package move_test

import (
	"bytes"
	"context"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/api"
	"mission/services-go/tenant-registry/internal/contract"
	"mission/services-go/tenant-registry/internal/move"
	"mission/services-go/tenant-registry/internal/platform/auth"
	"mission/services-go/tenant-registry/internal/platform/auth/authtest"
	"mission/services-go/tenant-registry/internal/platform/crypto"
	"mission/services-go/tenant-registry/internal/resolve"
	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
	"mission/services-go/tenant-registry/internal/testdb"
	"mission/services-go/tenant-registry/pkg/routecookie"
)

const platformYAML = "modules/platform.yaml"

var (
	admin1  = "0192f1a2-aaaa-7000-8000-000000000001"
	admin2  = "0192f1a2-bbbb-7000-8000-000000000002"
	srcCell = uuid.MustParse("0192f1a2-0000-7c00-8a00-00000000c0c1") // CELL-SH (seed, pool)
)

// ---------------------------------------------------------------- hệ giả: kho S3 và ledger-service

type fakeS3 struct {
	srv     *httptest.Server
	mu      sync.Mutex
	objects map[string][]byte // bucket/key
	ak, sk  string
	badSig  int
}

func newS3(t *testing.T) *fakeS3 {
	f := &fakeS3{objects: map[string][]byte{}, ak: "AKTEST", sk: testdb.RandomSecret()}
	f.srv = httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(f.srv.Close)
	return f
}

// serve kiểm chữ ký SigV4 (ký lại yêu cầu nhận được bằng cùng khoá) rồi xử lý ListObjectsV2, Get, Put, Delete.
func (f *fakeS3) serve(w http.ResponseWriter, r *http.Request) {
	when, _ := time.Parse("20060102T150405Z", r.Header.Get("X-Amz-Date"))
	chk, _ := http.NewRequest(r.Method, "http://"+r.Host+r.URL.RequestURI(), nil)
	if ct := r.Header.Get("Content-Type"); ct != "" {
		chk.Header.Set("Content-Type", ct)
	}
	move.SignV4(chk, f.ak, f.sk, "default", "s3", when)
	if chk.Header.Get("Authorization") != r.Header.Get("Authorization") {
		f.mu.Lock()
		f.badSig++
		f.mu.Unlock()
		w.WriteHeader(403)
		return
	}
	parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/"), "/", 2)
	bucket, key := parts[0], ""
	if len(parts) == 2 {
		key = parts[1]
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	switch {
	case r.Method == http.MethodGet && key == "":
		pfx := r.URL.Query().Get("prefix")
		type obj struct {
			Key  string `xml:"Key"`
			ETag string `xml:"ETag"`
			Size int64  `xml:"Size"`
		}
		var res struct {
			XMLName     xml.Name `xml:"ListBucketResult"`
			Contents    []obj    `xml:"Contents"`
			IsTruncated bool     `xml:"IsTruncated"`
		}
		var keys []string
		for k := range f.objects {
			if strings.HasPrefix(k, bucket+"/"+pfx) {
				keys = append(keys, k)
			}
		}
		sort.Strings(keys)
		for _, k := range keys {
			b := f.objects[k]
			res.Contents = append(res.Contents, obj{Key: strings.TrimPrefix(k, bucket+"/"), ETag: fmt.Sprintf(`"%x"`, len(b)*7919+int(b[0])), Size: int64(len(b))})
		}
		_ = xml.NewEncoder(w).Encode(res)
	case r.Method == http.MethodGet:
		b, ok := f.objects[bucket+"/"+key]
		if !ok {
			w.WriteHeader(404)
			return
		}
		_, _ = w.Write(b)
	case r.Method == http.MethodPut:
		b, _ := io.ReadAll(r.Body)
		f.objects[bucket+"/"+key] = b
	case r.Method == http.MethodDelete:
		delete(f.objects, bucket+"/"+key)
		w.WriteHeader(204)
	default:
		w.WriteHeader(405)
	}
}

func (f *fakeS3) put(bucket, key, body string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.objects[bucket+"/"+key] = []byte(body)
}

func (f *fakeS3) count(prefix string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for k := range f.objects {
		if strings.Contains(k, prefix) {
			n++
		}
	}
	return n
}

type fakeLedger struct {
	mu      sync.Mutex
	sealed  map[string]bool
	heads   map[string][]move.ChainHead
	unseals int
}

func (l *fakeLedger) Seal(_ context.Context, cell string, tid, _ uuid.UUID, _ string) (*move.Handover, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.sealed[cell+tid.String()] = true
	h := &move.Handover{SealID: "seal-" + tid.String()[:8]}
	for p := range 64 {
		h.Heads = append(h.Heads, move.ChainHead{Partition: p, LastSeqNo: int64(p * 3), LastHash: strings.Repeat(fmt.Sprintf("%02x", p), 32)})
	}
	return h, nil
}

func (l *fakeLedger) Accept(_ context.Context, cell string, tid, _ uuid.UUID, _ string, h *move.Handover) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.heads[cell+tid.String()] = h.Heads
	return nil
}

func (l *fakeLedger) Unseal(_ context.Context, cell string, tid, _ uuid.UUID) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.sealed, cell+tid.String())
	l.unseals++
	return nil
}

// ---------------------------------------------------------------- môi trường

type env struct {
	h      http.Handler
	pool   *pgxpool.Pool
	adm    *pgxpool.Pool
	srcDB  *pgxpool.Pool
	dstDB  *pgxpool.Pool
	runner *move.Runner
	keys   *authtest.Keys
	specs  *contract.Spec
	s3src  *fakeS3
	s3dst  *fakeS3
	ledger *fakeLedger
	target store.Cell
}

func newEnv(t *testing.T) *env {
	t.Helper()
	ctx := context.Background()
	e := &env{keys: authtest.New(), ledger: &fakeLedger{sealed: map[string]bool{}, heads: map[string][]move.ChainHead{}}}
	t.Cleanup(e.keys.Close)
	var err error
	if e.specs, err = contract.Load(filepath.Join(testdb.RepoRoot(), "contracts", "openapi")); err != nil {
		t.Fatal(err)
	}
	e.pool = testdb.Pool(ctx, t, reg.SvcDSN)
	e.adm = testdb.Pool(ctx, t, reg.AdminDSN)
	e.srcDB = testdb.Pool(ctx, t, src.adminURL)
	e.dstDB = testdb.Pool(ctx, t, dst.adminURL)
	e.s3src, e.s3dst = newS3(t), newS3(t)
	// Cell đích pool thứ hai cho kiểm thử (mỗi kiểm thử một cell để tải không ảnh hưởng lẫn nhau).
	code := "CELL-T" + strings.ToUpper(uuid.NewString()[:4])
	e.target = store.Cell{ID: store.NewID(), Code: code, Name: "Cell kiểm thử " + code, PrimaryDC: "PHIANAM", TenancyModel: "POOL",
		APIBaseURL: "https://api." + strings.ToLower(code) + ".okrkpi.vn", CapacityAccounts: 1000000, Status: "HOAT_DONG"}
	if err := store.InsertCell(ctx, e.adm, &e.target); err != nil {
		t.Fatal(err)
	}
	enc, _ := crypto.NewAESGCM([]byte(testdb.RandomSecret()[:32]))
	tenants := tenant.NewService(tenant.DefaultRules(), enc)
	cells := &move.PGCells{Cells: map[string]move.CellDBConfig{
		"CELL-SH": {AdminURL: src.adminURL, PeerConnInfo: src.peer()},
		code:      {AdminURL: dst.adminURL, PeerConnInfo: dst.peer()},
	}}
	t.Cleanup(cells.Close)
	objects := &move.S3Sync{Buckets: move.DefaultBuckets(), Cells: map[string]move.S3Endpoint{
		"CELL-SH": {URL: e.s3src.srv.URL, AccessKey: e.s3src.ak, SecretKey: e.s3src.sk},
		code:      {URL: e.s3dst.srv.URL, AccessKey: e.s3dst.ak, SecretKey: e.s3dst.sk},
	}}
	log := slog.New(slog.NewJSONHandler(io.Discard, nil))
	if os.Getenv("TEST_LOG") != "" {
		log = slog.New(slog.NewJSONHandler(os.Stderr, nil))
	}
	e.runner = &move.Runner{Pool: e.pool, Tenants: tenants, DB: cells, Objects: objects, Ledger: e.ledger, Log: log,
		PollEvery: 100 * time.Millisecond, CatchUpTimeout: 2 * time.Minute, FreezeTimeout: time.Minute, MaxAttempts: 2}
	signer, _ := routecookie.NewSigner(bytes.Repeat([]byte{9}, 32))
	srv := api.New(api.Deps{Pool: e.pool, Log: log, Tenants: tenants, Cookies: signer,
		Verifier: auth.NewJWKSVerifier(auth.JWKSConfig{JWKSURL: e.keys.JWKSURL(), Issuers: []string{authtest.Issuer}}),
		Resolver: resolve.New(&resolve.DBLoader{Pool: e.pool}, resolve.Options{TTL: time.Millisecond}),
		Moves:    &move.Service{Tenants: tenants}, MoveRunner: e.runner})
	e.h = srv.Handler()
	return e
}

func (e *env) admin(sub string) string { return e.keys.PlatformAdmin(sub) }

func (e *env) do(t *testing.T, method, target, tok string, body any, hdr map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req := httptest.NewRequest(method, target, rd)
	req.Header.Set("Authorization", "Bearer "+tok)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if method != http.MethodGet {
		req.Header.Set("Idempotency-Key", uuid.NewString())
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	e.h.ServeHTTP(rec, req)
	return rec
}

func (e *env) conform(t *testing.T, path, method string, rec *httptest.ResponseRecorder) {
	t.Helper()
	if err := e.specs.Validate(platformYAML, path, method, rec.Code, rec.Header().Get("Content-Type"), rec.Body.Bytes()); err != nil {
		t.Errorf("lệch hợp đồng %s %s: %v\n%s", method, path, err, rec.Body.String())
	}
}

func problemCode(t *testing.T, rec *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	var p struct {
		Code string `json:"code"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &p)
	if rec.Code != status || (code != "" && p.Code != code) {
		t.Fatalf("muốn %d %s, nhận %d %s", status, code, rec.Code, rec.Body.String())
	}
}

// seedTenant tạo tenant pool đang vận hành trên CELL-SH và dữ liệu mẫu ở cụm nguồn.
func (e *env) seedTenant(t *testing.T, objectives, tasks int) (*store.Tenant, uuid.UUID) {
	t.Helper()
	ctx := context.Background()
	id := store.NewID()
	code := "mv_" + strings.ReplaceAll(id.String()[24:], "-", "")
	exp := 2000
	tn := &store.Tenant{ID: id, Code: code, Name: "Xã " + code, Sector: "CHINH_QUYEN", TenancyModel: "POOL", Status: tenant.DangVanHanh,
		MaxAccounts: 3000, CreatedBy: store.SystemActor, Flows: []string{"NHA_NUOC"}, ExpectedAccounts: &exp, KekRef: "transit/keys/cell-cell-sh"}
	if err := store.InsertTenant(ctx, e.adm, tn); err != nil {
		t.Fatal(err)
	}
	if err := store.InsertTenantCell(ctx, e.adm, &store.TenantCell{TenantID: id, CellID: srcCell, DatabaseName: "td_pool",
		ValidFrom: time.Now().Add(-24 * time.Hour), TenancyModel: "pool", DataRegion: "HOALAC"}, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	e.seedRows(t, e.srcDB, id, objectives, tasks)
	return tn, id
}

func (e *env) seedRows(t *testing.T, db *pgxpool.Pool, id uuid.UUID, objectives, tasks int) {
	t.Helper()
	ctx := context.Background()
	part := "task.task_progress_" + strings.ReplaceAll(id.String(), "-", "")[20:]
	if _, err := db.Exec(ctx, fmt.Sprintf(`CREATE TABLE %s PARTITION OF task.task_progress FOR VALUES IN ('%s')`, part, id)); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(ctx, `INSERT INTO okr.objective (id, tenant_id, title, progress, tags)
		SELECT gen_random_uuid(), $1, 'Mục tiêu số ' || g || ' – nâng cao chất lượng', (g % 100)::numeric, ARRAY['quý ' || (g % 4 + 1)]
		FROM generate_series(1, $2) g`, id, objectives); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(ctx, `INSERT INTO task.task_progress (tenant_id, id, note, amount)
		SELECT $1, gen_random_uuid(), 'Báo cáo tiến độ ' || g, g * 1.25 FROM generate_series(1, $2) g`, id, tasks); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(ctx, `INSERT INTO okr.outbox_event SELECT gen_random_uuid(), $1, 'sự kiện ' || g FROM generate_series(1, 5) g`, id); err != nil {
		t.Fatal(err)
	}
}

type counts struct{ obj, task, outbox int }

func countRows(t *testing.T, db *pgxpool.Pool, id uuid.UUID) counts {
	t.Helper()
	var c counts
	err := db.QueryRow(context.Background(), `SELECT (SELECT count(*) FROM okr.objective WHERE tenant_id = $1),
		(SELECT count(*) FROM task.task_progress WHERE tenant_id = $1), (SELECT count(*) FROM okr.outbox_event WHERE tenant_id = $1)`, id).
		Scan(&c.obj, &c.task, &c.outbox)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

// rowHash là băm toàn bộ dòng của tenant tính độc lập với mã (sắp theo id).
func rowHash(t *testing.T, db *pgxpool.Pool, id uuid.UUID) string {
	t.Helper()
	var h string
	err := db.QueryRow(context.Background(), `SELECT md5(coalesce((SELECT string_agg(x::text, '|' ORDER BY x.id) FROM okr.objective x WHERE tenant_id = $1), '') ||
		coalesce((SELECT string_agg(y::text, '|' ORDER BY y.id) FROM task.task_progress y WHERE tenant_id = $1), ''))`, id).Scan(&h)
	if err != nil {
		t.Fatal(err)
	}
	return h
}

type moveView struct {
	ID          uuid.UUID `json:"id"`
	Status      string    `json:"status"`
	RowVersion  int       `json:"rowVersion"`
	CurrentStep *string   `json:"currentStep"`
}

func decodeMove(t *testing.T, rec *httptest.ResponseRecorder) moveView {
	t.Helper()
	var v moveView
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("%v: %s", err, rec.Body.String())
	}
	return v
}

func (e *env) state(t *testing.T, moveID uuid.UUID) move.State {
	t.Helper()
	j, err := store.GetJob(context.Background(), e.pool, moveID)
	if err != nil {
		t.Fatal(err)
	}
	var st move.State
	if err := json.Unmarshal(j.Params, &st); err != nil {
		t.Fatal(err)
	}
	return st
}

func isReadOnly(err error) bool {
	var pe *pgconn.PgError
	return errors.As(err, &pe) && pe.Code == "25006"
}

// ---------------------------------------------------------------- kiểm thử

// TestMoveTenantBetweenCells: chuyển một tenant pool có dữ liệu mẫu và có ghi liên tục từ cụm nguồn sang cụm
// đích; so khớp số dòng và tổng băm, ghi lại thời gian chỉ đọc; tenant khác cùng cụm không bị ảnh hưởng;
// bốn mắt; sổ cái nối tiếp; client gọi cell cũ nhận 421; dọn nguồn sau thời hạn giữ.
func TestMoveTenantBetweenCells(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	tn, a := e.seedTenant(t, 3000, 800)
	_, b := e.seedTenant(t, 400, 120) // tenant khác ở cụm nguồn
	other := store.NewID()            // tenant đã có ở cụm đích
	e.seedRows(t, e.dstDB, other, 150, 40)
	beforeB, beforeOther := countRows(t, e.srcDB, b), countRows(t, e.dstDB, other)
	hashB, hashOther := rowHash(t, e.srcDB, b), rowHash(t, e.dstDB, other)
	for i := range 3 {
		e.s3src.put("dossier", fmt.Sprintf("t/%s/sha256/ab/%064d", a, i), "hồ sơ "+fmt.Sprint(i))
	}
	e.s3src.put("dossier", fmt.Sprintf("t/%s/sha256/cd/%064d", b, 9), "hồ sơ tenant khác")
	e.s3src.put("templates", fmt.Sprintf("t/%s/mau/%s", a, "bao-cao.docx"), "mẫu báo cáo riêng")
	e.s3src.put("evidence", fmt.Sprintf("t/%s/sha256/ef/%064d", a, 1), "minh chứng mã hoá")
	e.s3dst.put("evidence", fmt.Sprintf("t/%s/sha256/ef/%064d", a, 1), "minh chứng mã hoá") // đồng bộ multisite của Ceph

	// Đề nghị (người thứ nhất): 201, If-Match là phiên bản tenant.
	body := map[string]any{"targetCellId": e.target.ID, "targetTenancyModel": "POOL", "reason": "Cell nguồn vượt ngưỡng 1,5 triệu tài khoản (ADR-29)"}
	rec := e.do(t, http.MethodPost, "/api/v1/registry/tenants/"+a.String()+"/move", e.admin(admin1), body, map[string]string{"If-Match": fmt.Sprintf(`"%d"`, tn.RowVersion)})
	e.conform(t, "/registry/tenants/{id}/move", "post", rec)
	if rec.Code != 201 {
		t.Fatalf("đề nghị: %d %s", rec.Code, rec.Body.String())
	}
	mv := decodeMove(t, rec)
	if mv.Status != move.ChoDuyet || !strings.HasSuffix(rec.Header().Get("Location"), "/move/"+mv.ID.String()) {
		t.Fatalf("đề nghị: %+v", mv)
	}
	// Đề nghị lần hai khi còn lệnh đang chờ: 409; cell đích trùng cell hiện tại: 409.
	problemCode(t, e.do(t, http.MethodPost, "/api/v1/registry/tenants/"+a.String()+"/move", e.admin(admin1), body,
		map[string]string{"If-Match": fmt.Sprintf(`"%d"`, tn.RowVersion)}), 409, "REGISTRY-409-MOVE-IN-PROGRESS")
	_, c := e.seedTenant(t, 1, 1)
	problemCode(t, e.do(t, http.MethodPost, "/api/v1/registry/tenants/"+c.String()+"/move", e.admin(admin1),
		map[string]any{"targetCellId": srcCell, "reason": "thử"}, map[string]string{"If-Match": `"0"`}), 409, "REGISTRY-409-SAME-CELL")

	approve := "/api/v1/registry/tenants/" + a.String() + "/move/" + mv.ID.String() + "/approve"
	ifm := map[string]string{"If-Match": fmt.Sprintf(`"%d"`, mv.RowVersion)}
	// Bốn mắt: người đề nghị tự duyệt bị chặn; người thứ hai chưa bước lên AAL2 bị 401.
	rec = e.do(t, http.MethodPost, approve, e.admin(admin1), nil, ifm)
	e.conform(t, "/registry/tenants/{id}/move/{moveId}/approve", "post", rec)
	problemCode(t, rec, 403, "REGISTRY-403-SAME-APPROVER")
	aal1 := e.keys.Token(authtest.Claims{Subject: admin2, Roles: []string{"platform_admin"}, ACR: "aal1", AuthTime: time.Now()})
	problemCode(t, e.do(t, http.MethodPost, approve, aal1, nil, ifm), 401, "COMMON-401-STEP-UP")

	// Ghi liên tục trong lúc sao chép: tenant chuyển (tới khi bị khoá ghi) và tenant khác (không bao giờ bị chặn).
	stop := make(chan struct{})
	var wg sync.WaitGroup
	var aWrites, aBlocked, bWrites, bFailed atomic.Int64
	writer := func(id uuid.UUID, ok, failed *atomic.Int64, blocked *atomic.Int64) {
		defer wg.Done()
		for i := 0; ; i++ {
			select {
			case <-stop:
				return
			default:
			}
			_, err := e.srcDB.Exec(ctx, `WITH u AS (UPDATE okr.objective SET progress = (progress + 1) % 100, updated_at = now()
				  WHERE id = (SELECT id FROM okr.objective WHERE tenant_id = $1 LIMIT 1))
				INSERT INTO okr.objective (id, tenant_id, title) VALUES (gen_random_uuid(), $1, 'Ghi trong lúc chuyển ' || $2::int)`, id, i)
			switch {
			case err == nil:
				ok.Add(1)
			case blocked != nil && isReadOnly(err):
				blocked.Add(1)
			default:
				failed.Add(1)
			}
			time.Sleep(15 * time.Millisecond)
		}
	}
	var aFailed atomic.Int64
	wg.Add(2)
	go writer(a, &aWrites, &aFailed, &aBlocked)
	go writer(b, &bWrites, &bFailed, nil)

	start := time.Now()
	rec = e.do(t, http.MethodPost, approve, e.admin(admin2), map[string]string{"comment": "Đồng ý"}, ifm)
	e.conform(t, "/registry/tenants/{id}/move/{moveId}/approve", "post", rec)
	if rec.Code != 202 || rec.Header().Get("Location") != "/api/v1/jobs/"+mv.ID.String() {
		t.Fatalf("duyệt: %d %s", rec.Code, rec.Body.String())
	}
	if x, _ := store.GetTenant(ctx, e.pool, a); x.Status != tenant.DangChuyenCell {
		t.Fatalf("sau duyệt phải DANG_CHUYEN_CELL: %s", x.Status)
	}
	e.runner.Wait()
	close(stop)
	wg.Wait()
	elapsed := time.Since(start)

	rec = e.do(t, http.MethodGet, "/api/v1/registry/tenants/"+a.String()+"/move/"+mv.ID.String(), e.admin(admin1), nil, nil)
	e.conform(t, "/registry/tenants/{id}/move/{moveId}", "get", rec)
	if v := decodeMove(t, rec); v.Status != move.HoanTat || v.CurrentStep != nil {
		t.Fatalf("lệnh chuyển: %s", rec.Body.String())
	}
	st := e.state(t, mv.ID)
	t.Logf("tổng thời gian %s; thời gian chỉ đọc %.2f giây; ghi tenant chuyển: %d thành công, %d bị chặn khi khoá; tenant khác: %d ghi, %d lỗi",
		elapsed, st.ReadOnlySeconds, aWrites.Load(), aBlocked.Load(), bWrites.Load(), bFailed.Load())
	for _, v := range st.Verification {
		t.Logf("so khớp %-20s nguồn %6d dòng tổng %s | đích %6d dòng tổng %s | %v", v.Table, v.SourceRows, v.SourceSum, v.TargetRows, v.TargetSum, v.OK)
		if !v.OK || v.SourceRows != v.TargetRows {
			t.Fatalf("lệch ở %s", v.Table)
		}
	}
	if len(st.Verification) != 2 {
		t.Fatalf("phải so khớp 2 bảng (không sao chép outbox, bảng không có tenant_id): %+v", st.Verification)
	}
	if aFailed.Load() != 0 || bFailed.Load() != 0 || aWrites.Load() == 0 || bWrites.Load() == 0 {
		t.Fatalf("ghi lỗi ngoài dự kiến: a=%d b=%d", aFailed.Load(), bFailed.Load())
	}
	if st.ReadOnlySeconds <= 0 || st.ReadOnlySeconds > 15*60 {
		t.Fatalf("thời gian chỉ đọc bất thường: %f", st.ReadOnlySeconds)
	}

	// Không mất dòng nào: đích = nguồn (kể cả các dòng ghi trong lúc sao chép), outbox không sao chép.
	ca, cd := countRows(t, e.srcDB, a), countRows(t, e.dstDB, a)
	if ca.obj != cd.obj || ca.task != cd.task || cd.outbox != 0 || ca.obj != 3000+int(aWrites.Load()) {
		t.Fatalf("số dòng: nguồn %+v đích %+v (ghi thêm %d)", ca, cd, aWrites.Load())
	}
	if rowHash(t, e.srcDB, a) != rowHash(t, e.dstDB, a) {
		t.Fatal("băm toàn bộ dòng lệch giữa hai cụm")
	}
	// Tenant khác không bị ảnh hưởng.
	if countRows(t, e.dstDB, b).obj != 0 || countRows(t, e.dstDB, other) != beforeOther || rowHash(t, e.dstDB, other) != hashOther {
		t.Fatal("tenant khác ở cụm đích bị ảnh hưởng hoặc dữ liệu tenant khác bị chép sang")
	}
	if cb := countRows(t, e.srcDB, b); cb.obj != beforeB.obj+int(bWrites.Load()) || cb.task != beforeB.task {
		t.Fatalf("tenant khác ở nguồn: %+v trước %+v ghi %d", cb, beforeB, bWrites.Load())
	}
	_ = hashB
	// Sau cắt chuyển: ghi vào nguồn bị chặn (giữ 30 ngày), ghi vào đích được; tenant khác ở nguồn vẫn ghi được.
	if _, err := e.srcDB.Exec(ctx, `INSERT INTO okr.objective (id, tenant_id, title) VALUES (gen_random_uuid(), $1, 'ghi lạc')`, a); !isReadOnly(err) {
		t.Fatalf("ghi vào cell cũ phải bị chặn: %v", err)
	}
	if _, err := e.dstDB.Exec(ctx, `INSERT INTO okr.objective (id, tenant_id, title) VALUES (gen_random_uuid(), $1, 'ghi ở cell mới')`, a); err != nil {
		t.Fatal(err)
	}
	if _, err := e.srcDB.Exec(ctx, `UPDATE okr.objective SET title = title WHERE tenant_id = $1`, b); err != nil {
		t.Fatal(err)
	}
	// Không còn publication, subscription, slot; replica identity của nguồn đã khôi phục.
	var pubs, subs, slots int
	_ = e.srcDB.QueryRow(ctx, `SELECT (SELECT count(*) FROM pg_publication), (SELECT count(*) FROM pg_replication_slots)`).Scan(&pubs, &slots)
	_ = e.dstDB.QueryRow(ctx, `SELECT count(*) FROM pg_subscription`).Scan(&subs)
	var ident string
	_ = e.srcDB.QueryRow(ctx, `SELECT relreplident::text FROM pg_class WHERE oid = 'okr.objective'::regclass`).Scan(&ident)
	if pubs != 0 || subs != 0 || slots != 0 || ident != "d" {
		t.Fatalf("còn đối tượng sao chép: pub %d sub %d slot %d replident %s", pubs, subs, slots, ident)
	}

	// Danh bạ: đóng gắn cell cũ, mở gắn cell mới, không chồng lấn; tenant DANG_VAN_HANH.
	cur, err := store.CurrentTenantCell(ctx, e.pool, a, time.Now())
	if err != nil || cur.CellID != e.target.ID || cur.TenancyModel != "pool" {
		t.Fatalf("gắn cell hiện hành: %+v %v", cur, err)
	}
	var bindings int
	_ = e.pool.QueryRow(ctx, `SELECT count(*) FROM registry.tenant_cell WHERE tenant_id = $1`, a).Scan(&bindings)
	if x, _ := store.GetTenant(ctx, e.pool, a); x.Status != tenant.DangVanHanh || bindings != 2 {
		t.Fatalf("tenant sau chuyển: %s, %d dòng tenant_cell", x.Status, bindings)
	}
	var events []string
	rows, _ := e.adm.Query(ctx, `SELECT event_type FROM registry.outbox_event WHERE tenant_id = $1
		AND convert_from(payload, 'UTF8')::jsonb->>'moveId' IS NOT NULL ORDER BY created_at`, a)
	events, _ = pgx.CollectRows(rows, pgx.RowTo[string])
	if strings.Join(events, ",") != "TENANT_WRITE_LOCKED,TENANT_CELL_SWITCHED,TENANT_WRITE_UNLOCKED" {
		t.Fatalf("sự kiện cổng: %v", events)
	}

	// Sổ cái: đầu chuỗi đích nối tiếp đầu chuỗi nguồn.
	e.ledger.mu.Lock()
	heads := e.ledger.heads[e.target.Code+a.String()]
	sealed := e.ledger.sealed["CELL-SH"+a.String()]
	e.ledger.mu.Unlock()
	if !sealed || len(heads) != 64 || st.Ledger == nil || heads[5].LastHash != st.Ledger.Heads[5].LastHash {
		t.Fatalf("chuyển giao sổ cái: niêm phong %v, %d đầu chuỗi", sealed, len(heads))
	}
	// Đối tượng: đủ ở đích, không chép đối tượng của tenant khác.
	if e.s3dst.count("dossier/t/"+a.String()) != 3 || e.s3dst.count("t/"+b.String()) != 0 || st.Objects == nil || !st.Objects.OK {
		t.Fatalf("đối tượng: %+v", st.Objects)
	}
	if e.s3src.badSig+e.s3dst.badSig != 0 {
		t.Fatal("chữ ký SigV4 không khớp")
	}

	// Client gọi cell cũ sau cắt chuyển nhận 421 kèm URL cell mới.
	user := e.keys.Token(authtest.Claims{Subject: "0192f1a2-cccc-7000-8000-000000000003", Tenant: tn.Code, Cell: "CELL-SH", Roles: []string{"member"},
		ACR: "aal1", AuthTime: time.Now()})
	rec = e.do(t, http.MethodGet, "/api/v1/registry/resolve?tenant="+tn.Code, user, nil, nil)
	e.conform(t, "/registry/resolve", "get", rec)
	if rec.Code != 421 || rec.Header().Get("Location") != e.target.APIBaseURL {
		t.Fatalf("cell cũ phải nhận 421: %d %v %s", rec.Code, rec.Header(), rec.Body.String())
	}
	// Đã cắt chuyển thì không huỷ được.
	problemCode(t, e.do(t, http.MethodPost, "/api/v1/registry/tenants/"+a.String()+"/move/"+mv.ID.String()+"/cancel", e.admin(admin1), nil,
		map[string]string{"If-Match": fmt.Sprintf(`"%d"`, st.RowVersion)}), 409, "REGISTRY-409-MOVE-ALREADY-CUTOVER")

	// Dọn dữ liệu nguồn sau thời hạn giữ 30 ngày (biên bản số dòng ghi vào lệnh chuyển).
	if n, err := e.runner.PurgeExpired(ctx); err != nil || n != 0 {
		t.Fatalf("chưa hết hạn giữ mà đã dọn: %d %v", n, err)
	}
	e.runner.Now = func() time.Time { return time.Now().Add(31 * 24 * time.Hour) }
	if n, err := e.runner.PurgeExpired(ctx); err != nil || n < 1 {
		t.Fatalf("dọn nguồn: %d %v", n, err)
	}
	e.runner.Now = nil
	if c := countRows(t, e.srcDB, a); c.obj != 0 || c.task != 0 {
		t.Fatalf("nguồn còn dữ liệu: %+v", c)
	}
	if countRows(t, e.srcDB, b).obj == 0 || countRows(t, e.dstDB, a).obj == 0 {
		t.Fatal("dọn nhầm dữ liệu")
	}
	if st2 := e.state(t, mv.ID); st2.PurgedAt == nil || st2.PurgedRows == 0 {
		t.Fatalf("biên bản dọn: %+v", st2.PurgedRows)
	}
	// Bucket khoá Object Lock (evidence, dossier) giữ nguyên, vòng đời Ceph xử lý; templates được xoá.
	if e.s3src.count("t/"+a.String()+"/") != 4 || e.s3src.count("templates/t/"+a.String()) != 0 || e.s3dst.count("templates/t/"+a.String()) != 1 {
		t.Fatalf("đối tượng nguồn: %d", e.s3src.count("t/"+a.String()+"/"))
	}
}

// TestMoveCancelBeforeCutover: lệnh đang chạy (chờ giờ cắt chuyển) được huỷ bằng hai người; quay về trạng thái
// cũ, đích không còn dữ liệu, nguồn không còn publication, rào chặn, replica identity đã khôi phục.
func TestMoveCancelBeforeCutover(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	tn, a := e.seedTenant(t, 500, 100)
	before := rowHash(t, e.srcDB, a)
	planned := time.Now().Add(2 * time.Hour)
	rec := e.do(t, http.MethodPost, "/api/v1/registry/tenants/"+a.String()+"/move", e.admin(admin1),
		map[string]any{"targetCellId": e.target.ID, "reason": "Lên lịch cắt chuyển ngoài giờ", "plannedAt": planned}, map[string]string{"If-Match": fmt.Sprintf(`"%d"`, tn.RowVersion)})
	if rec.Code != 201 {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
	mv := decodeMove(t, rec)
	base := "/api/v1/registry/tenants/" + a.String() + "/move/" + mv.ID.String()
	if rec := e.do(t, http.MethodPost, base+"/approve", e.admin(admin2), nil, map[string]string{"If-Match": `"0"`}); rec.Code != 202 {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
	// Chờ saga tới bước khoá ghi (đợi giờ cắt chuyển): dữ liệu đã chép sang đích.
	deadline := time.Now().Add(2 * time.Minute)
	for {
		st := e.state(t, mv.ID)
		if st.CurrentStep != nil && *st.CurrentStep == move.StepFreeze {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("saga chưa tới bước khoá ghi: %+v", st.Steps)
		}
		time.Sleep(100 * time.Millisecond)
	}
	if countRows(t, e.dstDB, a).obj != 500 {
		t.Fatal("dữ liệu chưa chép sang đích")
	}
	st := e.state(t, mv.ID)
	cancel := func(sub string, version int) *httptest.ResponseRecorder {
		r := e.do(t, http.MethodPost, base+"/cancel", e.admin(sub), map[string]string{"comment": "Huỷ để điều chỉnh kế hoạch"},
			map[string]string{"If-Match": fmt.Sprintf(`"%d"`, version)})
		e.conform(t, "/registry/tenants/{id}/move/{moveId}/cancel", "post", r)
		return r
	}
	rec = cancel(admin1, st.RowVersion)
	if rec.Code != 200 || decodeMove(t, rec).Status != move.DangChuyen {
		t.Fatalf("đề nghị huỷ: %d %s", rec.Code, rec.Body.String())
	}
	v := decodeMove(t, rec)
	problemCode(t, cancel(admin1, v.RowVersion), 403, "REGISTRY-403-SAME-APPROVER")
	if rec = cancel(admin2, v.RowVersion); rec.Code != 200 {
		t.Fatalf("xác nhận huỷ: %d %s", rec.Code, rec.Body.String())
	}
	e.runner.Wait()
	final := e.state(t, mv.ID)
	if final.Status != move.DaHuy {
		t.Fatalf("phải DA_HUY: %s %s", final.Status, final.Failure)
	}
	if x, _ := store.GetTenant(ctx, e.pool, a); x.Status != tenant.DangVanHanh {
		t.Fatalf("tenant phải về DANG_VAN_HANH: %s", x.Status)
	}
	if cur, _ := store.CurrentTenantCell(ctx, e.pool, a, time.Now()); cur.CellID != srcCell {
		t.Fatal("gắn cell không được đổi khi huỷ")
	}
	if c := countRows(t, e.dstDB, a); c.obj != 0 || c.task != 0 {
		t.Fatalf("đích còn dữ liệu: %+v", c)
	}
	var parts, pubs, subs, fences int
	_ = e.dstDB.QueryRow(ctx, `SELECT count(*) FROM pg_inherits WHERE inhparent = 'task.task_progress'::regclass
		AND pg_get_expr((SELECT relpartbound FROM pg_class WHERE oid = inhrelid), inhrelid) LIKE '%' || $1 || '%'`, a.String()).Scan(&parts)
	_ = e.srcDB.QueryRow(ctx, `SELECT (SELECT count(*) FROM pg_publication), (SELECT count(*) FROM pg_trigger WHERE tgname = $1)`, move.Name(mv.ID)).Scan(&pubs, &fences)
	_ = e.dstDB.QueryRow(ctx, `SELECT count(*) FROM pg_subscription`).Scan(&subs)
	if parts != 0 || pubs != 0 || subs != 0 || fences != 0 {
		t.Fatalf("còn tài nguyên: phân vùng %d pub %d sub %d rào %d", parts, pubs, subs, fences)
	}
	if rowHash(t, e.srcDB, a) != before {
		t.Fatal("dữ liệu nguồn bị đổi khi huỷ")
	}
	if _, err := e.srcDB.Exec(ctx, `INSERT INTO okr.objective (id, tenant_id, title) VALUES (gen_random_uuid(), $1, 'ghi sau huỷ')`, a); err != nil {
		t.Fatalf("sau huỷ phải ghi được ở nguồn: %v", err)
	}
	// Huỷ đề nghị đang chờ duyệt (một người là đủ).
	tn2, a2 := e.seedTenant(t, 1, 1)
	rec = e.do(t, http.MethodPost, "/api/v1/registry/tenants/"+a2.String()+"/move", e.admin(admin1),
		map[string]any{"targetCellId": e.target.ID, "reason": "thử"}, map[string]string{"If-Match": fmt.Sprintf(`"%d"`, tn2.RowVersion)})
	mv2 := decodeMove(t, rec)
	rec = e.do(t, http.MethodPost, "/api/v1/registry/tenants/"+a2.String()+"/move/"+mv2.ID.String()+"/cancel", e.admin(admin1), nil, map[string]string{"If-Match": `"0"`})
	if rec.Code != 200 || decodeMove(t, rec).Status != move.DaHuy {
		t.Fatalf("huỷ đề nghị chờ duyệt: %d %s", rec.Code, rec.Body.String())
	}
}

// TestMoveTargetSchemaMissing: CSDL đích thiếu bảng → saga dừng ở bước chuẩn bị, quay lui, tenant về DANG_VAN_HANH.
func TestMoveTargetSchemaMissing(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	tn, a := e.seedTenant(t, 10, 5)
	if _, err := e.srcDB.Exec(ctx, `CREATE TABLE IF NOT EXISTS okr.key_result_tmp (id uuid PRIMARY KEY, tenant_id uuid NOT NULL)`); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _, _ = e.srcDB.Exec(ctx, `DROP TABLE IF EXISTS okr.key_result_tmp`) })
	rec := e.do(t, http.MethodPost, "/api/v1/registry/tenants/"+a.String()+"/move", e.admin(admin1),
		map[string]any{"targetCellId": e.target.ID, "reason": "thử thiếu bảng"}, map[string]string{"If-Match": fmt.Sprintf(`"%d"`, tn.RowVersion)})
	mv := decodeMove(t, rec)
	if rec := e.do(t, http.MethodPost, "/api/v1/registry/tenants/"+a.String()+"/move/"+mv.ID.String()+"/approve", e.admin(admin2), nil,
		map[string]string{"If-Match": `"0"`}); rec.Code != 202 {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
	e.runner.Wait()
	st := e.state(t, mv.ID)
	if st.Status != move.ThatBai || !strings.Contains(st.Failure, "okr.key_result_tmp") {
		t.Fatalf("phải THAT_BAI vì thiếu bảng: %s %q", st.Status, st.Failure)
	}
	if x, _ := store.GetTenant(ctx, e.pool, a); x.Status != tenant.DangVanHanh {
		t.Fatalf("tenant: %s", x.Status)
	}
	if c := countRows(t, e.dstDB, a); c.obj != 0 {
		t.Fatalf("đích còn dữ liệu: %+v", c)
	}
}
