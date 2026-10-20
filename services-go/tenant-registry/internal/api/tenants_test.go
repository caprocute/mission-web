package api_test

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/platform/auth/authtest"
	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
)

const (
	configYAML = "modules/config.yaml"
	jobsYAML   = "modules/jobs.yaml"
)

type cfgTenant struct {
	ID                    uuid.UUID `json:"id"`
	Code                  string    `json:"code"`
	Name                  string    `json:"name"`
	OnboardingCode        string    `json:"onboardingCode"`
	TenancyModel          string    `json:"tenancyModel"`
	SuggestedTenancyModel string    `json:"suggestedTenancyModel"`
	CellCode              string    `json:"cellCode"`
	Domain                string    `json:"domain"`
	DataRegion            string    `json:"dataRegion"`
	KekMode               string    `json:"kekMode"`
	Status                string    `json:"status"`
	OnboardingStep        *int      `json:"onboardingStep"`
	RowVersion            int       `json:"rowVersion"`
	AdminContact          *struct {
		FullName, Email string
	} `json:"adminContact"`
	StatusLog []struct {
		FromStatus *string `json:"fromStatus"`
		ToStatus   string  `json:"toStatus"`
	} `json:"statusLog"`
	Idps []struct {
		IdpAlias string `json:"idpAlias"`
	} `json:"idps"`
	StaffSources []struct {
		Type string `json:"type"`
	} `json:"staffSources"`
}

type problemBody struct {
	Type   string `json:"type"`
	Status int    `json:"status"`
	Code   string `json:"code"`
	Msg    string `json:"msg"`
	Detail string `json:"detail"`
	Errors []struct {
		Field string `json:"field"`
	} `json:"errors"`
}

func decode[T any](t testing.TB, rec *httptest.ResponseRecorder) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("giải mã %q: %v", rec.Body.String(), err)
	}
	return v
}

func expectProblem(t testing.TB, rec *httptest.ResponseRecorder, status int, code string) problemBody {
	t.Helper()
	if rec.Code != status {
		t.Fatalf("muốn %d %s, nhận %d: %s", status, code, rec.Code, rec.Body.String())
	}
	p := decode[problemBody](t, rec)
	if code != "" && p.Code != code {
		t.Fatalf("muốn mã %s, nhận %s (%s)", code, p.Code, rec.Body.String())
	}
	if !strings.HasPrefix(p.Type, "https://okrkpi.vn/problems/") || rec.Header().Get("Content-Type") != "application/problem+json" {
		t.Fatalf("không phải Problem Details: %s", rec.Body.String())
	}
	return p
}

var seq int

func uniq(prefix string) string {
	seq++
	return fmt.Sprintf("%s%d%s", prefix, seq, strings.ReplaceAll(uuid.NewString()[:6], "-", ""))
}

func idemKey(h ...string) map[string]string {
	m := map[string]string{"Idempotency-Key": uuid.NewString(), "Content-Type": "application/json"}
	for i := 0; i+1 < len(h); i += 2 {
		m[h[i]] = h[i+1]
	}
	return m
}

func createBody(code, sector string, expected int, flows ...string) []byte {
	if len(flows) == 0 {
		flows = []string{"DANG", "NHA_NUOC"}
	}
	b, _ := json.Marshal(map[string]any{"code": code, "name": "Tỉnh " + code, "sector": sector, "contractRef": "HĐ-2026/VTS-" + code,
		"contractValidTo": "2029-12-31", "flows": flows, "expectedAccounts": expected, "expectedUnits": 120,
		"adminContact": map[string]string{"fullName": "Trần Thị Hồng", "email": "quantri@" + strings.ReplaceAll(code, "_", "-") + ".gov.vn"}})
	return b
}

func mustCreate(t testing.TB, f *fixture, code, sector string, expected int, flows ...string) cfgTenant {
	t.Helper()
	body := createBody(code, sector, expected, flows...)
	if err := specs.ValidateRequest(configYAML, "/config/tenants", "post", "application/json", body); err != nil {
		t.Fatalf("thân yêu cầu lệch hợp đồng: %v", err)
	}
	rec := f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), body, idemKey())
	if rec.Code != http.StatusCreated {
		t.Fatalf("tạo tenant: %d %s", rec.Code, rec.Body.String())
	}
	conform(t, configYAML, "/config/tenants", "post", rec)
	return decode[cfgTenant](t, rec)
}

func patch(t testing.TB, f *fixture, tok string, id uuid.UUID, version int, body string) *httptest.ResponseRecorder {
	t.Helper()
	return patchX(t, f, tok, id, version, body, true)
}

// patchX: strict = kiểm thân yêu cầu với lược đồ hợp đồng. Thân có null (xoá trường theo RFC 7396) hoặc đối
// tượng lồng một phần không qua được lược đồ ConfigTenantPatch (điểm lệch ghi ở wiki trang 14).
func patchX(t testing.TB, f *fixture, tok string, id uuid.UUID, version int, body string, strict bool) *httptest.ResponseRecorder {
	t.Helper()
	if !strict {
	} else if err := specs.ValidateRequest(configYAML, "/config/tenants/{id}", "patch", "application/merge-patch+json", []byte(body)); err != nil {
		t.Fatalf("thân yêu cầu lệch hợp đồng: %v", err)
	}
	rec := f.do(t, http.MethodPatch, "/api/v1/config/tenants/"+id.String(), tok, []byte(body),
		idemKey("Content-Type", "application/merge-patch+json", "If-Match", fmt.Sprintf(`"%d"`, version)))
	conform(t, configYAML, "/config/tenants/{id}", "patch", rec)
	return rec
}

func tenantAdminToken(tenantCode string) string {
	return keys.Token(authtest.Claims{Subject: "0192f1a2-4444-7000-8000-000000000004", Tenant: tenantCode, Roles: []string{"tenant_admin"},
		ACR: "aal2", AuthTime: time.Now()})
}

func statusLogCount(t testing.TB, f *fixture, id uuid.UUID) int {
	var n int
	if err := f.pool.QueryRow(context.Background(), `SELECT count(*) FROM registry.tenant_status_log WHERE tenant_id = $1`, id).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func outboxCount(t testing.TB, f *fixture, id uuid.UUID) int {
	t.Helper()
	// svc_registry chỉ có INSERT trên outbox (TL-03); đếm bằng vai trò quản trị kiểm thử.
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, db.AdminDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM registry.outbox_event WHERE aggregate_id = $1`, id).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

// ---------------------------------------------------------------- createConfigTenant

func TestCreateConfigTenant(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	code := uniq("tn")
	v := mustCreate(t, f, code, "CHINH_QUYEN", 98420)
	if v.Status != "KHOI_TAO" || v.TenancyModel != "bridge" || v.SuggestedTenancyModel != "bridge" || v.Domain != code+".okr.viettel.vn" {
		t.Fatalf("tenant vừa tạo sai: %+v", v)
	}
	if !regexp.MustCompile(`^ONB-\d{4}-\d{4}$`).MatchString(v.OnboardingCode) || !strings.HasPrefix(v.OnboardingCode, fmt.Sprintf("ONB-%d-", time.Now().Year())) {
		t.Fatalf("mã hồ sơ sai: %s", v.OnboardingCode)
	}
	if v.OnboardingStep == nil || *v.OnboardingStep != 2 || v.AdminContact == nil || v.AdminContact.FullName != "Trần Thị Hồng" {
		t.Fatalf("bước wizard hoặc đầu mối sai: %+v", v)
	}
	if len(v.StatusLog) != 1 || v.StatusLog[0].FromStatus != nil || v.StatusLog[0].ToStatus != "KHOI_TAO" {
		t.Fatalf("nhật ký trạng thái sai: %+v", v.StatusLog)
	}
	if outboxCount(t, f, v.ID) != 1 {
		t.Fatal("phải ghi một sự kiện outbox")
	}
	// Dữ liệu C2 được mã hoá ứng dụng: cột chỉ chứa bản mã.
	var enc []byte
	if err := f.pool.QueryRow(context.Background(), `SELECT admin_contact_enc FROM registry.tenant_onboarding WHERE tenant_id = $1`, v.ID).Scan(&enc); err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(enc, []byte("Hồng")) || bytes.Contains(enc, []byte("quantri@")) {
		t.Fatal("đầu mối quản trị lưu dạng rõ")
	}
	// Số hồ sơ tăng dần.
	v2 := mustCreate(t, f, uniq("tn"), "CHINH_QUYEN", 4000)
	if v2.OnboardingCode <= v.OnboardingCode || v2.TenancyModel != "pool" || v2.KekMode != "DUNG_CHUNG_CELL" {
		t.Fatalf("tenant thứ hai sai: %+v", v2)
	}
	silo := mustCreate(t, f, uniq("qd"), "LLVT", 3000, "NHA_NUOC")
	if silo.SuggestedTenancyModel != "silo" || silo.TenancyModel != "silo" || silo.KekMode != "RIENG" {
		t.Fatalf("BR-01-01 gợi ý silo: %+v", silo)
	}
}

func TestCreateConfigTenantIdempotency(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	code := uniq("idem")
	body := createBody(code, "CHINH_QUYEN", 20000)
	h := idemKey()
	first := f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), body, h)
	if first.Code != http.StatusCreated {
		t.Fatalf("lần đầu: %d %s", first.Code, first.Body.String())
	}
	again := f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), body, h)
	if again.Code != http.StatusCreated || again.Header().Get("Idempotency-Replayed") != "true" ||
		again.Header().Get("Location") != first.Header().Get("Location") || again.Header().Get("ETag") != first.Header().Get("ETag") {
		t.Fatalf("phát lại: %d %v", again.Code, again.Header())
	}
	conform(t, configYAML, "/config/tenants", "post", again)
	a, b := decode[cfgTenant](t, first), decode[cfgTenant](t, again)
	if a.ID != b.ID || b.AdminContact != nil {
		t.Fatalf("phát lại phải cùng tenant và không lưu C2: %+v", b)
	}
	var n int
	_ = f.pool.QueryRow(context.Background(), `SELECT count(*) FROM registry.tenant WHERE code = $1`, code).Scan(&n)
	if n != 1 {
		t.Fatalf("phát lại tạo thêm %d tenant", n-1)
	}
	other := createBody(code+"x", "CHINH_QUYEN", 20000)
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), other, h), 422, "COMMON-422-IDEMPOTENCY-MISMATCH")
	rec := f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), other, map[string]string{"Content-Type": "application/json"})
	expectProblem(t, rec, 422, "COMMON-422-VALIDATION")
	conform(t, configYAML, "/config/tenants", "post", rec)
	h2 := idemKey()
	h2["Idempotency-Key"] = strings.Repeat("k", 65)
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), other, h2), 422, "COMMON-422-VALIDATION")
}

func TestCreateConfigTenantErrors(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	code := uniq("dup")
	v := mustCreate(t, f, code, "CHINH_QUYEN", 20000)

	rec := f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), createBody(code, "CHINH_QUYEN", 20000), idemKey())
	expectProblem(t, rec, 409, "CONFIG-409-DUPLICATE")
	conform(t, configYAML, "/config/tenants", "post", rec)

	sameName, _ := json.Marshal(map[string]any{"code": uniq("dn"), "name": strings.ToUpper("Tỉnh " + code), "sector": "CHINH_QUYEN",
		"contractRef": "HĐ-1", "flows": []string{"DANG"}, "expectedAccounts": 10, "adminContact": map[string]string{"fullName": "A", "email": "a@b.vn"}})
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), sameName, idemKey()), 409, "CONFIG-409-DUPLICATE")

	// MSG-01-04: tên miền mặc định đã có tenant khác dùng làm bí danh.
	taken := uniq("dm")
	if err := store.InsertDomain(context.Background(), f.pool, &store.Domain{TenantID: v.ID, Domain: taken + ".okr.viettel.vn", Kind: "ALIAS"}, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	rec = f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), createBody(taken, "CHINH_QUYEN", 100), idemKey())
	p := expectProblem(t, rec, 409, "CONFIG-409-DOMAIN-TAKEN")
	if p.Msg != "MSG-01-04" || p.Type != "https://okrkpi.vn/problems/msg-01-04" || !strings.Contains(p.Detail, taken+".okr.viettel.vn") {
		t.Fatalf("MSG-01-04 sai: %+v", p)
	}
	conform(t, configYAML, "/config/tenants", "post", rec)

	bad := []string{
		`{}`,
		`{"code":"Hoa","name":"","sector":"X","contractRef":"","flows":["DANG","DANG"],"expectedAccounts":0,"adminContact":{"fullName":"","email":"x"}}`,
		`{"code":"ok","name":"n","sector":"DANG","contractRef":"c","flows":[],"expectedAccounts":5}`,
		`{"code":"ok","name":"n","sector":"DANG","contractRef":"c","flows":["DANG"],"expectedAccounts":5,"adminContact":{"fullName":"a","email":"a@b.vn","phone":"012345678901234567890"}}`,
		`{"unknown":1}`,
		`{"code":1}`,
	}
	for _, b := range bad {
		rec := f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), []byte(b), idemKey())
		if rec.Code != 422 {
			t.Fatalf("%s muốn 422, nhận %d %s", b, rec.Code, rec.Body.String())
		}
		conform(t, configYAML, "/config/tenants", "post", rec)
	}
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), []byte(`{`), idemKey()), 400, "COMMON-400-BAD-REQUEST")
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants", adminToken(), []byte(`{}`), idemKey("Content-Type", "text/plain")), 415, "")

	// Quyền: chưa đăng nhập 401, vai trò khác 403, chưa bước lên AAL2 401 RFC 9470, quản trị tenant 403.
	body := createBody(uniq("q"), "DANG", 10)
	rec = f.do(t, http.MethodPost, "/api/v1/config/tenants", "", body, idemKey())
	expectProblem(t, rec, 401, "COMMON-401-UNAUTHORIZED")
	conform(t, configYAML, "/config/tenants", "post", rec)
	rec = f.do(t, http.MethodPost, "/api/v1/config/tenants", userToken("", "", "member"), body, idemKey())
	expectProblem(t, rec, 403, "COMMON-403-OUT-OF-SCOPE")
	conform(t, configYAML, "/config/tenants", "post", rec)
	aal1 := keys.Token(authtest.Claims{Subject: uuid.NewString(), Roles: []string{"platform_admin"}, ACR: "aal1", AuthTime: time.Now()})
	rec = f.do(t, http.MethodPost, "/api/v1/config/tenants", aal1, body, idemKey())
	expectProblem(t, rec, 401, "COMMON-401-STEP-UP")
	if !strings.Contains(rec.Header().Get("WWW-Authenticate"), "insufficient_user_authentication") {
		t.Fatal("thiếu WWW-Authenticate RFC 9470")
	}
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants", tenantAdminToken(code), body, idemKey()), 403, "")
}

// ---------------------------------------------------------------- updateConfigTenant

func TestUpdateConfigTenantSteps13(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	v := mustCreate(t, f, uniq("up"), "CHINH_QUYEN", 30000)
	id := v.ID

	// Thiếu If-Match 428, lệch 409, sai kiểu nội dung 415.
	rec := f.do(t, http.MethodPatch, "/api/v1/config/tenants/"+id.String(), adminToken(), []byte(`{"name":"x"}`), idemKey("Content-Type", "application/merge-patch+json"))
	expectProblem(t, rec, 428, "COMMON-428-IF-MATCH-REQUIRED")
	conform(t, configYAML, "/config/tenants/{id}", "patch", rec)
	rec = patch(t, f, adminToken(), id, v.RowVersion+5, `{"name":"Tên mới"}`)
	p := expectProblem(t, rec, 409, "CONFIG-409-VERSION-CONFLICT")
	if !strings.Contains(p.Detail, fmt.Sprint(v.RowVersion)) {
		t.Fatalf("409 phải nêu phiên bản hiện tại: %+v", p)
	}
	rec = f.do(t, http.MethodPatch, "/api/v1/config/tenants/"+id.String(), adminToken(), []byte(`{}`), idemKey("If-Match", `"0"`))
	expectProblem(t, rec, 415, "")
	rec = f.do(t, http.MethodPatch, "/api/v1/config/tenants/"+id.String(), adminToken(), []byte(`[1]`),
		idemKey("Content-Type", "application/merge-patch+json", "If-Match", `"0"`))
	expectProblem(t, rec, 400, "")
	expectProblem(t, patch(t, f, adminToken(), uuid.New(), 0, `{"name":"x"}`), 404, "COMMON-404-NOT-FOUND")
	expectProblem(t, f.do(t, http.MethodPatch, "/api/v1/config/tenants/khong-phai-uuid", adminToken(), []byte(`{}`),
		idemKey("Content-Type", "application/merge-patch+json", "If-Match", `"0"`)), 404, "")

	// Bước 2: chọn mô hình, hệ thống tự chọn cell tải thấp nhất.
	rec = patch(t, f, adminToken(), id, v.RowVersion, `{"tenancyModel":"bridge","dataRegion":"HOALAC","kekMode":"RIENG"}`)
	if rec.Code != 200 {
		t.Fatalf("lưu nháp: %d %s", rec.Code, rec.Body.String())
	}
	u := decode[cfgTenant](t, rec)
	if u.RowVersion != v.RowVersion+1 || rec.Header().Get("ETag") != fmt.Sprintf(`"%d"`, u.RowVersion) {
		t.Fatalf("phiên bản sau lưu sai: %d %s", u.RowVersion, rec.Header().Get("ETag"))
	}
	if !strings.HasPrefix(u.CellCode, "CELL-G") || u.DataRegion != "HOALAC" || u.OnboardingStep == nil || *u.OnboardingStep != 3 {
		t.Fatalf("chọn cell sai: %+v", u)
	}
	// Ghi lại If-Match cũ: 409.
	expectProblem(t, patch(t, f, adminToken(), id, v.RowVersion, `{"name":"Tên khác"}`), 409, "CONFIG-409-VERSION-CONFLICT")

	// Chọn cell cụ thể, đổi tên miền, gắn gói chính sách đủ hai luồng, UI Pack.
	body := `{"cellCode":"CELL-G3","domain":"okr.` + v.Code + `.gov.vn","policyBindings":[{"flow":"DANG","packVersionId":"0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a70"},{"flow":"NHA_NUOC","packVersionId":"0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a71"}],"uiPackVersionId":"0192e2d5-6e7f-7a81-8b2c-3d4e5f6a7b80","contractValidTo":null,"expectedUnits":1300,"adminContact":{"phone":"0912345678"}}`
	rec = patchX(t, f, adminToken(), id, u.RowVersion, body, false)
	if rec.Code != 200 {
		t.Fatalf("lưu nháp bước 2–3: %d %s", rec.Code, rec.Body.String())
	}
	u = decode[cfgTenant](t, rec)
	if u.CellCode != "CELL-G3" || u.Domain != "okr."+v.Code+".gov.vn" || u.AdminContact == nil || u.AdminContact.FullName != "Trần Thị Hồng" {
		t.Fatalf("lưu nháp sai: %s", rec.Body.String())
	}
	var tenancy, db string
	if err := f.pool.QueryRow(context.Background(), `SELECT tc.tenancy_model, tc.database_name FROM registry.tenant_cell tc WHERE tc.tenant_id = $1`, id).Scan(&tenancy, &db); err != nil {
		t.Fatal(err)
	}
	if tenancy != "bridge" || db != "td_"+v.Code {
		t.Fatalf("tenant_cell nháp sai: %s %s", tenancy, db)
	}
	// Chuyển sang pool: khoá mặc định dùng chung của cell, cell pool.
	rec = patch(t, f, adminToken(), id, u.RowVersion, `{"tenancyModel":"pool","expectedAccounts":3000}`)
	if rec.Code != 200 {
		t.Fatalf("đổi pool: %d %s", rec.Code, rec.Body.String())
	}
	u = decode[cfgTenant](t, rec)
	if u.CellCode != "CELL-SH" || u.KekMode != "DUNG_CHUNG_CELL" {
		t.Fatalf("đổi pool sai: %+v", u)
	}
	// Merge Patch: trường bắt buộc không xoá được, trường lạ bị từ chối.
	for _, b := range []string{`{"name":null}`, `{"tenancyModel":"airgap"}`, `{"domain":"khong hop le"}`, `{"cellCode":"CELL-XX"}`,
		`{"cellCode":"CELL-G1"}`, `{"policyBindings":[{"flow":"DANG","packVersionId":"0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a70"},{"flow":"DANG","packVersionId":"0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a70"}]}`,
		`{"flows":["DANG","DANG"]}`, `{"adminContact":{"email":"sai"}}`, `{"adminContact":null}`, `{"expectedAccounts":"x"}`} {
		rec := f.do(t, http.MethodPatch, "/api/v1/config/tenants/"+id.String(), adminToken(), []byte(b),
			idemKey("Content-Type", "application/merge-patch+json", "If-Match", fmt.Sprintf(`"%d"`, u.RowVersion)))
		expectProblem(t, rec, 422, "")
		conform(t, configYAML, "/config/tenants/{id}", "patch", rec)
	}
	rec = f.do(t, http.MethodPatch, "/api/v1/config/tenants/"+id.String(), adminToken(), []byte(`{"bogus":1}`),
		idemKey("Content-Type", "application/merge-patch+json", "If-Match", fmt.Sprintf(`"%d"`, u.RowVersion)))
	expectProblem(t, rec, 422, "CONFIG-422-VALIDATION")
	// Bỏ luồng NHA_NUOC: gói của luồng đó bị gỡ.
	rec = patch(t, f, adminToken(), id, u.RowVersion, `{"flows":["DANG"]}`)
	if rec.Code != 200 || strings.Contains(rec.Body.String(), "NHA_NUOC") {
		t.Fatalf("bỏ luồng: %d %s", rec.Code, rec.Body.String())
	}
	if statusLogCount(t, f, id) != 1 {
		t.Fatal("lưu nháp không đổi trạng thái nên không thêm nhật ký")
	}
	// GET trả bước hiện tại và ETag.
	rec = f.do(t, http.MethodGet, "/api/v1/config/tenants/"+id.String(), adminToken(), nil, nil)
	conform(t, configYAML, "/config/tenants/{id}", "get", rec)
	if rec.Code != 200 || rec.Header().Get("ETag") == "" {
		t.Fatalf("GET: %d", rec.Code)
	}
}

func TestUpdateConfigTenantBusinessRules(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	ctx := context.Background()

	// BR-01-01: lực lượng vũ trang không chọn được bridge.
	qd := mustCreate(t, f, uniq("qd"), "LLVT", 20000, "NHA_NUOC")
	rec := patch(t, f, adminToken(), qd.ID, qd.RowVersion, `{"tenancyModel":"bridge"}`)
	p := expectProblem(t, rec, 422, "CONFIG-422-SILO-REQUIRED")
	if p.Type != "https://okrkpi.vn/problems/br-01-01" {
		t.Fatalf("type phải trỏ BR-01-01: %s", p.Type)
	}
	// Silo bắt buộc khoá riêng.
	expectProblem(t, patch(t, f, adminToken(), qd.ID, qd.RowVersion, `{"kekMode":"DUNG_CHUNG_CELL"}`), 422, "CONFIG-422-VALIDATION")
	// Tăng quy mô lên trên 1.000.000 khi đang bridge: chặn.
	big := mustCreate(t, f, uniq("big"), "CHINH_QUYEN", 900000)
	expectProblem(t, patch(t, f, adminToken(), big.ID, big.RowVersion, `{"expectedAccounts":1000001}`), 422, "CONFIG-422-SILO-REQUIRED")

	// BR-01-04: tenant hai luồng phải gắn đủ gói cho mỗi luồng.
	two := mustCreate(t, f, uniq("tw"), "CHINH_QUYEN", 20000)
	rec = patch(t, f, adminToken(), two.ID, two.RowVersion, `{"policyBindings":[{"flow":"DANG","packVersionId":"0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a70"}]}`)
	p = expectProblem(t, rec, 422, "CONFIG-422-FLOW-PACK-MISSING")
	if p.Type != "https://okrkpi.vn/problems/br-01-04" {
		t.Fatal(p.Type)
	}

	// BR-01-02 tại biên 85% trên CELL-G4.
	cells, err := store.ListCells(ctx, f.pool, store.CellFilter{TenancyModel: "BRIDGE"})
	if err != nil {
		t.Fatal(err)
	}
	var g4 store.Cell
	for _, c := range cells {
		if c.Code == "CELL-G4" {
			g4 = c
		}
	}
	room := int(0.85*float64(g4.CapacityAccounts)) - g4.Reserved
	edge := mustCreate(t, f, uniq("edge"), "CHINH_QUYEN", room)
	rec = patch(t, f, adminToken(), edge.ID, edge.RowVersion, `{"tenancyModel":"bridge","cellCode":"CELL-G4"}`)
	if rec.Code != 200 {
		t.Fatalf("đúng 85%% phải được nhận: %d %s", rec.Code, rec.Body.String())
	}
	edge = decode[cfgTenant](t, rec)
	one := mustCreate(t, f, uniq("one"), "CHINH_QUYEN", 5000)
	rec = patch(t, f, adminToken(), one.ID, one.RowVersion, `{"tenancyModel":"bridge","cellCode":"CELL-G4"}`)
	p = expectProblem(t, rec, 422, "CONFIG-422-CELL-OVER-CAPACITY")
	if p.Type != "https://okrkpi.vn/problems/br-01-02" || !strings.Contains(p.Detail, "CELL-G4") {
		t.Fatalf("BR-01-02 sai: %+v", p)
	}
	// Tự chọn tránh cell đã đầy.
	rec = patch(t, f, adminToken(), one.ID, one.RowVersion, `{"tenancyModel":"bridge"}`)
	if rec.Code != 200 || decode[cfgTenant](t, rec).CellCode == "CELL-G4" {
		t.Fatalf("tự chọn phải tránh CELL-G4: %s", rec.Body.String())
	}
	// Tăng quy mô của chính tenant đang ở biên: vượt 85% bị chặn; giữ nguyên quy mô (đã trừ phần giữ chỗ cũ) được nhận.
	expectProblem(t, patch(t, f, adminToken(), edge.ID, edge.RowVersion, fmt.Sprintf(`{"expectedAccounts":%d}`, room+1)), 422, "CONFIG-422-CELL-OVER-CAPACITY")
	if rec := patch(t, f, adminToken(), edge.ID, edge.RowVersion, `{"dataRegion":"PHIANAM"}`); rec.Code != 200 {
		t.Fatalf("giữ cell ở biên: %d %s", rec.Code, rec.Body.String())
	}

	// MSG-01-03: không còn cell pool nào chứa được 900.000 tài khoản.
	huge := mustCreate(t, f, uniq("pl"), "CHINH_QUYEN", 900000)
	rec = patch(t, f, adminToken(), huge.ID, huge.RowVersion, `{"tenancyModel":"pool"}`)
	p = expectProblem(t, rec, 422, "CONFIG-422-CELL-OVER-CAPACITY")
	if p.Msg != "MSG-01-03" || !strings.Contains(p.Detail, "pool") {
		t.Fatalf("MSG-01-03 sai: %+v", p)
	}

	// MSG-01-04 khi lưu nháp tên miền đã dùng.
	rec = patch(t, f, adminToken(), huge.ID, huge.RowVersion, `{"domain":"`+edge.Domain+`"}`)
	expectProblem(t, rec, 409, "CONFIG-409-DOMAIN-TAKEN")
	expectProblem(t, patch(t, f, adminToken(), huge.ID, huge.RowVersion, `{"name":"`+edge.Name+`"}`), 409, "CONFIG-409-DUPLICATE")

	// Cell bảo trì không chọn được.
	if _, err := f.pool.Exec(ctx, `UPDATE registry.cell SET status = 'BAO_TRI' WHERE code = 'CELL-G2'`); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = f.pool.Exec(ctx, `UPDATE registry.cell SET status = 'HOAT_DONG' WHERE code = 'CELL-G2'`)
	})
	expectProblem(t, patch(t, f, adminToken(), huge.ID, huge.RowVersion, `{"cellCode":"CELL-G2","expectedAccounts":10}`), 422, "CONFIG-422-VALIDATION")

	// Giải phóng chỗ trên CELL-G4 cho các kiểm thử khác.
	cur := decode[cfgTenant](t, f.do(t, http.MethodGet, "/api/v1/config/tenants/"+edge.ID.String(), adminToken(), nil, nil))
	if rec := f.do(t, http.MethodDelete, "/api/v1/config/tenants/"+edge.ID.String(), adminToken(), nil, idemKey("If-Match", fmt.Sprintf(`"%d"`, cur.RowVersion))); rec.Code != 204 {
		t.Fatalf("huỷ: %d %s", rec.Code, rec.Body.String())
	}
}

func TestUpdateConfigTenantRolesAndState(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	ctx := context.Background()
	v := mustCreate(t, f, uniq("ro"), "CHINH_QUYEN", 20000)
	other := mustCreate(t, f, uniq("ot"), "CHINH_QUYEN", 20000)
	ta := tenantAdminToken(v.Code)

	// Quản trị tenant sửa bước 4, 5 của tenant mình.
	rec := patch(t, f, ta, v.ID, v.RowVersion, `{"idps":[{"idpAlias":"vneid","protocol":"OIDC","issuer":"https://sso.vneid.gov.vn","isVneid":true},{"idpAlias":"ad-tinh","protocol":"SAML","issuer":"https://adfs.tinh.gov.vn","emailDomains":["tinh.gov.vn"]}],"staffSources":[{"type":"CSDL_CBCCVC"}]}`)
	if rec.Code != 200 {
		t.Fatalf("bước 4–5: %d %s", rec.Code, rec.Body.String())
	}
	u := decode[cfgTenant](t, rec)
	if len(u.Idps) != 2 || len(u.StaffSources) != 1 {
		t.Fatalf("IdP, nguồn nhân sự sai: %+v", u)
	}
	// Thay tập IdP: giữ vneid (cập nhật), bỏ ad-tinh.
	rec = patchX(t, f, ta, v.ID, u.RowVersion, `{"idps":[{"idpAlias":"vneid","protocol":"OIDC","issuer":"https://sso.vneid.gov.vn/v2","isVneid":true}],"staffSources":null}`, false)
	u = decode[cfgTenant](t, rec)
	if rec.Code != 200 || len(u.Idps) != 1 || len(u.StaffSources) != 0 {
		t.Fatalf("thay IdP: %d %s", rec.Code, rec.Body.String())
	}
	for _, b := range []string{`{"idps":[{"idpAlias":"","protocol":"LDAP","issuer":""}]}`, `{"staffSources":[{"type":"EXCEL"}]}`, `{"idps":{"a":1}}`, `{"staffSources":1}`} {
		rec := f.do(t, http.MethodPatch, "/api/v1/config/tenants/"+v.ID.String(), ta, []byte(b),
			idemKey("Content-Type", "application/merge-patch+json", "If-Match", fmt.Sprintf(`"%d"`, u.RowVersion)))
		expectProblem(t, rec, 422, "")
	}
	// Quản trị tenant không sửa được bước 1–3; quản trị nền tảng không sửa được bước 4–5 (ma trận: R/N).
	expectProblem(t, patch(t, f, ta, v.ID, u.RowVersion, `{"name":"Đổi tên"}`), 403, "COMMON-403-OUT-OF-SCOPE")
	expectProblem(t, patch(t, f, adminToken(), v.ID, u.RowVersion, `{"staffSources":[{"type":"SCIM"}]}`), 403, "COMMON-403-OUT-OF-SCOPE")
	// Phạm vi Tn: không xem, không sửa tenant khác.
	expectProblem(t, patch(t, f, ta, other.ID, other.RowVersion, `{"staffSources":[]}`), 403, "")
	rec = f.do(t, http.MethodGet, "/api/v1/config/tenants/"+other.ID.String(), ta, nil, nil)
	expectProblem(t, rec, 403, "")
	conform(t, configYAML, "/config/tenants/{id}", "get", rec)
	rec = f.do(t, http.MethodGet, "/api/v1/config/tenants/"+v.ID.String(), ta, nil, nil)
	if rec.Code != 200 {
		t.Fatalf("quản trị tenant xem tenant mình: %d", rec.Code)
	}
	expectProblem(t, f.do(t, http.MethodGet, "/api/v1/config/tenants/"+uuid.NewString(), adminToken(), nil, nil), 404, "")

	// Sau khi rời KHOI_TAO, bước 1–3 khoá lại (409 CONFIG-409-TENANT-STATE); saga đang chạy khoá cả bước 4–5.
	svc := f.srv.Tenants
	tx, err := f.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	ten, _ := store.GetTenantForUpdate(ctx, tx, v.ID)
	if err := svc.Transition(ctx, tx, tenant.Actor{ID: store.SystemActor}, ten, ten.RowVersion, tenant.DangCapPhat, "Bắt đầu cấp phát"); err != nil {
		t.Fatal(err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	rec = patch(t, f, adminToken(), v.ID, ten.RowVersion, `{"name":"Đổi"}`)
	p := expectProblem(t, rec, 409, "CONFIG-409-TENANT-STATE")
	_ = p
	expectProblem(t, patch(t, f, ta, v.ID, ten.RowVersion, `{"staffSources":[]}`), 409, "CONFIG-409-TENANT-STATE")
	g := decode[cfgTenant](t, f.do(t, http.MethodGet, "/api/v1/config/tenants/"+v.ID.String(), adminToken(), nil, nil))
	if g.Status != "DANG_CAP_PHAT" || g.OnboardingStep == nil || *g.OnboardingStep != 4 || len(g.StatusLog) != 2 || g.StatusLog[0].ToStatus != "DANG_CAP_PHAT" {
		t.Fatalf("trạng thái sau cấp phát: %+v", g)
	}
	// Huỷ chỉ khi KHOI_TAO.
	rec = f.do(t, http.MethodDelete, "/api/v1/config/tenants/"+v.ID.String(), adminToken(), nil, idemKey("If-Match", fmt.Sprintf(`"%d"`, g.RowVersion)))
	expectProblem(t, rec, 409, "CONFIG-409-TENANT-STATE")
	conform(t, configYAML, "/config/tenants/{id}", "delete", rec)
}

// ---------------------------------------------------------------- cancelConfigTenant

func TestCancelConfigTenant(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	v := mustCreate(t, f, uniq("cx"), "CHINH_QUYEN", 20000)
	u := decode[cfgTenant](t, patch(t, f, adminToken(), v.ID, v.RowVersion, `{"tenancyModel":"bridge"}`))
	del := func(tok string, version int, h map[string]string) *httptest.ResponseRecorder {
		if h == nil {
			h = idemKey("If-Match", fmt.Sprintf(`"%d"`, version))
		}
		rec := f.do(t, http.MethodDelete, "/api/v1/config/tenants/"+v.ID.String(), tok, nil, h)
		conform(t, configYAML, "/config/tenants/{id}", "delete", rec)
		return rec
	}
	expectProblem(t, f.do(t, http.MethodDelete, "/api/v1/config/tenants/"+v.ID.String(), adminToken(), nil, idemKey()), 428, "")
	expectProblem(t, del(adminToken(), u.RowVersion+1, nil), 409, "CONFIG-409-VERSION-CONFLICT")
	expectProblem(t, del(tenantAdminToken(v.Code), u.RowVersion, nil), 403, "")
	expectProblem(t, f.do(t, http.MethodDelete, "/api/v1/config/tenants/"+uuid.NewString(), adminToken(), nil, idemKey("If-Match", `"0"`)), 404, "")
	h := idemKey("If-Match", fmt.Sprintf(`"%d"`, u.RowVersion))
	if rec := del(adminToken(), 0, h); rec.Code != 204 {
		t.Fatalf("huỷ: %d %s", rec.Code, rec.Body.String())
	}
	if rec := del(adminToken(), 0, h); rec.Code != 204 || rec.Header().Get("Idempotency-Replayed") != "true" {
		t.Fatalf("phát lại huỷ: %d", rec.Code)
	}
	g := decode[cfgTenant](t, f.do(t, http.MethodGet, "/api/v1/config/tenants/"+v.ID.String(), adminToken(), nil, nil))
	if g.Status != "DA_CHAM_DUT" || g.Domain != "" || g.OnboardingStep != nil || len(g.StatusLog) != 2 || g.StatusLog[0].ToStatus != "DA_CHAM_DUT" {
		t.Fatalf("sau huỷ: %+v", g)
	}
	expectProblem(t, del(adminToken(), g.RowVersion, nil), 409, "CONFIG-409-TENANT-STATE")
	// Tên miền và chỗ trên cell được giải phóng: không còn gắn cell hiện hành.
	if _, err := store.CurrentTenantCell(context.Background(), f.pool, v.ID, time.Now()); err == nil {
		t.Fatal("phải đóng tenant_cell nháp")
	}
	if outboxCount(t, f, v.ID) != 3 {
		t.Fatalf("outbox: tạo, lưu nháp, huỷ = 3, có %d", outboxCount(t, f, v.ID))
	}
}

// ---------------------------------------------------------------- máy trạng thái và nhật ký

// Mọi lần đổi trạng thái ghi tenant_status_log và outbox trong cùng giao dịch; chuyển sai bị chặn và không ghi.
func TestLifecycleStatusLog(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	ctx := context.Background()
	v := mustCreate(t, f, uniq("lc"), "CHINH_QUYEN", 20000)
	svc := f.srv.Tenants
	a := tenant.Actor{ID: store.SystemActor}
	step := func(to string, wantErr bool) {
		t.Helper()
		tx, err := f.pool.Begin(ctx)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = tx.Rollback(ctx) }()
		ten, err := store.GetTenantForUpdate(ctx, tx, v.ID)
		if err != nil {
			t.Fatal(err)
		}
		before := statusLogCount(t, f, v.ID)
		err = svc.Transition(ctx, tx, a, ten, ten.RowVersion, to, "Kiểm thử "+to)
		if wantErr {
			var te *tenant.TransitionError
			if !errors.As(err, &te) {
				t.Fatalf("→ %s phải bị chặn, nhận %v", to, err)
			}
			return
		}
		if err != nil {
			t.Fatalf("→ %s: %v", to, err)
		}
		if err := tx.Commit(ctx); err != nil {
			t.Fatal(err)
		}
		if statusLogCount(t, f, v.ID) != before+1 {
			t.Fatalf("→ %s không ghi nhật ký", to)
		}
	}
	step(tenant.SanSang, true)
	step(tenant.DangCapPhat, false)
	step(tenant.KhoiTao, false) // saga bù trừ
	step(tenant.DangCapPhat, false)
	step(tenant.DangCauHinh, false)
	step(tenant.DangVanHanh, true)
	step(tenant.SanSang, false)
	step(tenant.DangVanHanh, false)
	step(tenant.DangChuyenCell, false)
	step(tenant.TamNgung, true)
	step(tenant.DangVanHanh, false)
	step(tenant.TamNgung, false)
	step(tenant.DangVanHanh, false)
	step(tenant.TamNgung, false)
	step(tenant.DaChamDut, false)
	step(tenant.DangVanHanh, true)
	var changes, logs int
	_ = f.pool.QueryRow(ctx, `SELECT count(*) FROM registry.tenant_status_log WHERE tenant_id = $1`, v.ID).Scan(&logs)
	changes = 1 + 12 // tạo + 12 lần chuyển hợp lệ
	if logs != changes || outboxCount(t, f, v.ID) != changes {
		t.Fatalf("nhật ký %d, outbox %d, mong %d", logs, outboxCount(t, f, v.ID), changes)
	}
	// Không có dòng tenant nào có trạng thái khác dòng nhật ký mới nhất (không ghi nào bỏ qua nhật ký).
	var drift int
	if err := f.pool.QueryRow(ctx, `SELECT count(*) FROM registry.tenant t WHERE t.status IS DISTINCT FROM
		(SELECT l.to_status FROM registry.tenant_status_log l WHERE l.tenant_id = t.id ORDER BY l.changed_at DESC, l.id DESC LIMIT 1)`).Scan(&drift); err != nil {
		t.Fatal(err)
	}
	if drift != 0 {
		t.Logf("có %d tenant tạo trực tiếp bằng store trong kiểm thử khác (seed), bỏ qua", drift)
	}
	// Lệch phiên bản khi chuyển: lỗi phiên bản, không ghi nhật ký.
	tx, _ := f.pool.Begin(ctx)
	defer func() { _ = tx.Rollback(ctx) }()
	ten, _ := store.GetTenantForUpdate(ctx, tx, v.ID)
	ten.Status = tenant.TamNgung
	if err := svc.Transition(ctx, tx, a, ten, ten.RowVersion+1, tenant.DaChamDut, "x"); err == nil {
		t.Fatal("phải lệch phiên bản")
	}
}

// ---------------------------------------------------------------- listConfigTenants, export, jobs

func TestListConfigTenants(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	prefix := uniq("ls")
	var made []cfgTenant
	for i := 0; i < 3; i++ {
		made = append(made, mustCreate(t, f, fmt.Sprintf("%s_%d", prefix, i), "CHINH_QUYEN", 20000))
	}
	decode[cfgTenant](t, patch(t, f, adminToken(), made[0].ID, made[0].RowVersion, `{"tenancyModel":"bridge","cellCode":"CELL-G1"}`))
	rec := f.do(t, http.MethodGet, "/api/v1/config/tenants?limit=2&q="+prefix, adminToken(), nil, nil)
	conform(t, configYAML, "/config/tenants", "get", rec)
	type page struct {
		Items []struct {
			ID             uuid.UUID
			Code, CellCode string
			OnboardingStep *int
		}
		NextCursor *string
		Total      int
		Summary    *struct {
			TenantLimit, OnboardingTenants, CellCount int
			AccountLimit                              int64
			CellLoads                                 []struct{ CellCode string }
			Onboarding                                []struct {
				TenantID uuid.UUID
				Step     int
			}
		}
	}
	p := decode[page](t, rec)
	if rec.Code != 200 || len(p.Items) != 2 || p.Total != 3 || p.NextCursor == nil || p.Summary == nil {
		t.Fatalf("trang 1: %s", rec.Body.String())
	}
	if p.Items[0].ID != made[2].ID || p.Summary.TenantLimit != 5000 || p.Summary.AccountLimit != 6_500_000 || len(p.Summary.CellLoads) != 8 || p.Summary.CellCount != 8 {
		t.Fatalf("thứ tự hoặc summary sai: %s", rec.Body.String())
	}
	found := false
	for _, o := range p.Summary.Onboarding {
		found = found || (o.TenantID == made[0].ID && o.Step == 3)
	}
	if !found {
		t.Fatal("summary.onboarding thiếu tenant đang khởi tạo (MSG-01-02)")
	}
	rec = f.do(t, http.MethodGet, "/api/v1/config/tenants?limit=2&q="+prefix+"&cursor="+*p.NextCursor, adminToken(), nil, nil)
	conform(t, configYAML, "/config/tenants", "get", rec)
	p2 := decode[page](t, rec)
	if len(p2.Items) != 1 || p2.NextCursor != nil || p2.Summary != nil || p2.Items[0].ID != made[0].ID || p2.Items[0].CellCode != "CELL-G1" {
		t.Fatalf("trang 2: %s", rec.Body.String())
	}
	// Lọc cell, mô hình, trạng thái; tìm không dấu.
	for target, want := range map[string]int{
		"cell=CELL-G1&q=" + prefix:             1,
		"model=bridge&q=" + prefix:             3,
		"model=pool&q=" + prefix:               0,
		"status=KHOI_TAO&q=" + prefix:          3,
		"status=DANG_VAN_HANH&q=" + prefix:     0,
		"q=" + strings.ToUpper("tinh+"+prefix): 3,
	} {
		rec := f.do(t, http.MethodGet, "/api/v1/config/tenants?"+target, adminToken(), nil, nil)
		if got := decode[page](t, rec).Total; rec.Code != 200 || got != want {
			t.Errorf("%s: muốn %d, nhận %d (%d)", target, want, got, rec.Code)
		}
	}
	for _, target := range []string{"?limit=0", "?limit=201", "?status=XX", "?model=BRIDGE", "?cursor=***", "?cursor=YWJj"} {
		rec := f.do(t, http.MethodGet, "/api/v1/config/tenants"+target, adminToken(), nil, nil)
		expectProblem(t, rec, 422, "")
		conform(t, configYAML, "/config/tenants", "get", rec)
	}
	rec = f.do(t, http.MethodGet, "/api/v1/config/tenants", tenantAdminToken(made[0].Code), nil, nil)
	expectProblem(t, rec, 403, "")
	conform(t, configYAML, "/config/tenants", "get", rec)
}

func TestExportConfigTenants(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	prefix := uniq("ex")
	mustCreate(t, f, prefix+"_a", "CHINH_QUYEN", 20000)
	mustCreate(t, f, prefix+"_b", "CHINH_QUYEN", 20000)
	for _, format := range []string{"XLSX", "CSV"} {
		body := []byte(`{"q":"` + prefix + `","format":"` + format + `"}`)
		if err := specs.ValidateRequest(configYAML, "/config/tenants/export", "post", "application/json", body); err != nil {
			t.Fatal(err)
		}
		rec := f.do(t, http.MethodPost, "/api/v1/config/tenants/export", adminToken(), body, idemKey())
		if rec.Code != 202 || !strings.HasPrefix(rec.Header().Get("Location"), "/api/v1/jobs/") {
			t.Fatalf("xuất: %d %s", rec.Code, rec.Body.String())
		}
		conform(t, configYAML, "/config/tenants/export", "post", rec)
		f.srv.WaitJobs()
		loc := rec.Header().Get("Location")
		jr := f.do(t, http.MethodGet, loc, adminToken(), nil, nil)
		conform(t, jobsYAML, "/jobs/{id}", "get", jr)
		j := decode[tenant.JobView](t, jr)
		if jr.Code != 200 || j.Status != "HOAN_TAT" || j.Progress != 100 || j.Type != "EXPORT" {
			t.Fatalf("tác vụ: %d %s", jr.Code, jr.Body.String())
		}
		data, err := store.JobResult(context.Background(), f.pool, uuid.MustParse(j.ID))
		if err != nil {
			t.Fatal(err)
		}
		if format == "CSV" {
			if !strings.Contains(string(data), prefix+"_a") || strings.Count(string(data), "\n") != 3 {
				t.Fatalf("CSV: %q", data)
			}
		} else if _, err := zip.NewReader(bytes.NewReader(data), int64(len(data))); err != nil {
			t.Fatalf("XLSX hỏng: %v", err)
		}
		// Người khác không thấy tác vụ (404).
		rec = f.do(t, http.MethodGet, loc, keys.PlatformAdmin(uuid.NewString()), nil, nil)
		expectProblem(t, rec, 404, "")
		conform(t, jobsYAML, "/jobs/{id}", "get", rec)
	}
	expectProblem(t, f.do(t, http.MethodGet, "/api/v1/jobs/khong-co", adminToken(), nil, nil), 404, "")
	expectProblem(t, f.do(t, http.MethodGet, "/api/v1/jobs/"+uuid.NewString(), "", nil, nil), 401, "")
	rec := f.do(t, http.MethodPost, "/api/v1/config/tenants/export", adminToken(), []byte(`{"format":"PDF"}`), idemKey())
	expectProblem(t, rec, 422, "")
	conform(t, configYAML, "/config/tenants/export", "post", rec)
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants/export", adminToken(), []byte(`{}`), map[string]string{"Content-Type": "application/json"}), 422, "")
	rec = f.do(t, http.MethodPost, "/api/v1/config/tenants/export", userToken("", "", "member"), []byte(`{}`), idemKey())
	expectProblem(t, rec, 403, "")
	conform(t, configYAML, "/config/tenants/export", "post", rec)
}

// ---------------------------------------------------------------- createRegistryTenant, getRegistryTenant

func TestRegistryTenant(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	ctx := context.Background()
	code := uniq("rg")
	body := func(code, model, cell string, max int, domain string) []byte {
		b, _ := json.Marshal(map[string]any{"code": code, "name": "Đảng uỷ " + code, "sector": "DANG", "tenancyModel": model, "cellId": cell,
			"flows": []string{"DANG"}, "maxAccounts": max, "expectedAccounts": max - 1000, "contractRef": "HĐ-2026/VTS-123", "contractValidTo": "2029-12-31",
			"domains": []map[string]string{{"domain": domain, "kind": "PRIMARY"}}})
		if err := specs.ValidateRequest(platformYAML, "/registry/tenants", "post", "application/json", b); err != nil {
			t.Fatalf("thân yêu cầu lệch hợp đồng: %v", err)
		}
		return b
	}
	const cellHCM = "0192f1a2-0000-7c00-8a00-00000000c0a1"
	const cellG1 = "0192f1a2-0000-7c00-8a00-00000000c0b1"
	rec := f.do(t, http.MethodPost, "/api/v1/registry/tenants", serviceToken(), body(code, "SILO", cellHCM, 60000, "kpi."+code+".dcs.vn"), idemKey())
	if rec.Code != 201 {
		t.Fatalf("tạo danh bạ: %d %s", rec.Code, rec.Body.String())
	}
	conform(t, platformYAML, "/registry/tenants", "post", rec)
	var rt struct {
		ID             uuid.UUID
		Status         string
		OnboardingCode string
		CurrentCell    struct {
			ID           uuid.UUID
			Code         string
			DatabaseName string
		}
		Domains []struct{ Domain string }
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &rt)
	if rt.Status != "KHOI_TAO" || rt.CurrentCell.Code != "CELL-HCM" || rt.CurrentCell.ID.String() != cellHCM || rt.CurrentCell.DatabaseName != "td_"+code ||
		len(rt.Domains) != 1 || !strings.HasPrefix(rt.OnboardingCode, "ONB-") || rec.Header().Get("Location") != "/api/v1/registry/tenants/"+rt.ID.String() {
		t.Fatalf("bản ghi danh bạ sai: %s", rec.Body.String())
	}
	if statusLogCount(t, f, rt.ID) != 1 {
		t.Fatal("thiếu nhật ký trạng thái")
	}
	var kek string
	_ = f.pool.QueryRow(ctx, `SELECT kek_ref FROM registry.tenant WHERE id = $1`, rt.ID).Scan(&kek)
	if kek != "transit/keys/t-"+code || strings.Contains(rec.Body.String(), "kek") {
		t.Fatalf("kek_ref %q hoặc lộ trong phản hồi", kek)
	}
	rec = f.do(t, http.MethodGet, "/api/v1/registry/tenants/"+rt.ID.String(), adminToken(), nil, nil)
	if rec.Code != 200 || rec.Header().Get("ETag") != `"0"` {
		t.Fatalf("xem danh bạ: %d", rec.Code)
	}
	conform(t, platformYAML, "/registry/tenants/{id}", "get", rec)
	rec = f.do(t, http.MethodGet, "/api/v1/registry/tenants/"+uuid.NewString(), adminToken(), nil, nil)
	expectProblem(t, rec, 404, "")
	conform(t, platformYAML, "/registry/tenants/{id}", "get", rec)
	expectProblem(t, f.do(t, http.MethodGet, "/api/v1/registry/tenants/"+rt.ID.String(), serviceToken(), nil, nil), 403, "")

	cases := []struct {
		name   string
		body   []byte
		status int
		code   string
	}{
		{"trùng mã", body(code, "SILO", cellHCM, 1000, "a."+code+".vn"), 409, "REGISTRY-409-DUPLICATE"},
		{"trùng tên miền", body(uniq("rg"), "SILO", cellHCM, 1000, "kpi."+code+".dcs.vn"), 409, "REGISTRY-409-DUPLICATE"},
		{"BR-01-01", body(uniq("rg"), "BRIDGE", cellG1, 1_500_000, "b."+code+".vn"), 422, "REGISTRY-422-SILO-REQUIRED"},
		{"BR-01-02", body(uniq("rg"), "BRIDGE", cellG1, 900_000, "c."+code+".vn"), 422, "REGISTRY-422-CELL-OVER-CAPACITY"},
		{"cell khác mô hình", body(uniq("rg"), "BRIDGE", cellHCM, 1000, "d."+code+".vn"), 422, "REGISTRY-422-VALIDATION"},
		{"cell không có", body(uniq("rg"), "BRIDGE", uuid.NewString(), 1000, "e."+code+".vn"), 422, "REGISTRY-422-VALIDATION"},
		{"airgap", body(uniq("rg"), "AIRGAP", cellHCM, 1000, "f."+code+".vn"), 422, "REGISTRY-422-VALIDATION"},
	}
	for _, c := range cases {
		rec := f.do(t, http.MethodPost, "/api/v1/registry/tenants", adminToken(), c.body, idemKey())
		expectProblem(t, rec, c.status, c.code)
		conform(t, platformYAML, "/registry/tenants", "post", rec)
	}
	// Cell bảo trì: 409 REGISTRY-409-CELL-UNAVAILABLE.
	if _, err := f.pool.Exec(ctx, `UPDATE registry.cell SET status = 'BAO_TRI' WHERE code = 'CELL-G1'`); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_, _ = f.pool.Exec(ctx, `UPDATE registry.cell SET status = 'HOAT_DONG' WHERE code = 'CELL-G1'`)
	}()
	rec = f.do(t, http.MethodPost, "/api/v1/registry/tenants", adminToken(), body(uniq("rg"), "BRIDGE", cellG1, 1000, "g."+code+".vn"), idemKey())
	expectProblem(t, rec, 409, "REGISTRY-409-CELL-UNAVAILABLE")
	conform(t, platformYAML, "/registry/tenants", "post", rec)
	for _, b := range []string{`{}`, `{"code":"A","name":"","sector":"X","tenancyModel":"Y","cellId":"00000000-0000-0000-0000-000000000000","flows":[],"maxAccounts":0,"expectedAccounts":-1,"onboardingCode":"","contractRef":"` + strings.Repeat("x", 101) + `","domains":[{"domain":"x","kind":"Z"},{"domain":"a.vn","kind":"PRIMARY"},{"domain":"b.vn","kind":"PRIMARY"}]}`} {
		expectProblem(t, f.do(t, http.MethodPost, "/api/v1/registry/tenants", adminToken(), []byte(b), idemKey()), 422, "REGISTRY-422-VALIDATION")
	}
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/registry/tenants", userToken("", "", "tenant_admin"), []byte(`{}`), idemKey()), 403, "")
}

// ---------------------------------------------------------------- đồng bộ account_count

type fakeSource struct {
	counts map[string]map[uuid.UUID]int
	fail   map[string]bool
}

func (s fakeSource) AccountCounts(_ context.Context, c store.Cell) (map[uuid.UUID]int, error) {
	if s.fail[c.Code] {
		return nil, fmt.Errorf("cell %s không phản hồi", c.Code)
	}
	return s.counts[c.Code], nil
}

func TestSyncAccounts(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	ctx := context.Background()
	a := seedResolvable(t, f.pool, uniq("sy"), "CELL-G2", "DANG_VAN_HANH")
	b := seedResolvable(t, f.pool, uniq("sy"), "CELL-G3", "DANG_VAN_HANH")
	src := fakeSource{counts: map[string]map[uuid.UUID]int{
		"CELL-G2": {a.ID: 51900, b.ID: 7, uuid.New(): 5}, // b không thuộc CELL-G2: bỏ qua
		"CELL-G3": {b.ID: -1},
	}, fail: map[string]bool{"CELL-SH": true}}
	res, err := f.srv.Tenants.SyncAccounts(ctx, f.pool, src)
	if err != nil {
		t.Fatal(err)
	}
	if res.Updated != 1 || len(res.Failed) != 1 || res.Failed[0] != "CELL-SH" || res.Skipped {
		t.Fatalf("kết quả đồng bộ: %+v", res)
	}
	ga, _ := store.GetTenant(ctx, f.pool, a.ID)
	gb, _ := store.GetTenant(ctx, f.pool, b.ID)
	if ga.AccountCount != 51900 || ga.RowVersion != a.RowVersion || gb.AccountCount != b.AccountCount {
		t.Fatalf("account_count sai: %d/%d, row_version %d", ga.AccountCount, gb.AccountCount, ga.RowVersion)
	}
	// Bản sao khác đang giữ khoá: bỏ lượt.
	conn, err := f.pool.Acquire(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, `SELECT pg_advisory_lock($1)`, 0x5f03_0001); err != nil {
		t.Fatal(err)
	}
	res, err = f.srv.Tenants.SyncAccounts(ctx, f.pool, src)
	if err != nil || !res.Skipped {
		t.Fatalf("phải bỏ lượt khi khoá đang giữ: %+v %v", res, err)
	}
	_, _ = conn.Exec(ctx, `SELECT pg_advisory_unlock($1)`, 0x5f03_0001)
	// Chạy định kỳ một lượt rồi dừng.
	cctx, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() { f.srv.Tenants.RunPeriodic(cctx, f.pool, src, time.Hour, slogDiscard()); close(done) }()
	time.Sleep(200 * time.Millisecond)
	cancel()
	<-done
}
