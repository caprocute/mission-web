package lookup_test

import (
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/ledger/internal/auth"
	"mission/services-go/ledger/internal/auth/authtest"
	"mission/services-go/ledger/internal/contract"
	"mission/services-go/ledger/internal/ingest"
	"mission/services-go/ledger/internal/lookup"
	"mission/services-go/ledger/internal/seal"
	"mission/services-go/ledger/internal/server"
	"mission/services-go/ledger/internal/testdb"
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

const evType = "task.task.progressed.v1"

func write(t *testing.T, ing *ingest.Ingester, tenant uuid.UUID, n int) []ingest.EventInput {
	t.Helper()
	byChain := map[int16][]ingest.EventInput{}
	var all []ingest.EventInput
	for i := 0; i < n; i++ {
		id := uuid.Must(uuid.NewV7())
		h := sha256.Sum256(id[:])
		ev := ingest.EventInput{EventID: id, TenantID: tenant, EventType: evType, AggregateType: "TASK",
			AggregateID: uuid.New(), ActorKind: "DICH_VU", OccurredAt: time.Now().UTC(), PayloadHash: h[:], Classification: "C1",
			Metadata: map[string]any{"after_status": "DANG_THUC_HIEN"}}
		byChain[ev.ChainPartition()] = append(byChain[ev.ChainPartition()], ev)
		all = append(all, ev)
	}
	for p, evs := range byChain {
		if _, err := ing.AppendBatch(context.Background(), tenant, p, evs); err != nil {
			t.Fatal(err)
		}
	}
	return all
}

func TestTraCuuKhopHopDong(t *testing.T) {
	if db == nil {
		t.Skip("LEDGER_SKIP_IT")
	}
	ctx := context.Background()
	pool := testdb.Pool(ctx, t, db.SvcDSN)
	admin := testdb.Pool(ctx, t, db.AdminDSN)
	ing := ingest.NewIngester(pool, nil, nil)
	k, c, err := seal.SelfSigned("Khoá niêm phong IT-LK", false)
	if err != nil {
		t.Fatal(err)
	}
	tsa, err := seal.NewLocalTSA()
	if err != nil {
		t.Fatal(err)
	}
	sealer := &seal.Sealer{Pool: pool, Signer: &seal.KeySigner{Key: k, Cert: c, KeyID: "k-lk"}, TSA: tsa, Cell: "IT-LK", Interval: time.Hour}
	svc := &lookup.Service{Pool: pool, Certs: map[[32]byte]*x509.Certificate{sha256.Sum256(c.Raw): c}}

	tn := uuid.New()
	sealed := write(t, ing, tn, 150)
	res, err := sealer.SealWindow(ctx, time.Now().Add(time.Second), seal.KindPeriodic)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := admin.Exec(ctx, `INSERT INTO ledger.anchor_receipt (id, cell_seal_id, anchor_target, receipt, anchored_at)
		VALUES ($1, $2, 'OBJECT_LOCK', '\x0102', now())`, uuid.New(), res.CellSealID); err != nil {
		t.Fatal(err)
	}
	time.Sleep(10 * time.Millisecond)
	pending := write(t, ing, tn, 3)

	// Bằng chứng bao hàm của mọi sự kiện đã niêm phong phải tự kiểm được tới gốc cell.
	for _, ev := range sealed {
		p, err := svc.Proof(ctx, tn, ev.EventID)
		if err != nil {
			t.Fatal(err)
		}
		if msg := p.Check(); msg != "" {
			t.Fatalf("bằng chứng của %s sai: %s", ev.EventID, msg)
		}
		if p.Seal.SignatureValid == nil || !*p.Seal.SignatureValid {
			t.Fatal("chữ ký gốc cell phải hợp lệ")
		}
		if _, err := seal.ParseToken(p.Seal.TSAToken, [32]byte(p.Seal.CellRoot)); err != nil {
			t.Fatalf("token TSA: %v", err)
		}
	}

	spec, err := contract.Load(filepath.Join(testdb.RepoRoot(), "contracts", "openapi"))
	if err != nil {
		t.Fatal(err)
	}
	keys := authtest.New()
	defer keys.Close()
	srv := httptest.NewServer(server.New(server.Deps{Pool: pool, Lookup: svc, Auditor: ing,
		Auth: auth.NewJWKSVerifier(auth.JWKSConfig{JWKSURL: keys.JWKSURL(), Issuers: []string{authtest.Issuer}}),
		Log:  slog.New(slog.NewTextHandler(io.Discard, nil))}).Handler())
	defer srv.Close()
	inspector := keys.Inspector(uuid.NewString(), tn.String())
	appraiser := keys.Token(authtest.Claims{Subject: uuid.NewString(), Tenant: tn.String(), Roles: []string{"appraiser"}})
	member := keys.Token(authtest.Claims{Subject: uuid.NewString(), Tenant: tn.String(), Roles: []string{"member"}})

	get := func(path, token string) (*http.Response, []byte) {
		t.Helper()
		req, _ := http.NewRequest("GET", srv.URL+path, nil)
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		r, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(r.Body)
		r.Body.Close()
		return r, b
	}
	check := func(path string, r *http.Response, b []byte, want int) {
		t.Helper()
		if r.StatusCode != want {
			t.Fatalf("%s: %d, muốn %d: %s", path, r.StatusCode, want, b)
		}
		if err := spec.Validate("modules/oversight.yaml", path, "get", r.StatusCode, r.Header.Get("Content-Type"), b); err != nil {
			t.Fatalf("%v\n%s", err, b)
		}
	}

	// Phân trang con trỏ phủ đủ, không trùng.
	seen := map[string]bool{}
	cursor := ""
	for page := 0; page < 10; page++ {
		q := url.Values{"eventType": {evType}, "limit": {"40"}}
		if cursor != "" {
			q.Set("cursor", cursor)
		}
		r, b := get("/api/v1/ledger/entries?"+q.Encode(), appraiser)
		check("/ledger/entries", r, b, 200)
		var pg struct {
			Items []struct {
				EventID string `json:"eventId"`
			}
			NextCursor *string
		}
		_ = json.Unmarshal(b, &pg)
		for _, it := range pg.Items {
			if seen[it.EventID] {
				t.Fatalf("trùng %s giữa các trang", it.EventID)
			}
			seen[it.EventID] = true
		}
		if pg.NextCursor == nil {
			break
		}
		cursor = *pg.NextCursor
	}
	if len(seen) != len(sealed)+len(pending) {
		t.Fatalf("phân trang trả %d bản ghi, muốn %d", len(seen), len(sealed)+len(pending))
	}
	r, b := get("/api/v1/ledger/entries?aggregateType=task&aggregateId="+sealed[0].AggregateID.String(), inspector)
	check("/ledger/entries", r, b, 200)
	var one struct{ Items []map[string]any }
	_ = json.Unmarshal(b, &one)
	if len(one.Items) != 1 {
		t.Fatalf("lọc theo đối tượng trả %d", len(one.Items))
	}
	entryID := one.Items[0]["id"].(string)

	r, b = get("/api/v1/ledger/entries?from=2023-01-01T00:00:00Z&to=2026-01-02T00:00:00Z", inspector)
	check("/ledger/entries", r, b, 422)
	r, b = get("/api/v1/ledger/entries", member)
	check("/ledger/entries", r, b, 403)
	if r, _ := get("/api/v1/ledger/entries", ""); r.StatusCode != 401 {
		t.Fatalf("thiếu token: %d", r.StatusCode)
	}

	r, b = get("/api/v1/ledger/entries/"+entryID, appraiser)
	check("/ledger/entries/{id}", r, b, 200)
	var detail struct {
		Seal    map[string]any
		Anchors []map[string]any
	}
	_ = json.Unmarshal(b, &detail)
	if detail.Seal == nil || detail.Seal["signatureValid"] != true || len(detail.Anchors) != 1 {
		t.Fatalf("chi tiết thiếu niêm phong hoặc bản neo: %s", b)
	}
	r, b = get("/api/v1/ledger/entries?eventType="+evType+"&limit=1", inspector)
	_ = json.Unmarshal(b, &one)
	r, b = get("/api/v1/ledger/entries/"+one.Items[0]["id"].(string), inspector)
	check("/ledger/entries/{id}", r, b, 200)
	if err := json.Unmarshal(b, &detail); err != nil || detail.Seal != nil {
		t.Fatalf("bản ghi mới nhất chưa niêm phong phải có seal null: %s", b)
	}
	r, b = get("/api/v1/ledger/entries/"+uuid.NewString(), inspector)
	check("/ledger/entries/{id}", r, b, 404)

	r, b = get("/api/v1/oversight/ledger/proofs?event="+sealed[7].EventID.String(), inspector)
	check("/oversight/ledger/proofs", r, b, 200)
	r, b = get("/api/v1/oversight/ledger/proofs?event="+pending[0].EventID.String(), inspector)
	check("/oversight/ledger/proofs", r, b, 409)
	r, b = get("/api/v1/oversight/ledger/proofs?event="+sealed[7].EventID.String(), appraiser)
	check("/oversight/ledger/proofs", r, b, 403)
	r, b = get("/api/v1/oversight/ledger/proofs?event="+uuid.NewString(), inspector)
	check("/oversight/ledger/proofs", r, b, 404)

	// Mọi lần tra cứu, xem được ghi vào sổ cái.
	var audits int
	if err := admin.QueryRow(ctx, `SELECT count(*) FROM ledger.ledger_entry WHERE tenant_id = $1 AND event_type = 'sys.audit.v1'`,
		tn).Scan(&audits); err != nil || audits < 8 {
		t.Fatalf("sự kiện kiểm toán = %d (%v)", audits, err)
	}
}
