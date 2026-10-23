package archiver_test

// Kiểm thử tích hợp INF-24 trên container thật: Kafka (confluent-local, KRaft), Ceph RGW 19 một nút (quay.io/ceph/demo,
// vai kho sao lưu có Object Lock; ảnh MinIO không còn tải công khai được) và OpenBao dev có khoá Transit ed25519.
//   - 10.000 sự kiện vào đủ kho, giết tiến trình (SIGKILL) giữa chừng và chạy lại không mất, không trùng.
//   - Sự cố sau khi ghi kê khai mà trước khi ghi dữ liệu: lần chạy sau dựng lại tệp từ Kafka, khớp SHA-256 đã ký.
//   - Kho từ chối xoá phiên bản và rút ngắn hạn giữ; ghi đè hay sửa một byte thì audit-verify báo lỗi.
//   - Job kiểm hằng ngày đạt và đối chiếu được offset Kafka; độ trễ từ sự kiện tới tệp đo được qua chỉ số.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/testcontainers/testcontainers-go"
	tckafka "github.com/testcontainers/testcontainers-go/modules/kafka"
	"github.com/testcontainers/testcontainers-go/wait"
	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"

	"mission/services-go/audit-archiver/internal/archive"
	"mission/services-go/audit-archiver/internal/archiver"
	"mission/services-go/audit-archiver/internal/check"
	"mission/services-go/audit-archiver/internal/metrics"
	"mission/services-go/audit-archiver/internal/objstore"
	"mission/services-go/audit-archiver/internal/transit"
	"mission/services-go/audit-archiver/internal/verify"
)

const (
	cell      = "cell-it"
	baoToken  = "tok-it"
	s3Access  = "kho-it-truy-cap"
	s3Secret  = "kho-it-bi-mat-chi-dung-trong-kiem-thu"
	retention = 24 * time.Hour
)

type moiTruong struct {
	brokers []string
	s3      string
	bao     string
	bins    string
}

var (
	envOnce sync.Once
	envVal  *moiTruong
	envErr  error
)

func batDau(t *testing.T) *moiTruong {
	t.Helper()
	if testing.Short() {
		t.Skip("kiểm thử tích hợp cần Docker")
	}
	envOnce.Do(func() { envVal, envErr = dungMoiTruong() })
	if envErr != nil {
		t.Fatalf("dựng môi trường: %v", envErr)
	}
	return envVal
}

func dungMoiTruong() (*moiTruong, error) {
	ctx := context.Background()
	e := &moiTruong{}
	var wg sync.WaitGroup
	errs := make(chan error, 4)
	wg.Add(4)
	go func() {
		defer wg.Done()
		kc, err := tckafka.Run(ctx, "confluentinc/confluent-local:7.5.0", tckafka.WithClusterID("audit-it"))
		if err != nil {
			errs <- fmt.Errorf("kafka: %w", err)
			return
		}
		e.brokers, err = kc.Brokers(ctx)
		if err != nil {
			errs <- err
		}
	}()
	go func() {
		defer wg.Done()
		c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{Started: true, ContainerRequest: testcontainers.ContainerRequest{
			Image:      "quay.io/ceph/demo:latest-squid",
			Entrypoint: []string{"sh", "-c", `export MON_IP=$(hostname -i | cut -d" " -f1); exec /opt/ceph-container/bin/demo`},
			Env: map[string]string{"CEPH_PUBLIC_NETWORK": "0.0.0.0/0", "DEMO_DAEMONS": "osd,rgw", "RGW_FRONTEND_PORT": "8080",
				"OSD_SIZE": "1024", "CEPH_DEMO_UID": "audit-it", "CEPH_DEMO_ACCESS_KEY": s3Access, "CEPH_DEMO_SECRET_KEY": s3Secret},
			Tmpfs:        map[string]string{"/var/lib/ceph": "rw,size=3g"},
			ExposedPorts: []string{"8080/tcp"},
			WaitingFor: wait.ForHTTP("/").WithPort("8080/tcp").WithStatusCodeMatcher(func(int) bool { return true }).
				WithStartupTimeout(6 * time.Minute),
		}})
		if err != nil {
			errs <- fmt.Errorf("ceph: %w", err)
			return
		}
		ep, err := c.PortEndpoint(ctx, "8080/tcp", "")
		if err != nil {
			errs <- err
			return
		}
		// RGW của ảnh demo hiểu tên máy "localhost" là bucket kiểu virtual-host; gọi bằng địa chỉ IP.
		e.s3 = strings.Replace(ep, "localhost", "127.0.0.1", 1)
	}()
	go func() {
		defer wg.Done()
		c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{Started: true, ContainerRequest: testcontainers.ContainerRequest{
			Image:        "quay.io/openbao/openbao:2.1.0",
			Cmd:          []string{"server", "-dev", "-dev-root-token-id=" + baoToken, "-dev-listen-address=0.0.0.0:8200"},
			ExposedPorts: []string{"8200/tcp"},
			WaitingFor:   wait.ForHTTP("/v1/sys/health").WithPort("8200/tcp").WithStartupTimeout(2 * time.Minute),
		}})
		if err != nil {
			errs <- fmt.Errorf("openbao: %w", err)
			return
		}
		ep, err := c.PortEndpoint(ctx, "8200/tcp", "http")
		if err != nil {
			errs <- err
			return
		}
		e.bao = ep
		for _, r := range [][2]string{{"/v1/sys/mounts/transit", `{"type":"transit"}`}, {"/v1/transit/keys/audit-archiver", `{"type":"ed25519","exportable":false}`}} {
			req, _ := http.NewRequest(http.MethodPost, ep+r[0], strings.NewReader(r[1]))
			req.Header.Set("X-Vault-Token", baoToken)
			resp, err := http.DefaultClient.Do(req)
			if err != nil || resp.StatusCode/100 != 2 {
				errs <- fmt.Errorf("cấu hình Transit %s: %v", r[0], err)
				return
			}
			resp.Body.Close()
		}
	}()
	go func() {
		defer wg.Done()
		dir, err := os.MkdirTemp("", "audit-archiver-bin")
		if err != nil {
			errs <- err
			return
		}
		for _, c := range []string{"audit-archiver", "audit-verify"} {
			out, err := exec.Command("go", "build", "-o", filepath.Join(dir, c), "../../cmd/"+c).CombinedOutput()
			if err != nil {
				errs <- fmt.Errorf("build %s: %v\n%s", c, err, out)
				return
			}
		}
		e.bins = dir
	}()
	wg.Wait()
	close(errs)
	for err := range errs {
		return nil, err
	}
	return e, nil
}

func (e *moiTruong) store(t *testing.T, bucket string, mode string) *objstore.Store {
	st, err := objstore.New(objstore.Config{Endpoint: e.s3, Bucket: bucket, AccessKey: s3Access, SecretKey: s3Secret,
		Region: "us-east-1", Mode: mode, Retention: retention})
	if err != nil {
		t.Fatal(err)
	}
	return st
}

// taoBucket tạo bucket có Object Lock (RGW cần vài giây sau khi cổng mở mới nhận người dùng demo).
func (e *moiTruong) taoBucket(t *testing.T, bucket string, lock bool) *objstore.Store {
	st := e.store(t, bucket, "COMPLIANCE")
	ctx := context.Background()
	var err error
	for i := 0; i < 60; i++ {
		err = st.Client().MakeBucket(ctx, bucket, minio.MakeBucketOptions{Region: "us-east-1", ObjectLocking: lock})
		if err == nil {
			return st
		}
		time.Sleep(2 * time.Second)
	}
	t.Fatalf("tạo bucket %s: %v", bucket, err)
	return nil
}

func (e *moiTruong) taoTopic(t *testing.T, topic string, partitions int32) *kgo.Client {
	cl, err := kgo.NewClient(kgo.SeedBrokers(e.brokers...), kgo.ProducerLinger(5*time.Millisecond))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(cl.Close)
	if _, err := kadm.NewClient(cl).CreateTopic(context.Background(), partitions, 1, nil, topic); err != nil {
		t.Fatal(err)
	}
	return cl
}

// phat ghi n sự kiện kiểm toán giả (thân Avro giả có tiền tố mã lược đồ, header phong bì CloudEvents).
func phat(t *testing.T, cl *kgo.Client, topic string, from, n int) {
	t.Helper()
	recs := make([]*kgo.Record, 0, n)
	for i := from; i < from+n; i++ {
		recs = append(recs, &kgo.Record{
			Topic: topic,
			Key:   []byte(fmt.Sprintf("tenant-%d", i%7)),
			Value: append([]byte{0, 0, 0, 0, 42}, []byte(fmt.Sprintf("su-kien-kiem-toan-%06d", i))...),
			Headers: []kgo.RecordHeader{{Key: "ce_id", Value: []byte(fmt.Sprintf("evt-%06d", i))},
				{Key: "ce_type", Value: []byte("sys.audit.v1")}, {Key: "tenant_id", Value: []byte(fmt.Sprintf("tenant-%d", i%7))}},
		})
	}
	if err := cl.ProduceSync(context.Background(), recs...).FirstErr(); err != nil {
		t.Fatal(err)
	}
}

func (e *moiTruong) envFor(topic, bucket, group string, mgmt string) []string {
	return append(os.Environ(),
		"CELL="+cell, "KAFKA_BROKERS="+strings.Join(e.brokers, ","), "KAFKA_GROUP="+group, "TOPICS="+topic,
		"S3_ENDPOINT="+e.s3, "S3_TLS=false", "S3_REGION=us-east-1", "S3_BUCKET="+bucket,
		"S3_ACCESS_KEY="+s3Access, "S3_SECRET_KEY="+s3Secret, "LOCK_MODE=COMPLIANCE", "LOCK_RETENTION="+retention.String(),
		"BAO_ADDR="+e.bao, "BAO_TOKEN="+baoToken, "BATCH_WINDOW=2s", "BATCH_MAX_RECORDS=700", "MGMT_ADDR="+mgmt)
}

func cong(t *testing.T) string {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().String()
}

func (e *moiTruong) keys(t *testing.T) *transit.Client {
	return &transit.Client{Addr: e.bao, Key: "audit-archiver", Token: baoToken}
}

func kiem(t *testing.T, e *moiTruong, st *objstore.Store, topic string) *verify.Report {
	t.Helper()
	keys, err := e.keys(t).PublicKeys(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	r, err := verify.Run(context.Background(), st, verify.Options{Cell: cell, Topic: topic, Keys: keys})
	if err != nil {
		t.Fatal(err)
	}
	return r
}

// demBanGhi đếm bản ghi đã khoá xong (theo kê khai có đủ chữ ký và dữ liệu).
func demBanGhi(t *testing.T, st *objstore.Store, topic string) int64 {
	var n int64
	ctx := context.Background()
	have := map[string]bool{}
	_ = st.List(ctx, archive.Prefix(cell, topic), "", func(k string) error { have[k] = true; return nil })
	for k := range have {
		if strings.HasSuffix(k, ".json") && have[strings.TrimSuffix(k, ".json")+".parquet"] {
			b, err := st.Get(ctx, k)
			if err == nil {
				if m, err := archive.ParseManifest(b); err == nil {
					n += m.SoBanGhi
				}
			}
		}
	}
	return n
}

func choDen(t *testing.T, timeout time.Duration, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if ok() {
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("hết giờ chờ: %s", what)
}

func chay(t *testing.T, e *moiTruong, env []string, logf string) *exec.Cmd {
	f, err := os.OpenFile(logf, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(filepath.Join(e.bins, "audit-archiver"))
	cmd.Env, cmd.Stdout, cmd.Stderr = env, f, f
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _, _ = cmd.Process.Wait(); f.Close() })
	return cmd
}

func TestMuoiNghinSuKienGietTienTrinhKhongMatKhongTrung(t *testing.T) {
	e := batDau(t)
	const topic, bucket, total = "sys.audit.v1", "audit-it", 10_000
	st := e.taoBucket(t, bucket, true)
	cl := e.taoTopic(t, topic, 3)
	logf := filepath.Join(t.TempDir(), "archiver.log")
	mgmt := cong(t)
	env := e.envFor(topic, bucket, "audit-archiver", mgmt)

	phat(t, cl, topic, 0, total/2)
	start := time.Now()
	p1 := chay(t, e, env, logf)
	choDen(t, 3*time.Minute, "lưu một phần lô đầu", func() bool { return demBanGhi(t, st, topic) >= 1500 })
	// Giết tiến trình giữa lô: có lô đã gom chưa ghi, có thể có lô đã ghi mà chưa xác nhận offset.
	_ = p1.Process.Signal(syscall.SIGKILL)
	_, _ = p1.Process.Wait()
	phat(t, cl, topic, total/2, total/2)
	chay(t, e, env, logf)
	choDen(t, 5*time.Minute, "lưu đủ 10.000 sự kiện", func() bool { return demBanGhi(t, st, topic) >= total })
	t.Logf("10.000 sự kiện vào kho sau %s", time.Since(start).Round(time.Second))

	// Độ trễ từ bản ghi đầu lô tới lúc lô khoá xong, đọc từ chỉ số của dịch vụ.
	resp, err := http.Get("http://" + mgmt + "/metrics")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	maxLag := 0.0
	for _, line := range strings.Split(string(body), "\n") {
		if strings.HasPrefix(line, archiver.MBatchLag+"{") {
			v, _ := strconv.ParseFloat(line[strings.LastIndexByte(line, ' ')+1:], 64)
			maxLag = max(maxLag, v)
		}
	}
	if maxLag <= 0 || maxLag > 300 {
		t.Fatalf("độ trễ từ sự kiện tới tệp phải đo được và ≤ 5 phút, đo được %.1f s\n%s", maxLag, body)
	}
	t.Logf("độ trễ lô cuối lớn nhất: %.1f s", maxLag)

	rep := kiem(t, e, st, topic)
	if !rep.OK() || rep.SoBanGhi != total {
		t.Fatalf("kho phải đạt với đúng %d bản ghi: %d bản ghi, lỗi %v", total, rep.SoBanGhi, rep.Loi)
	}
	// Không trùng: mỗi ce_id xuất hiện đúng một lần trên toàn kho, thân và header giữ nguyên.
	seen := map[string]bool{}
	ctx := context.Background()
	_ = st.List(ctx, archive.Prefix(cell, topic), "", func(k string) error {
		if !strings.HasSuffix(k, ".parquet") {
			return nil
		}
		b, err := st.Get(ctx, k)
		if err != nil {
			t.Fatal(err)
		}
		rows, err := archive.DecodeParquet(b)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range rows {
			id := string(r.Headers[0].Value)
			if seen[id] {
				t.Fatalf("sự kiện %s bị trùng", id)
			}
			seen[id] = true
			if want := "su-kien-kiem-toan-" + strings.TrimPrefix(id, "evt-"); !bytes.Equal(r.Value[5:], []byte(want)) || r.Value[4] != 42 {
				t.Fatalf("thân Avro bị đổi: %q", r.Value)
			}
		}
		return nil
	})
	if len(seen) != total {
		t.Fatalf("có %d sự kiện khác nhau, cần %d", len(seen), total)
	}

	t.Run("kho-tu-choi-xoa-va-rut-han", func(t *testing.T) {
		var key string
		_ = st.List(ctx, archive.Prefix(cell, topic), "", func(k string) error {
			if key == "" && strings.HasSuffix(k, ".parquet") {
				key = k
			}
			return nil
		})
		info, err := st.Client().StatObject(ctx, bucket, key, minio.StatObjectOptions{})
		if err != nil {
			t.Fatal(err)
		}
		if err := st.Client().RemoveObject(ctx, bucket, key, minio.RemoveObjectOptions{VersionID: info.VersionID}); err == nil {
			t.Fatal("xoá phiên bản bị khoá COMPLIANCE phải bị kho từ chối")
		} else {
			t.Logf("kho từ chối xoá: %v", err)
		}
		if err := st.Client().RemoveObject(ctx, bucket, key, minio.RemoveObjectOptions{VersionID: info.VersionID, GovernanceBypass: true}); err == nil {
			t.Fatal("bỏ qua governance không được xoá đối tượng COMPLIANCE")
		}
		short := time.Now().Add(time.Hour)
		mode := minio.Compliance
		if err := st.Client().PutObjectRetention(ctx, bucket, key, minio.PutObjectRetentionOptions{Mode: &mode, RetainUntilDate: &short, VersionID: info.VersionID}); err == nil {
			t.Fatal("rút ngắn hạn giữ phải bị từ chối")
		}
		if ok, _ := st.Exists(ctx, key); !ok {
			t.Fatal("đối tượng phải còn")
		}
	})

	t.Run("audit-verify-doc-lap", func(t *testing.T) {
		dir := t.TempDir()
		pubf := filepath.Join(dir, "audit-archiver.pub.json")
		cmd := exec.Command(filepath.Join(e.bins, "audit-archiver"), "khoa-cong-khai")
		cmd.Env = env
		pub, err := cmd.Output()
		if err != nil {
			t.Fatal(err)
		}
		_ = os.WriteFile(pubf, pub, 0o600)
		verifyCmd := func(b string) (int, string) {
			c := exec.Command(filepath.Join(e.bins, "audit-verify"), "-endpoint", e.s3, "-khong-tls", "-region", "us-east-1",
				"-bucket", b, "-cell", cell, "-topic", topic, "-khoa-cong-khai", pubf)
			c.Env = append(os.Environ(), "AWS_ACCESS_KEY_ID="+s3Access, "AWS_SECRET_ACCESS_KEY="+s3Secret)
			out, err := c.CombinedOutput()
			var ee *exec.ExitError
			if errors.As(err, &ee) {
				return ee.ExitCode(), string(out)
			}
			if err != nil {
				t.Fatal(err)
			}
			return 0, string(out)
		}
		if code, out := verifyCmd(bucket); code != 0 || !strings.Contains(out, `"soBanGhi": 10000`) {
			t.Fatalf("audit-verify trên kho nguyên vẹn phải đạt (mã %d):\n%s", code, out[max(0, len(out)-2000):])
		}
		// Bản sao có một byte bị sửa: chép toàn bộ sang bucket không khoá, lật một bit của một tệp Parquet.
		copyB := "audit-it-ban-sao"
		cp := e.taoBucket(t, copyB, false)
		var tampered string
		_ = st.List(ctx, archive.Prefix(cell, topic), "", func(k string) error {
			b, _ := st.Get(ctx, k)
			if tampered == "" && strings.HasSuffix(k, ".parquet") {
				b[len(b)/3] ^= 0x01
				tampered = k
			}
			_, err := cp.Client().PutObject(ctx, copyB, k, bytes.NewReader(b), int64(len(b)), minio.PutObjectOptions{SendContentMd5: true})
			return err
		})
		code, out := verifyCmd(copyB)
		if code != 1 || !strings.Contains(out, "SHA-256") || !strings.Contains(out, tampered) {
			t.Fatalf("sửa một byte phải bị audit-verify báo lỗi (mã %d):\n%s", code, out)
		}
		// Ghi đè đối tượng trong kho thật chỉ sinh phiên bản mới (bản gốc vẫn bị khoá), và audit-verify bắt được.
		if _, err := st.Client().PutObject(ctx, bucket, tampered, strings.NewReader("ghi-de"), 6, minio.PutObjectOptions{SendContentMd5: true}); err == nil {
			code, out = verifyCmd(bucket)
			if code != 1 || !strings.Contains(out, tampered) {
				t.Fatalf("ghi đè phải bị audit-verify phát hiện (mã %d):\n%s", code, out)
			}
			vs := 0
			for o := range st.Client().ListObjects(ctx, bucket, minio.ListObjectsOptions{Prefix: tampered, WithVersions: true}) {
				if o.Err == nil {
					vs++
				}
			}
			if vs < 2 {
				t.Fatalf("bản gốc phải còn dưới dạng phiên bản cũ, có %d phiên bản", vs)
			}
		} else {
			t.Logf("kho từ chối ghi đè: %v", err)
		}
	})
}

// Sự cố giữa kê khai và dữ liệu: dựng lại tệp từ Kafka, khớp SHA-256 của kê khai đã ký; job kiểm hằng ngày đạt.
func TestSuCoSauKeKhaiDungLaiTuKafkaVaKiemHangNgay(t *testing.T) {
	e := batDau(t)
	const topic, bucket = "iam.auth.event.v1", "audit-it-khoi-phuc"
	st := e.taoBucket(t, bucket, true)
	cl := e.taoTopic(t, topic, 1)
	phat(t, cl, topic, 0, 300)
	ctx := context.Background()
	signer := e.keys(t)
	cfg := archiver.Config{Brokers: e.brokers, Group: "audit-archiver-it", Topics: []string{topic}, Cell: cell,
		SignKey: "audit-archiver", Retention: retention, Window: time.Second, MaxRecords: 100}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))

	// Lần chạy 1: lô thứ hai ghi được kê khai và chữ ký nhưng ghi dữ liệu thất bại, rồi tiến trình dừng.
	failing := &hongDuLieu{Store: st, after: 1}
	a1, err := archiver.New(cfg, failing, signer, log, metrics.New())
	if err != nil {
		t.Fatal(err)
	}
	c1, cancel1 := context.WithCancel(ctx)
	go func() { _ = a1.Run(c1) }()
	choDen(t, 2*time.Minute, "lô hỏng dữ liệu", func() bool { return failing.failed.Load() })
	cancel1()
	a1.Close()

	// Lần chạy 2: khôi phục phát hiện kê khai thiếu dữ liệu, dựng lại từ Kafka rồi chạy tiếp.
	reg := metrics.New()
	a2, err := archiver.New(cfg, st, signer, log, reg)
	if err != nil {
		t.Fatal(err)
	}
	c2, cancel2 := context.WithCancel(ctx)
	defer cancel2()
	go func() { _ = a2.Run(c2) }()
	choDen(t, 2*time.Minute, "lưu đủ 300 sự kiện", func() bool { return demBanGhi(t, st, topic) >= 300 })
	choDen(t, 30*time.Second, "xác nhận offset", func() bool {
		o, err := kadm.NewClient(cl).FetchOffsets(ctx, "audit-archiver-it")
		if err != nil {
			return false
		}
		c, ok := o.Lookup(topic, 0)
		return ok && c.At == 300 && strings.HasSuffix(c.Metadata, ".json")
	})
	if s := a2.Status(); len(s.Broken) != 0 {
		t.Fatalf("không phân vùng nào được dừng: %v", s.Broken)
	}
	rep := kiem(t, e, st, topic)
	if !rep.OK() || rep.SoBanGhi != 300 || rep.SoTep != 3 {
		t.Fatalf("kho phải đạt với 3 tệp, 300 bản ghi: %+v", rep)
	}

	// Kiểm hằng ngày: đạt, offset nhóm khớp offset kết thúc của kê khai cuối.
	keys, _ := signer.PublicKeys(ctx)
	res, err := check.Run(ctx, st, kadm.NewClient(cl), check.Options{Group: "audit-archiver-it",
		Verify: verify.Options{Cell: cell, Topic: topic, Keys: keys, From: time.Now().Add(-48 * time.Hour)}})
	if err != nil {
		t.Fatal(err)
	}
	if !res.Kho.OK() || len(res.Kafka) != 1 || res.Kafka[0].DenOffset != 300 || res.Kafka[0].DaXacNhan != 300 || res.Kafka[0].ChuaLuu != 0 {
		out, _ := json.Marshal(res)
		t.Fatalf("kiểm hằng ngày phải đạt: %s", out)
	}
	cancel2()
	a2.Close()

	// Lệnh kiem-tra của CronJob: đạt thì mã thoát 0.
	env := e.envFor(topic, bucket, "audit-archiver-it", cong(t))
	cmd := exec.Command(filepath.Join(e.bins, "audit-archiver"), "kiem-tra")
	cmd.Env = env
	if out, err := cmd.CombinedOutput(); err != nil || !strings.Contains(string(out), "kiểm kho lưu trữ đạt") {
		t.Fatalf("kiem-tra phải đạt: %v\n%s", err, out)
	}
}

// hongDuLieu làm hỏng lần ghi tệp dữ liệu thứ after+1 (sau khi kê khai và chữ ký của lô đó đã ghi).
type hongDuLieu struct {
	*objstore.Store
	after  int
	n      int
	failed atomicBool
}

type atomicBool struct {
	mu sync.Mutex
	v  bool
}

func (b *atomicBool) Load() bool   { b.mu.Lock(); defer b.mu.Unlock(); return b.v }
func (b *atomicBool) Store(v bool) { b.mu.Lock(); defer b.mu.Unlock(); b.v = v }

func (h *hongDuLieu) PutLocked(ctx context.Context, key string, data []byte, ct string) (bool, error) {
	if strings.HasSuffix(key, ".parquet") {
		if h.failed.Load() {
			return false, errors.New("giả lập sự cố: tiến trình đã dừng")
		}
		if h.n == h.after {
			h.failed.Store(true)
			return false, errors.New("giả lập sự cố khi ghi dữ liệu")
		}
		h.n++
	}
	if h.failed.Load() {
		return false, errors.New("giả lập sự cố: tiến trình đã dừng")
	}
	return h.Store.PutLocked(ctx, key, data, ct)
}
