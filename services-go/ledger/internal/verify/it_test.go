package verify_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/ledger/internal/auth"
	"mission/services-go/ledger/internal/auth/authtest"
	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/contract"
	"mission/services-go/ledger/internal/ingest"
	"mission/services-go/ledger/internal/seal"
	"mission/services-go/ledger/internal/server"
	"mission/services-go/ledger/internal/testdb"
	"mission/services-go/ledger/internal/verify"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

var db *testdb.DB

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
	pool, admin *pgxpool.Pool
	ing         *ingest.Ingester
	sealer      *seal.Sealer
	svc         *verify.Service
	otel        *otel.Provider
}

func newEnv(t *testing.T, cell string) *env {
	t.Helper()
	if db == nil {
		t.Skip("LEDGER_SKIP_IT")
	}
	ctx := context.Background()
	k, c, err := seal.SelfSigned("Khoá niêm phong "+cell, false)
	if err != nil {
		t.Fatal(err)
	}
	tsa, err := seal.NewLocalTSA()
	if err != nil {
		t.Fatal(err)
	}
	prov, _ := otel.Init(otel.Config{ServiceName: "ledger", Cell: cell})
	e := &env{pool: testdb.Pool(ctx, t, db.SvcDSN), admin: testdb.Pool(ctx, t, db.AdminDSN), otel: prov}
	e.ing = ingest.NewIngester(e.pool, nil, nil)
	e.sealer = &seal.Sealer{Pool: e.pool, Signer: &seal.KeySigner{Key: k, Cert: c, KeyID: "k-" + cell}, TSA: tsa, Cell: cell, Interval: time.Hour}
	e.svc = &verify.Service{Pool: e.pool, Cell: cell, OTel: prov,
		Engine: &verify.Engine{Pool: e.pool, Workers: 8, Certs: map[[32]byte]*x509.Certificate{sha256.Sum256(c.Raw): c}}}
	return e
}

func (e *env) write(t *testing.T, tenant uuid.UUID, n int) {
	t.Helper()
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
			if _, err := e.ing.AppendBatch(context.Background(), tenant, p, evs[:k]); err != nil {
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

// tamper sửa trực tiếp payload_hash một bản ghi bằng superuser, tắt trigger bất biến cho phiên.
func (e *env) tamper(t *testing.T, tenant uuid.UUID, p int16, seq int64) {
	t.Helper()
	ctx := context.Background()
	conn, err := e.admin.Acquire(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, `SET session_replication_role = replica`); err != nil {
		t.Fatal(err)
	}
	ct, err := conn.Exec(ctx, `UPDATE ledger.ledger_entry SET payload_hash = sha256(payload_hash)
		WHERE tenant_id = $1 AND chain_partition = $2 AND seq_no = $3`, tenant, p, seq)
	if err != nil || ct.RowsAffected() != 1 {
		t.Fatalf("sửa bản ghi: %v (%d dòng)", err, ct.RowsAffected())
	}
	_, _ = conn.Exec(ctx, `RESET session_replication_role`)
}

// busiest là chuỗi có nhiều bản ghi nhất của tenant.
func (e *env) busiest(t *testing.T, tenant uuid.UUID) (int16, int64) {
	var p int16
	var n int64
	if err := e.admin.QueryRow(context.Background(), `SELECT chain_partition, last_seq_no FROM ledger.chain_head
		WHERE tenant_id = $1 ORDER BY last_seq_no DESC, chain_partition LIMIT 1`, tenant).Scan(&p, &n); err != nil {
		t.Fatal(err)
	}
	return p, n
}

func TestChuoiNguyenVen(t *testing.T) {
	e := newEnv(t, "IT-VF-OK")
	ctx := context.Background()
	tn := uuid.New()
	e.write(t, tn, 600)
	if _, err := e.sealer.SealWindow(ctx, time.Now().Add(time.Second), seal.KindPeriodic); err != nil {
		t.Fatal(err)
	}
	e.write(t, tn, 50) // phần chưa niêm phong cho DAU_CHUOI
	for _, kind := range []string{verify.KindFull, verify.KindHead} {
		run, err := e.svc.RunNow(ctx, verify.Request{TenantID: tn, Kind: kind})
		if err != nil {
			t.Fatal(err)
		}
		if run.Status != verify.StatusDone || run.Mismatches != 0 || run.PartitionsMatched != run.PartitionsTotal || run.PartitionsTotal == 0 {
			t.Fatalf("%s: %+v", kind, run)
		}
		if kind == verify.KindFull && (run.EntriesChecked != 650 || run.SealsChecked != 1) {
			t.Fatalf("TOAN_BO phải kiểm 650 bản ghi và 1 niêm phong: %d, %d", run.EntriesChecked, run.SealsChecked)
		}
	}
	run, err := e.svc.RunNow(ctx, verify.Request{Kind: verify.KindSeal, From: time.Now().Add(-time.Hour), To: time.Now().Add(time.Hour)})
	if err != nil || run.Mismatches != 0 || run.SealsChecked == 0 || run.TenantID != nil {
		t.Fatalf("NIEM_PHONG toàn cell: %+v %v", run, err)
	}
}

func TestSuaTrucTiepBiPhatHien(t *testing.T) {
	e := newEnv(t, "IT-VF-SUA")
	ctx := context.Background()
	tn := uuid.New()
	e.write(t, tn, 800)
	if _, err := e.sealer.SealWindow(ctx, time.Now().Add(time.Second), seal.KindPeriodic); err != nil {
		t.Fatal(err)
	}
	p, last := e.busiest(t, tn)
	if last < 6 {
		t.Fatalf("chuỗi quá ngắn: %d", last)
	}
	e.tamper(t, tn, p, 5)

	run, err := e.svc.RunNow(ctx, verify.Request{TenantID: tn, Kind: verify.KindFull})
	if err != nil {
		t.Fatal(err)
	}
	f := run.First
	if run.Mismatches != 1 || f == nil || f.ChainPartition == nil || *f.ChainPartition != p || *f.SeqNo != 5 {
		t.Fatalf("phải chỉ đúng chuỗi %d, seq_no 5: mismatches=%d first=%+v", p, run.Mismatches, f)
	}
	if run.PartitionsMatched != run.PartitionsTotal-1 || run.AlertID == nil || len(run.Links) < 3 {
		t.Fatalf("kết quả: %+v", run)
	}
	want := fmt.Sprintf("Phát hiện chuỗi băm bị phá vỡ tại bản ghi 5 của phân vùng %d. Đã tạo cảnh báo mức Cao và khoá ghi phân vùng.", p)
	if run.Detail != want {
		t.Fatalf("thông điệp: %q", run.Detail)
	}
	if n := e.count(t, `SELECT count(*) FROM ledger.outbox_event WHERE topic = 'analytics.anomaly.detected.v1' AND tenant_id = $1`, tn); n != 1 {
		t.Fatalf("EVT-20 = %d", n)
	}
	if n := e.count(t, `SELECT count(*) FROM ledger.chain_head WHERE tenant_id = $1 AND is_locked`, tn); n != 1 {
		t.Fatalf("số chuỗi bị khoá = %d", n)
	}
	if got := e.otel.CounterValue("ledger.verify.failures_total"); got != 1 {
		t.Fatalf("failures_total = %d", got)
	}
	// Chuỗi bị khoá không nhận ghi mới.
	agg := uuid.New()
	for canon.ChainPartition(agg) != p {
		agg = uuid.New()
	}
	h := sha256.Sum256(agg[:])
	_, err = e.ing.AppendBatch(ctx, tn, p, []ingest.EventInput{{EventID: uuid.Must(uuid.NewV7()), TenantID: tn,
		EventType: "task.task.progressed.v1", AggregateType: "TASK", AggregateID: agg, ActorKind: "DICH_VU",
		OccurredAt: time.Now(), PayloadHash: h[:], Classification: "C1"}})
	if !errors.Is(err, ingest.ErrPartitionLocked) {
		t.Fatalf("ghi vào chuỗi bị khoá: %v", err)
	}
}

func TestLichToanBoMotPhanTu(t *testing.T) {
	ts := make([]uuid.UUID, 8)
	for i := range ts {
		ts[i] = uuid.New()
	}
	seen := map[uuid.UUID]int{}
	for w := 0; w < 4; w++ {
		day := time.Date(2026, 11, 1, 2, 0, 0, 0, time.UTC).AddDate(0, 0, 7*w)
		share := verify.WeeklyShare(ts, day)
		if len(share) != 2 {
			t.Fatalf("tuần %d: %d tenant", w, len(share))
		}
		for _, x := range share {
			seen[x]++
		}
	}
	if len(seen) != 8 {
		t.Fatalf("bốn tuần phải phủ đủ 8 tenant, có %d", len(seen))
	}
}

func TestApiKhopHopDong(t *testing.T) {
	e := newEnv(t, "IT-VF-API")
	ctx := context.Background()
	tn := uuid.New()
	e.write(t, tn, 120)
	if _, err := e.sealer.SealWindow(ctx, time.Now().Add(time.Second), seal.KindPeriodic); err != nil {
		t.Fatal(err)
	}
	spec, err := contract.Load(filepath.Join(testdb.RepoRoot(), "contracts", "openapi"))
	if err != nil {
		t.Fatal(err)
	}
	keys := authtest.New()
	defer keys.Close()
	srv := httptest.NewServer(server.New(server.Deps{Pool: e.pool, Verify: e.svc, Auditor: e.ing,
		Auth: auth.NewJWKSVerifier(auth.JWKSConfig{JWKSURL: keys.JWKSURL(), Issuers: []string{authtest.Issuer}}),
		Log:  slogDiscard()}).Handler())
	defer srv.Close()
	inspector := uuid.New()
	tok := keys.Inspector(inspector.String(), tn.String())
	appraiser := keys.Token(authtest.Claims{Subject: uuid.NewString(), Tenant: tn.String(), Roles: []string{"appraiser"}})

	call := func(method, path, token, key string, body any) (*http.Response, []byte) {
		var rd io.Reader
		if body != nil {
			b, _ := json.Marshal(body)
			rd = bytes.NewReader(b)
		}
		req, _ := http.NewRequest(method, srv.URL+path, rd)
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		if key != "" {
			req.Header.Set("Idempotency-Key", key)
		}
		req.Header.Set("Content-Type", "application/json")
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(res.Body)
		res.Body.Close()
		return res, b
	}
	check := func(file, path, method string, res *http.Response, body []byte) {
		t.Helper()
		if err := spec.Validate(file, path, method, res.StatusCode, res.Header.Get("Content-Type"), body); err != nil {
			t.Fatalf("%v\n%s", err, body)
		}
	}

	key := uuid.NewString()
	res, body := call("POST", "/api/v1/ledger/verify-runs", tok, key, map[string]any{"verifyKind": "TOAN_BO"})
	if res.StatusCode != http.StatusAccepted || res.Header.Get("Location") == "" {
		t.Fatalf("POST: %d %s", res.StatusCode, body)
	}
	check("modules/oversight.yaml", "/ledger/verify-runs", "post", res, body)
	var job struct{ ID string }
	_ = json.Unmarshal(body, &job)
	if !strings.HasPrefix(job.ID, "ledger-") || res.Header.Get("Location") != "/api/v1/jobs/"+job.ID {
		t.Fatalf("Location = %s", res.Header.Get("Location"))
	}
	again, body2 := call("POST", "/api/v1/ledger/verify-runs", tok, key, map[string]any{"verifyKind": "TOAN_BO"})
	var job2 struct{ ID string }
	_ = json.Unmarshal(body2, &job2)
	if again.StatusCode != http.StatusAccepted || job2.ID != job.ID {
		t.Fatalf("cùng Idempotency-Key phải trả lại tác vụ cũ: %d %s", again.StatusCode, body2)
	}
	res, body = call("POST", "/api/v1/ledger/verify-runs", tok, key, map[string]any{"verifyKind": "DAU_CHUOI"})
	if res.StatusCode != http.StatusUnprocessableEntity {
		t.Fatalf("khoá dùng lại với thân khác: %d %s", res.StatusCode, body)
	}
	check("modules/oversight.yaml", "/ledger/verify-runs", "post", res, body)
	e.svc.Wait()

	res, body = call("GET", "/api/v1/jobs/"+job.ID, tok, "", nil)
	check("modules/jobs.yaml", "/jobs/{id}", "get", res, body)
	if res.StatusCode != 200 || !bytes.Contains(body, []byte(`"HOAN_TAT"`)) {
		t.Fatalf("job: %d %s", res.StatusCode, body)
	}
	if res, _ := call("GET", "/api/v1/jobs/"+job.ID, appraiser, "", nil); res.StatusCode != 404 {
		t.Fatalf("người khác xem job phải 404: %d", res.StatusCode)
	}
	res, body = call("GET", "/api/v1/ledger/verify-runs/"+strings.TrimPrefix(job.ID, "ledger-"), appraiser, "", nil)
	check("modules/oversight.yaml", "/ledger/verify-runs/{id}", "get", res, body)
	if res.StatusCode != 200 {
		t.Fatalf("GET run: %d %s", res.StatusCode, body)
	}
	res, body = call("GET", "/api/v1/ledger/verify-runs?limit=1", tok, "", nil)
	check("modules/oversight.yaml", "/ledger/verify-runs", "get", res, body)
	if res.StatusCode != 200 {
		t.Fatalf("list: %d %s", res.StatusCode, body)
	}

	// 403 với vai trò không được phép; 401 khi thiếu token.
	res, body = call("POST", "/api/v1/ledger/verify-runs", appraiser, uuid.NewString(), map[string]any{"verifyKind": "TOAN_BO"})
	if res.StatusCode != http.StatusForbidden {
		t.Fatalf("appraiser chạy kiểm tra: %d", res.StatusCode)
	}
	check("modules/oversight.yaml", "/ledger/verify-runs", "post", res, body)
	member := keys.Token(authtest.Claims{Subject: uuid.NewString(), Tenant: tn.String(), Roles: []string{"member"}})
	if res, _ := call("GET", "/api/v1/ledger/verify-runs", member, "", nil); res.StatusCode != http.StatusForbidden {
		t.Fatalf("member xem kiểm tra: %d", res.StatusCode)
	}
	if res, _ := call("GET", "/api/v1/ledger/verify-runs", "", "", nil); res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("thiếu token: %d", res.StatusCode)
	}
	// Mọi lần gọi API kiểm tra được ghi vào sổ cái (sys.audit.v1).
	if n := e.count(t, `SELECT count(*) FROM ledger.ledger_entry WHERE tenant_id = $1 AND event_type = 'sys.audit.v1'`, tn); n < 3 {
		t.Fatalf("sự kiện kiểm toán = %d", n)
	}
}

// TestDoThoiGianKiemTra đo (chỉ ghi nhận, YC-PT-19) thời gian TOAN_BO trên 64 chuỗi với 8 luồng.
func TestDoThoiGianKiemTra(t *testing.T) {
	e := newEnv(t, "IT-VF-DO")
	tn := uuid.New()
	const n = 64000
	e.write(t, tn, n)
	start := time.Now()
	run, err := e.svc.RunNow(context.Background(), verify.Request{TenantID: tn, Kind: verify.KindFull})
	if err != nil || run.Mismatches != 0 {
		t.Fatal(err, run)
	}
	took := time.Since(start)
	t.Logf("kiểm %d bản ghi mất %v (%.0f bản ghi/giây, 8 luồng)", run.EntriesChecked, took, float64(run.EntriesChecked)/took.Seconds())
}

func slogDiscard() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }
