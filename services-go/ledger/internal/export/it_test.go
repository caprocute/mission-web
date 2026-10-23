package export_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/json"
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
	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/ledger/internal/auth"
	"mission/services-go/ledger/internal/auth/authtest"
	"mission/services-go/ledger/internal/bundle"
	"mission/services-go/ledger/internal/contract"
	"mission/services-go/ledger/internal/export"
	"mission/services-go/ledger/internal/ingest"
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

// write ghi n sự kiện; aggregates là các đối tượng được dùng lại xen kẽ để chuỗi có cả bản ghi trong và ngoài
// phạm vi lọc theo đối tượng.
func write(t *testing.T, ing *ingest.Ingester, tenant uuid.UUID, n int, aggregates []uuid.UUID) {
	t.Helper()
	byChain := map[int16][]ingest.EventInput{}
	for i := 0; i < n; i++ {
		id := uuid.Must(uuid.NewV7())
		h := sha256.Sum256(id[:])
		agg := uuid.New()
		if len(aggregates) > 0 && i%3 == 0 {
			agg = aggregates[i%len(aggregates)]
		}
		ev := ingest.EventInput{EventID: id, TenantID: tenant, EventType: "appraisal.profile.stepcompleted.v1", AggregateType: "APPRAISAL_PROFILE",
			AggregateID: agg, ActorKind: "NGUOI", ActorID: &agg, OccurredAt: time.Now().UTC(), PayloadHash: h[:], Classification: "C1",
			Metadata: map[string]any{"after_status": "DA_HOAN_THANH"}}
		byChain[ev.ChainPartition()] = append(byChain[ev.ChainPartition()], ev)
	}
	for p, evs := range byChain {
		if _, err := ing.AppendBatch(context.Background(), tenant, p, evs); err != nil {
			t.Fatal(err)
		}
	}
}

func readFile(t *testing.T, loc string) []byte {
	t.Helper()
	b, err := os.ReadFile(loc)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// tamper sửa một ký tự hex của payloadHash ở bản ghi thứ idx trong entries.json, giữ nguyên kê khai.
func tamper(t *testing.T, tarball []byte) ([]byte, int16, int64) {
	t.Helper()
	b, err := bundle.Read(bytes.NewReader(tarball))
	if err != nil {
		t.Fatal(err)
	}
	ch := b.Entries.Chains[0]
	rec := ch.Segments[0].Records[0]
	old := rec.PayloadHash
	repl := "0"
	if old[0] == '0' {
		repl = "1"
	}
	out := bytes.Replace(tarball, []byte(`"payloadHash":"`+old+`"`), []byte(`"payloadHash":"`+repl+old[1:]+`"`), 1)
	if bytes.Equal(out, tarball) {
		t.Fatal("không sửa được gói")
	}
	return out, ch.ChainPartition, rec.SeqNo
}

func TestXuatVaKiemChungDocLap(t *testing.T) {
	if db == nil {
		t.Skip("LEDGER_SKIP_IT")
	}
	ctx := context.Background()
	pool := testdb.Pool(ctx, t, db.SvcDSN)
	ing := ingest.NewIngester(pool, nil, nil)
	k, c, err := seal.SelfSigned("Ledger Seal IT-EX", false)
	if err != nil {
		t.Fatal(err)
	}
	tsa, err := seal.NewLocalTSA()
	if err != nil {
		t.Fatal(err)
	}
	ks := &seal.KeySigner{Key: k, Cert: c, KeyID: "k-ex"}
	sealer := &seal.Sealer{Pool: pool, Signer: ks, TSA: tsa, Cell: "IT-EX", Interval: time.Hour}
	tn := uuid.New()
	profiles := []uuid.UUID{uuid.New(), uuid.New()}
	// Ba cửa sổ niêm phong: đoạn chuỗi của một hồ sơ đi qua nhiều lần niêm phong.
	for w := 0; w < 3; w++ {
		write(t, ing, tn, 120, profiles)
		if _, err := sealer.SealWindow(ctx, time.Now().Add(time.Second), seal.KindPeriodic); err != nil {
			t.Fatal(err)
		}
		time.Sleep(1100 * time.Millisecond)
	}
	write(t, ing, tn, 9, profiles) // chưa niêm phong

	dir := t.TempDir()
	certs := map[[32]byte]*x509.Certificate{sha256.Sum256(c.Raw): c}
	svc := &export.Service{Pool: pool, Cell: "IT-EX", Signer: export.SignerOf(ks), Certs: certs,
		Store: export.DirStore{Dir: dir}, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}

	spec, err := contract.Load(filepath.Join(testdb.RepoRoot(), "contracts", "openapi"))
	if err != nil {
		t.Fatal(err)
	}
	keys := authtest.New()
	defer keys.Close()
	srv := httptest.NewServer(server.New(server.Deps{Pool: pool, Export: svc, Auditor: ing,
		Auth: auth.NewJWKSVerifier(auth.JWKSConfig{JWKSURL: keys.JWKSURL(), Issuers: []string{authtest.Issuer}}),
		Log:  slog.New(slog.NewTextHandler(io.Discard, nil))}).Handler())
	defer srv.Close()
	inspector := uuid.New()
	tok := keys.Inspector(inspector.String(), tn.String())
	appraiser := keys.Token(authtest.Claims{Subject: uuid.NewString(), Tenant: tn.String(), Roles: []string{"appraiser"}})

	call := func(method, path, token, key string, body any) (*http.Response, []byte) {
		t.Helper()
		var rd io.Reader
		if body != nil {
			raw, _ := json.Marshal(body)
			rd = bytes.NewReader(raw)
		}
		req, _ := http.NewRequest(method, srv.URL+path, rd)
		req.Header.Set("Authorization", "Bearer "+token)
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
	from, to := time.Now().Add(-time.Hour).UTC().Format(time.RFC3339), time.Now().Add(time.Minute).UTC().Format(time.RFC3339)

	export1 := func(body map[string]any) string {
		t.Helper()
		key := uuid.NewString()
		res, b := call("POST", "/api/v1/ledger/exports", tok, key, body)
		check("modules/oversight.yaml", "/ledger/exports", "post", res, b)
		if res.StatusCode != http.StatusAccepted || !strings.HasPrefix(res.Header.Get("Location"), "/api/v1/jobs/") {
			t.Fatalf("POST exports: %d %s", res.StatusCode, b)
		}
		again, b2 := call("POST", "/api/v1/ledger/exports", tok, key, body)
		if again.StatusCode != http.StatusAccepted || !bytes.Equal(b[:40], b2[:40]) {
			t.Fatalf("cùng khoá phải trả lại tác vụ cũ: %s / %s", b, b2)
		}
		svc.Wait()
		res, b = call("GET", res.Header.Get("Location"), tok, "", nil)
		check("modules/jobs.yaml", "/jobs/{id}", "get", res, b)
		var job struct{ Status, Message, ResultLocation string }
		_ = json.Unmarshal(b, &job)
		if job.Status != "HOAN_TAT" || job.Message != export.MsgReady || job.ResultLocation == "" {
			t.Fatalf("job xuất: %s", b)
		}
		return job.ResultLocation
	}

	// Xuất toàn bộ tenant: mọi chuỗi liền mạch, HỢP LỆ.
	full := readFile(t, export1(map[string]any{"from": from, "to": to}))
	b, err := bundle.Read(bytes.NewReader(full))
	if err != nil {
		t.Fatal(err)
	}
	rep := bundle.Verify(b, bundle.Options{})
	if !rep.OK() || b.Manifest.EntriesInScope != 360 || b.Manifest.UnsealedExcluded < 9 || len(b.Seals) != 3 {
		t.Fatalf("gói toàn bộ: %d trong phạm vi, %d chưa niêm phong, %d niêm phong\n%v\n%v", b.Manifest.EntriesInScope,
			b.Manifest.UnsealedExcluded, len(b.Seals), rep.Lines, rep.Errors)
	}

	// Xuất một hồ sơ: đoạn chuỗi từ mỗi bản ghi tới đầu chuỗi đã niêm phong, có bản ghi ngoài phạm vi để nối.
	one := readFile(t, export1(map[string]any{"from": from, "to": to, "aggregateType": "appraisal_profile",
		"aggregateId": profiles[0].String()}))
	b1, err := bundle.Read(bytes.NewReader(one))
	if err != nil {
		t.Fatal(err)
	}
	if rep := bundle.Verify(b1, bundle.Options{}); !rep.OK() {
		t.Fatalf("gói một hồ sơ: %v\n%v", rep.Lines, rep.Errors)
	}
	outside := 0
	for _, ch := range b1.Entries.Chains {
		for _, sg := range ch.Segments {
			for _, r := range sg.Records {
				if !r.InScope {
					outside++
					if r.Metadata != nil {
						t.Fatal("bản ghi ngoài phạm vi không được mang metadata")
					}
				}
			}
		}
	}
	if b1.Manifest.EntriesInScope == 0 || b1.Manifest.EntriesInScope >= 360 {
		t.Fatalf("gói một hồ sơ có %d bản ghi trong phạm vi (%d ngoài)", b1.Manifest.EntriesInScope, outside)
	}

	// Sửa một byte: báo đúng chuỗi và seq_no, và kê khai bị sửa.
	bad, p, seq := tamper(t, one)
	bb, err := bundle.Read(bytes.NewReader(bad))
	if err != nil {
		t.Fatal(err)
	}
	rep = bundle.Verify(bb, bundle.Options{})
	want := fmt.Sprintf("Chuỗi %d, seq_no %d: entry_hash tính lại không khớp", p, seq)
	joined := strings.Join(rep.Errors, "\n")
	if rep.OK() || !strings.Contains(joined, want) || !strings.Contains(joined, "entries.json đã bị sửa") {
		t.Fatalf("gói bị sửa phải chỉ ra %q:\n%s", want, joined)
	}

	// Bản neo của bên độc lập: khớp với gói.
	anchorer := &seal.Anchorer{Pool: pool, Cell: "IT-EX"}
	doc, err := anchorer.DailyAnchor(ctx, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if rep := bundle.Verify(b, bundle.Options{Anchors: []seal.AnchorDocument{*doc}}); !rep.OK() ||
		!strings.Contains(strings.Join(rep.Lines, "\n"), "Bản neo: 1/1 ngày khớp") {
		t.Fatalf("đối chiếu bản neo: %v %v", rep.Lines, rep.Errors)
	}
	forged := *doc
	forged.CellRoot = strings.Repeat("ab", 32)
	if rep := bundle.Verify(b, bundle.Options{Anchors: []seal.AnchorDocument{forged}}); rep.OK() {
		t.Fatal("bản neo khác gốc phải báo sai")
	}

	// Quyền và kiểm tra đầu vào.
	res, body := call("POST", "/api/v1/ledger/exports", appraiser, uuid.NewString(), map[string]any{"from": from, "to": to})
	check("modules/oversight.yaml", "/ledger/exports", "post", res, body)
	if res.StatusCode != http.StatusForbidden {
		t.Fatalf("appraiser xuất: %d", res.StatusCode)
	}
	res, body = call("POST", "/api/v1/ledger/exports", tok, uuid.NewString(),
		map[string]any{"from": "2023-01-01T00:00:00Z", "to": "2026-01-02T00:00:00Z"})
	check("modules/oversight.yaml", "/ledger/exports", "post", res, body)
	if res.StatusCode != http.StatusUnprocessableEntity || !bytes.Contains(body, []byte("LEDGER-422-RANGE-TOO-LONG")) {
		t.Fatalf("quá 2 năm: %d %s", res.StatusCode, body)
	}

	// Gói mẫu nhỏ cho kiểm thử ledger-verify ngoại tuyến (cmd/ledger-verify), sinh lại khi UPDATE_GOLDEN=1.
	if os.Getenv("UPDATE_GOLDEN") == "1" {
		golden := filepath.Join(testdb.RepoRoot(), "data/golden/ledger-canon/ledger-verify")
		if err := os.MkdirAll(golden, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(golden, "goi-mau.tar"), one, 0o644); err != nil {
			t.Fatal(err)
		}
		raw, _ := json.MarshalIndent([]seal.AnchorDocument{*doc}, "", "  ")
		if err := os.WriteFile(filepath.Join(golden, "ban-neo.json"), raw, 0o644); err != nil {
			t.Fatal(err)
		}
	}
}
