package seal_test

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/ledger/internal/ingest"
	"mission/services-go/ledger/internal/seal"
	"mission/services-go/ledger/internal/testdb"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

var db *testdb.DB

// TestMain dựng PostgreSQL 17 với migration ledger thật; esign-service được giả lập bằng KeySigner
// (thay SoftHSM) và LocalTSA (RFC 3161). EVT-19 được kiểm ở ledger.outbox_event, chưa cần Kafka.
func TestMain(m *testing.M) {
	if os.Getenv("LEDGER_SKIP_IT") != "" {
		os.Exit(m.Run())
	}
	ctx := context.Background()
	pg, err := tcpg.Run(ctx, "postgres:17",
		tcpg.WithDatabase("ledger"), tcpg.WithUsername("postgres"), tcpg.WithPassword(testdb.RandomSecret()),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(90*time.Second)))
	if err != nil {
		fmt.Fprintln(os.Stderr, "không khởi động được PostgreSQL:", err)
		os.Exit(1)
	}
	dsn, _ := pg.ConnectionString(ctx, "sslmode=disable")
	if db, err = testdb.Bootstrap(ctx, dsn, true); err != nil {
		fmt.Fprintln(os.Stderr, "bootstrap ledger:", err)
		os.Exit(1)
	}
	code := m.Run()
	_ = pg.Terminate(ctx)
	os.Exit(code)
}

type env struct {
	pool   *pgxpool.Pool
	admin  *pgxpool.Pool
	ing    *ingest.Ingester
	signer *seal.KeySigner
	tsa    *flakyTSA
	otel   *otel.Provider
	sealer *seal.Sealer
}

// flakyTSA giả lập TSA lỗi khi down = true.
type flakyTSA struct {
	*seal.LocalTSA
	down bool
}

func (f *flakyTSA) Timestamp(ctx context.Context, d [32]byte) (seal.Token, error) {
	if f.down {
		return seal.Token{}, errors.New("TSA giả lập không phản hồi")
	}
	return f.LocalTSA.Timestamp(ctx, d)
}

func newEnv(t *testing.T, cell string, interval time.Duration) *env {
	t.Helper()
	if db == nil {
		t.Skip("LEDGER_SKIP_IT")
	}
	ctx := context.Background()
	k, c, err := seal.SelfSigned("Khoá niêm phong "+cell, false)
	if err != nil {
		t.Fatal(err)
	}
	local, err := seal.NewLocalTSA()
	if err != nil {
		t.Fatal(err)
	}
	prov, _ := otel.Init(otel.Config{ServiceName: "ledger", Cell: cell})
	e := &env{
		pool: testdb.Pool(ctx, t, db.SvcDSN), admin: testdb.Pool(ctx, t, db.AdminDSN),
		signer: &seal.KeySigner{Key: k, Cert: c, KeyID: "seal-" + cell}, tsa: &flakyTSA{LocalTSA: local}, otel: prov,
	}
	e.ing = ingest.NewIngester(e.pool, nil, nil)
	e.sealer = &seal.Sealer{Pool: e.pool, Signer: e.signer, TSA: e.tsa, Cell: cell, Interval: interval, OTel: prov}
	return e
}

// write ghi n sự kiện cho tenant, rải trên nhiều chuỗi.
func (e *env) write(t *testing.T, tenant uuid.UUID, n int) {
	t.Helper()
	ctx := context.Background()
	byChain := map[int16][]ingest.EventInput{}
	for i := 0; i < n; i++ {
		id := uuid.Must(uuid.NewV7())
		h := sha256.Sum256(id[:])
		ev := ingest.EventInput{EventID: id, TenantID: tenant, EventType: "task.task.progressed.v1", AggregateType: "TASK",
			AggregateID: uuid.New(), ActorKind: "DICH_VU", OccurredAt: time.Now().UTC(), PayloadHash: h[:], Classification: "C1"}
		byChain[ev.ChainPartition()] = append(byChain[ev.ChainPartition()], ev)
	}
	for p, evs := range byChain {
		for len(evs) > 0 {
			k := min(len(evs), ingest.MaxBatchSize)
			if _, err := e.ing.AppendBatch(ctx, tenant, p, evs[:k]); err != nil {
				t.Fatal(err)
			}
			evs = evs[k:]
		}
	}
}

func (e *env) count(t *testing.T, sql string, args ...any) int {
	t.Helper()
	var n int
	if err := e.admin.QueryRow(context.Background(), sql, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestBaTenantMotNiemPhongCell(t *testing.T) {
	e := newEnv(t, "IT-BA-TENANT", time.Hour)
	ctx := context.Background()
	tenants := []uuid.UUID{uuid.New(), uuid.New(), uuid.New()}
	for i, tn := range tenants {
		e.write(t, tn, 50+i*30)
	}
	windowEnd := time.Now().Add(time.Second)
	res, err := e.sealer.SealWindow(ctx, windowEnd, seal.KindPeriodic)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Tenants) != 3 || res.Late || res.Existing {
		t.Fatalf("mong 3 tenant, không trễ; có %d tenant, late=%v", len(res.Tenants), res.Late)
	}
	if n := e.count(t, `SELECT count(*) FROM ledger.cell_seal WHERE cell_code = 'IT-BA-TENANT'`); n != 1 {
		t.Fatalf("cell_seal = %d", n)
	}
	if n := e.count(t, `SELECT count(*) FROM ledger.merkle_seal WHERE cell_seal_id = $1`, res.CellSealID); n != 3 {
		t.Fatalf("merkle_seal = %d", n)
	}
	if n := e.count(t, `SELECT count(*) FROM ledger.outbox_event o JOIN ledger.merkle_seal m ON m.id = o.aggregate_id
		WHERE o.topic = 'ledger.seal.created.v1' AND m.cell_seal_id = $1`, res.CellSealID); n != 3 {
		t.Fatalf("EVT-19 trong outbox = %d (mỗi niêm phong tenant một sự kiện)", n)
	}
	if n := e.count(t, `SELECT count(*) FROM ledger.chain_head WHERE tenant_id = ANY($1) AND last_sealed_seq_no <> last_seq_no`, tenants); n != 0 {
		t.Fatalf("%d đầu chuỗi chưa cập nhật last_sealed_seq_no", n)
	}

	// Đường đi Merkle lưu trong inclusion_proof tái dựng đúng gốc cell từ gốc tenant.
	var (
		cellRoot, sig, tst []byte
		signedEnd          time.Time
	)
	if err := e.admin.QueryRow(ctx, `SELECT cell_root_hash, signature, rfc3161_tst, window_end FROM ledger.cell_seal WHERE id = $1`,
		res.CellSealID).Scan(&cellRoot, &sig, &tst, &signedEnd); err != nil {
		t.Fatal(err)
	}
	rows, err := e.admin.Query(ctx, `SELECT tenant_root_hash, inclusion_proof, seq_ranges FROM ledger.merkle_seal WHERE cell_seal_id = $1`, res.CellSealID)
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var (
			tr         []byte
			proof, rng []byte
		)
		if err := rows.Scan(&tr, &proof, &rng); err != nil {
			t.Fatal(err)
		}
		var p struct {
			CellLeafIndex int `json:"cellLeafIndex"`
			Path          []struct {
				S string `json:"s"`
				L bool   `json:"l"`
			} `json:"path"`
		}
		if err := json.Unmarshal(proof, &p); err != nil {
			t.Fatal(err)
		}
		steps := make([]seal.Step, len(p.Path))
		for i, s := range p.Path {
			b, _ := hex.DecodeString(s.S)
			copy(steps[i].Sibling[:], b)
			steps[i].Left = s.L
		}
		var root [32]byte
		copy(root[:], cellRoot)
		if !seal.Verify(seal.Leaf(tr), steps, root) {
			t.Fatalf("đường đi Merkle của lá %d không tái dựng được gốc cell", p.CellLeafIndex)
		}
		var ranges []seal.ChainRange
		_ = json.Unmarshal(rng, &ranges)
		got, err := seal.TenantRoot(ranges)
		if err != nil || !bytesEq(got[:], tr) {
			t.Fatal("gốc tenant không khớp các đầu chuỗi trong seq_ranges")
		}
	}
	rows.Close()

	// Chữ ký gốc cell kiểm bằng chứng thư khoá niêm phong.
	var root [32]byte
	copy(root[:], cellRoot)
	if !seal.VerifySignature(e.signer.Cert, seal.SignedMessage(root, [32]byte{}, signedEnd), sig) {
		t.Fatal("chữ ký gốc cell không hợp lệ")
	}
	// Token TSA kiểm bằng openssl ts -verify trên chứng thư thử.
	verifyOpenSSL(t, tst, cellRoot, e.tsa.Cert.Raw)

	// Chạy lại cùng cửa sổ không tạo niêm phong trùng.
	again, err := e.sealer.SealWindow(ctx, windowEnd, seal.KindPeriodic)
	if err != nil || !again.Existing || again.CellSealID != res.CellSealID {
		t.Fatalf("chạy lại phải trả niêm phong cũ: %+v %v", again, err)
	}
	if n := e.count(t, `SELECT count(*) FROM ledger.cell_seal WHERE cell_code = 'IT-BA-TENANT'`); n != 1 {
		t.Fatalf("chạy lại tạo thêm cell_seal: %d", n)
	}
}

func bytesEq(a, b []byte) bool { return hex.EncodeToString(a) == hex.EncodeToString(b) }

func verifyOpenSSL(t *testing.T, resp, data, caDER []byte) {
	t.Helper()
	bin, err := exec.LookPath("openssl")
	if err != nil {
		t.Log("không có openssl, bỏ qua bước openssl ts -verify (ParseToken đã kiểm chữ ký CMS)")
		return
	}
	dir := t.TempDir()
	write := func(name string, b []byte) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, b, 0o600); err != nil {
			t.Fatal(err)
		}
		return p
	}
	ca := write("tsa.pem", pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER}))
	out, err := exec.Command(bin, "ts", "-verify", "-in", write("resp.der", resp), "-data", write("root.bin", data),
		"-CAfile", ca, "-partial_chain").CombinedOutput()
	if err != nil {
		t.Fatalf("openssl ts -verify thất bại: %v\n%s", err, out)
	}
}

func TestTSALoiHaiCuaSoGopVaoCuaSoBa(t *testing.T) {
	e := newEnv(t, "IT-TSA-LOI", 300*time.Millisecond)
	ctx := context.Background()
	tn := uuid.New()
	e.write(t, tn, 20)
	first, err := e.sealer.SealWindow(ctx, time.Now(), seal.KindPeriodic)
	if err != nil {
		t.Fatal(err)
	}

	e.tsa.down = true
	for i := 0; i < 2; i++ {
		time.Sleep(350 * time.Millisecond)
		e.write(t, tn, 10)
		if _, err := e.sealer.SealWindow(ctx, time.Now(), seal.KindPeriodic); !errors.Is(err, seal.ErrTSAUnavailable) {
			t.Fatalf("cửa sổ %d: mong ErrTSAUnavailable, có %v", i+1, err)
		}
	}
	if got := e.otel.CounterValue("ledger.seal.tsa_failures_total"); got != 2 {
		t.Fatalf("tsa_failures_total = %d", got)
	}
	if n := e.count(t, `SELECT count(*) FROM ledger.cell_seal WHERE cell_code = 'IT-TSA-LOI'`); n != 1 {
		t.Fatalf("TSA lỗi không được ghi niêm phong dở: %d", n)
	}

	e.tsa.down = false
	time.Sleep(350 * time.Millisecond)
	e.write(t, tn, 10)
	third, err := e.sealer.SealWindow(ctx, time.Now(), seal.KindPeriodic)
	if err != nil {
		t.Fatal(err)
	}
	if !third.Late || !third.WindowStart.Equal(first.WindowEnd) || third.Token.DER == nil {
		t.Fatalf("cửa sổ thứ ba phải gộp từ cuối lần trước, có token bù và cờ trễ: %+v", third)
	}
	var late bool
	var gen *time.Time
	if err := e.admin.QueryRow(ctx, `SELECT tsa_late, tsa_gen_time FROM ledger.cell_seal WHERE id = $1`, third.CellSealID).Scan(&late, &gen); err != nil {
		t.Fatal(err)
	}
	if !late || gen == nil {
		t.Fatalf("tsa_late=%v tsa_gen_time=%v", late, gen)
	}
	if third.Tenants[0].Entries != 30 {
		t.Fatalf("cửa sổ gộp phải chứa 30 bản ghi của ba lần ghi sau, có %d", third.Tenants[0].Entries)
	}

	// Quá 24 giờ chưa có dấu thời gian thì phát metric cảnh báo.
	e.write(t, tn, 5)
	e.tsa.down = true
	if _, err := e.sealer.SealWindow(ctx, time.Now().Add(25*time.Hour), seal.KindPeriodic); !errors.Is(err, seal.ErrTSAUnavailable) {
		t.Fatal(err)
	}
	if got := e.otel.CounterValue("ledger.seal.tsa_overdue_total"); got != 1 {
		t.Fatalf("tsa_overdue_total = %d", got)
	}
	e.tsa.down = false
	if _, err := e.sealer.SealWindow(ctx, time.Now().Add(25*time.Hour), seal.KindAdHoc); err != nil {
		t.Fatal(err)
	}
}

func TestNeoHangNgay(t *testing.T) {
	e := newEnv(t, "IT-NEO", time.Hour)
	ctx := context.Background()
	e.write(t, uuid.New(), 10)
	res, err := e.sealer.SealWindow(ctx, time.Now().Add(time.Second), seal.KindPeriodic)
	if err != nil {
		t.Fatal(err)
	}
	a := &seal.Anchorer{Pool: e.pool, Cell: "IT-NEO", Store: seal.DirStore{Dir: t.TempDir()}}
	doc, err := a.AnchorDay(ctx, res.WindowEnd)
	if err != nil {
		t.Fatal(err)
	}
	if doc.CellSealID != res.CellSealID || doc.CellRoot != hex.EncodeToString(res.CellRoot[:]) {
		t.Fatalf("bản neo phải là gốc cell cuối ngày: %+v", doc)
	}
	if _, err := a.AnchorDay(ctx, res.WindowEnd); err != nil {
		t.Fatal("chạy lại job neo:", err)
	}
	if err := a.RecordReceipt(ctx, res.CellSealID, seal.AnchorArchive, []byte(`{"ok":true}`), time.Now()); err != nil {
		t.Fatal(err)
	}
	got, err := a.Receipts(ctx, res.CellSealID)
	if err != nil || len(got) != 2 {
		t.Fatalf("mong 2 biên nhận (KHO_VPTW, OBJECT_LOCK), có %v %v", got, err)
	}
	if err := a.RecordReceipt(ctx, res.CellSealID, "KHAC", []byte("x"), time.Now()); err == nil {
		t.Fatal("đích neo ngoài danh mục phải bị từ chối")
	}
	if _, err := a.DailyAnchor(ctx, res.WindowEnd.AddDate(0, 0, -3)); !errors.Is(err, seal.ErrNoSealForDay) {
		t.Fatalf("ngày không có niêm phong: %v", err)
	}
}

// TestDoThoiGianNiemPhong đo (chỉ ghi nhận, không kiểm tải theo YC-PT-19) thời gian niêm phong 20 tenant
// × 64 chuỗi.
func TestDoThoiGianNiemPhong(t *testing.T) {
	e := newEnv(t, "IT-DO", time.Hour)
	ctx := context.Background()
	for i := 0; i < 20; i++ {
		e.write(t, uuid.New(), 640)
	}
	start := time.Now()
	res, err := e.sealer.SealWindow(ctx, time.Now().Add(time.Second), seal.KindPeriodic)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("niêm phong %d tenant mất %v", len(res.Tenants), time.Since(start))
}

func TestLichCuaSo(t *testing.T) {
	end := time.Date(2026, 12, 31, 17, 0, 0, 0, time.UTC)
	s := seal.DefaultSchedule([]time.Time{end})
	if got := s.IntervalAt(end.AddDate(0, 0, -10)); got != time.Hour {
		t.Fatalf("ngày thường: %v", got)
	}
	if got := s.IntervalAt(end.Add(-time.Hour)); got != 5*time.Minute {
		t.Fatalf("3 ngày cuối kỳ: %v", got)
	}
	if got := s.WindowEnd(end.Add(-7*time.Minute + 3*time.Second)); !got.Equal(end.Add(-10 * time.Minute)) {
		t.Fatalf("mốc cửa sổ 5 phút: %v", got)
	}
}

// TestHaiReplicaCungCuaSo: hai replica niêm phong cùng mốc chỉ tạo một cell_seal.
func TestHaiReplicaCungCuaSo(t *testing.T) {
	e := newEnv(t, "IT-REPLICA", time.Hour)
	other := newEnv(t, "IT-REPLICA", time.Hour)
	ctx := context.Background()
	e.write(t, uuid.New(), 40)
	end := time.Now().Add(time.Second)
	errs := make(chan error, 2)
	for _, s := range []*seal.Sealer{e.sealer, other.sealer} {
		go func() { _, err := s.SealWindow(ctx, end, seal.KindPeriodic); errs <- err }()
	}
	for i := 0; i < 2; i++ {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
	}
	if n := e.count(t, `SELECT count(*) FROM ledger.cell_seal WHERE cell_code = 'IT-REPLICA'`); n != 1 {
		t.Fatalf("cell_seal = %d", n)
	}
}
