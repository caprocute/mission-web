package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/platform/auth/authtest"
	"mission/services-go/tenant-registry/internal/provision"
	"mission/services-go/tenant-registry/internal/provision/provisiontest"
	"mission/services-go/tenant-registry/internal/resolve"
	"mission/services-go/tenant-registry/internal/store"
)

// DV-03-05: provisionConfigTenant và activateConfigTenant qua HTTP, đối chiếu hợp đồng config.yaml. Keycloak dùng
// bản trong bộ nhớ (kiểm Keycloak thật ở gói provision); hệ còn lại là máy chủ giả.

type sagaFixture struct {
	*fixture
	cell *provisiontest.Cell
	kc   *provisiontest.Keycloak
	saga *provision.Saga
}

func newSagaFixture(t *testing.T) *sagaFixture {
	t.Helper()
	cell := provisiontest.NewCell()
	t.Cleanup(cell.Close)
	kc := provisiontest.NewKeycloak()
	var saga *provision.Saga
	f := newFixture(t, db.SvcDSN, func(d *apiDeps, _ *resolve.Options) {
		res, dns, certs, core, org, lag := cell.SagaDeps(nil, cell.URL(), "")
		saga = &provision.Saga{Pool: d.Pool, Tenants: d.Tenants, Log: d.Log, Endpoints: res, Keycloak: kc, DNS: dns, Certs: certs,
			Core: core, Org: org, Lag: lag, MaxAttempts: 3, Backoff: 5 * time.Millisecond}
		d.Provision = saga
	})
	return &sagaFixture{fixture: f, cell: cell, kc: kc, saga: saga}
}

const bindingsBody = `{"tenancyModel":"bridge","policyBindings":[{"flow":"DANG","packVersionId":"0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a70"},{"flow":"NHA_NUOC","packVersionId":"0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a71"}],"uiPackVersionId":"0192e2d5-6e7f-7a81-8b2c-3d4e5f6a7b80"}`

// draft tạo tenant bridge, gắn gói ở bước 3 và khai IdP ở bước 4 (quản trị tenant).
func (f *sagaFixture) draft(t *testing.T) cfgTenant {
	t.Helper()
	v := mustCreate(t, f.fixture, uniq("cp"), "CHINH_QUYEN", 30000)
	rec := patch(t, f.fixture, adminToken(), v.ID, v.RowVersion, bindingsBody)
	if rec.Code != 200 {
		t.Fatalf("bước 3: %d %s", rec.Code, rec.Body.String())
	}
	v = decode[cfgTenant](t, rec)
	idps := fmt.Sprintf(`{"idps":[{"idpAlias":"sso","protocol":"OIDC","issuer":"https://sso.%s.gov.vn","emailDomains":["%s.gov.vn"]}]}`, v.Code, v.Code)
	rec = patch(t, f.fixture, tenantAdminToken(v.Code), v.ID, v.RowVersion, idps)
	if rec.Code != 200 {
		t.Fatalf("bước 4: %d %s", rec.Code, rec.Body.String())
	}
	return decode[cfgTenant](t, rec)
}

func (f *sagaFixture) provision(t *testing.T, tok string, id uuid.UUID, version int) *httpRec {
	t.Helper()
	rec := f.do(t, http.MethodPost, "/api/v1/config/tenants/"+id.String()+"/provision", tok, nil,
		map[string]string{"Idempotency-Key": uuid.NewString(), "If-Match": fmt.Sprintf(`"%d"`, version)})
	conform(t, configYAML, "/config/tenants/{id}/provision", "post", rec)
	return rec
}

func (f *sagaFixture) activate(t *testing.T, tok string, id uuid.UUID, version int, key string) *httpRec {
	t.Helper()
	if key == "" {
		key = uuid.NewString()
	}
	body := []byte(`{"reason":"Quyết định đưa vào vận hành số 12/QĐ"}`)
	if err := specs.ValidateRequest(configYAML, "/config/tenants/{id}/activate", "post", "application/json", body); err != nil {
		t.Fatalf("thân yêu cầu lệch hợp đồng: %v", err)
	}
	rec := f.do(t, http.MethodPost, "/api/v1/config/tenants/"+id.String()+"/activate", tok, body,
		map[string]string{"Idempotency-Key": key, "If-Match": fmt.Sprintf(`"%d"`, version), "Content-Type": "application/json"})
	conform(t, configYAML, "/config/tenants/{id}/activate", "post", rec)
	return rec
}

func (f *sagaFixture) suspend(t *testing.T, tok string, id uuid.UUID, version int, body string) *httpRec {
	t.Helper()
	rec := f.do(t, http.MethodPost, "/api/v1/config/tenants/"+id.String()+"/suspend", tok, []byte(body),
		map[string]string{"Idempotency-Key": uuid.NewString(), "If-Match": fmt.Sprintf(`"%d"`, version), "Content-Type": "application/json"})
	conform(t, configYAML, "/config/tenants/{id}/suspend", "post", rec)
	return rec
}

func (f *sagaFixture) get(t *testing.T, id uuid.UUID) cfgTenant {
	t.Helper()
	rec := f.do(t, http.MethodGet, "/api/v1/config/tenants/"+id.String(), adminToken(), nil, nil)
	if rec.Code != 200 {
		t.Fatalf("đọc tenant: %d", rec.Code)
	}
	conform(t, configYAML, "/config/tenants/{id}", "get", rec)
	return decode[cfgTenant](t, rec)
}

// markIdpTested ghi kết quả kiểm kết nối thành công (như DV-03-06 ghi sau testConfigTenantIdp).
func markIdpTested(t *testing.T, pool *pgxpool.Pool, id uuid.UUID) {
	t.Helper()
	ctx := context.Background()
	m, err := store.LoadIdpMeta(ctx, pool, id)
	if err != nil {
		t.Fatal(err)
	}
	ok, now := true, time.Now()
	x := m["sso"]
	x.LastTestOk, x.LastTestedAt = &ok, &now
	m["sso"] = x
	if err := store.SaveIdpMeta(ctx, pool, id, m, store.SystemActor); err != nil {
		t.Fatal(err)
	}
}

func aal1Admin() string {
	return keys.Token(authtest.Claims{Subject: "0192f1a2-3333-7000-8000-000000000003", Roles: []string{"platform_admin"}, ACR: "aal1", AuthTime: time.Now()})
}

func TestProvisionAndActivate(t *testing.T) {
	f := newSagaFixture(t)
	v := f.draft(t)

	// Thiếu If-Match 428; không có AAL2 401; quản trị tenant 403.
	rec := f.do(t, http.MethodPost, "/api/v1/config/tenants/"+v.ID.String()+"/provision", adminToken(), nil,
		map[string]string{"Idempotency-Key": uuid.NewString()})
	expectProblem(t, rec, 428, "COMMON-428-IF-MATCH-REQUIRED")
	expectProblem(t, f.provision(t, aal1Admin(), v.ID, v.RowVersion), 401, "COMMON-401-STEP-UP")
	expectProblem(t, f.provision(t, tenantAdminToken(v.Code), v.ID, v.RowVersion), 403, "")
	expectProblem(t, f.provision(t, adminToken(), v.ID, v.RowVersion+3), 409, "CONFIG-409-VERSION-CONFLICT")

	// 202 + Location job PROVISION; saga chạy nền tới DANG_CAU_HINH.
	start := time.Now()
	rec = f.provision(t, adminToken(), v.ID, v.RowVersion)
	if rec.Code != http.StatusAccepted || !strings.HasPrefix(rec.Header().Get("Location"), "/api/v1/jobs/") {
		t.Fatalf("provision: %d %s %v", rec.Code, rec.Body.String(), rec.Header())
	}
	job := decode[struct {
		ID, Type, Status string
	}](t, rec)
	if job.Type != "PROVISION" || job.Status != "CHO" {
		t.Fatalf("job: %+v", job)
	}
	f.srv.WaitSagas()
	t.Logf("saga cấp phát (hệ giả): %s", time.Since(start))
	rec = f.do(t, http.MethodGet, rec.Header().Get("Location"), adminToken(), nil, nil)
	conform(t, jobsYAML, "/jobs/{id}", "get", rec)
	j := decode[struct{ Status, Message string }](t, rec)
	if j.Status != "HOAN_TAT" || !strings.HasPrefix(j.Message, "Cấp phát tenant thành công trong") {
		t.Fatalf("job sau saga: %+v", j)
	}
	g := f.get(t, v.ID)
	if g.Status != "DANG_CAU_HINH" || g.OnboardingStep == nil || *g.OnboardingStep != 5 {
		t.Fatalf("tenant sau saga: %+v", g)
	}
	var pj struct {
		ProvisionJobID string `json:"provisionJobId"`
	}
	_ = json.Unmarshal(f.do(t, http.MethodGet, "/api/v1/config/tenants/"+v.ID.String(), adminToken(), nil, nil).Body.Bytes(), &pj)
	if pj.ProvisionJobID != job.ID {
		t.Fatalf("provisionJobId: %q / %q", pj.ProvisionJobID, job.ID)
	}
	// Đang cấu hình thì không cấp phát lại.
	expectProblem(t, f.provision(t, adminToken(), v.ID, g.RowVersion), 409, "CONFIG-409-TENANT-STATE")

	// Kích hoạt: không có AAL2 → 401; chưa kiểm IdP, chưa có cây tổ chức → 422 chưa đạt kiểm tra tổng thể.
	expectProblem(t, f.activate(t, aal1Admin(), v.ID, g.RowVersion, ""), 401, "COMMON-401-STEP-UP")
	p := expectProblem(t, f.activate(t, adminToken(), v.ID, g.RowVersion, ""), 422, "CONFIG-422-READINESS-FAILED")
	fields := map[string]bool{}
	for _, e := range p.Errors {
		fields[e.Field] = true
	}
	if !fields["readinessChecks.IDP"] || !fields["readinessChecks.ORG_TREE"] || fields["readinessChecks.TLS"] || fields["readinessChecks.DOMAIN"] {
		t.Fatalf("mục chưa đạt sai: %+v", p.Errors)
	}

	// Còn lệch mức Cao → 422 CONFIG-422-HIGH-DISCREPANCY, MSG-01-12 (BR-01-10).
	markIdpTested(t, f.pool, v.ID)
	f.cell.Lock()
	f.cell.Trees[v.ID], f.cell.HighDisc[v.ID] = true, 3
	f.cell.Unlock()
	p = expectProblem(t, f.activate(t, adminToken(), v.ID, g.RowVersion, ""), 422, "CONFIG-422-HIGH-DISCREPANCY")
	if p.Msg != "MSG-01-12" || p.Detail != "Còn 3 mục lệch mức Cao. Tenant chưa thể kích hoạt." || p.Type != "https://okrkpi.vn/problems/br-01-10" {
		t.Fatalf("BR-01-10: %+v", p)
	}
	// Bản sao dự phòng trễ quá RPO 15 phút → chặn.
	f.cell.Lock()
	f.cell.HighDisc[v.ID], f.cell.LagSec = 0, 1200
	f.cell.Unlock()
	expectProblem(t, f.activate(t, adminToken(), v.ID, g.RowVersion, ""), 422, "CONFIG-422-RPO-EXCEEDED")
	if s := f.get(t, v.ID).Status; s != "DANG_CAU_HINH" {
		t.Fatalf("bị chặn thì không đổi trạng thái: %s", s)
	}

	// Trễ dưới ngưỡng: kích hoạt được, có cảnh báo trong nhật ký; DANG_CAU_HINH → SAN_SANG → DANG_VAN_HANH.
	f.cell.Lock()
	f.cell.LagSec = 4
	f.cell.Unlock()
	key := uuid.NewString()
	rec = f.activate(t, adminToken(), v.ID, g.RowVersion, key)
	if rec.Code != 200 {
		t.Fatalf("kích hoạt: %d %s", rec.Code, rec.Body.String())
	}
	var act struct {
		Status          string `json:"status"`
		RowVersion      int    `json:"rowVersion"`
		ReadinessChecks []struct {
			Code   string `json:"code"`
			Passed bool   `json:"passed"`
		} `json:"readinessChecks"`
		StatusLog []struct {
			ToStatus string `json:"toStatus"`
			Reason   string `json:"reason"`
		} `json:"statusLog"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &act)
	if act.Status != "DANG_VAN_HANH" || len(act.ReadinessChecks) != 7 || rec.Header().Get("ETag") != fmt.Sprintf(`"%d"`, act.RowVersion) {
		t.Fatalf("sau kích hoạt: %s", rec.Body.String())
	}
	for _, c := range act.ReadinessChecks {
		if !c.Passed {
			t.Fatalf("mục %s chưa đạt", c.Code)
		}
	}
	if act.StatusLog[0].ToStatus != "DANG_VAN_HANH" || act.StatusLog[1].ToStatus != "SAN_SANG" ||
		!strings.Contains(act.StatusLog[0].Reason, "12/QĐ") || !strings.Contains(act.StatusLog[0].Reason, "trễ 4 giây") {
		t.Fatalf("nhật ký trạng thái: %+v", act.StatusLog[:2])
	}
	// Gửi lại cùng khoá: trả lại phản hồi đã lưu, không chuyển trạng thái lần nữa.
	rec = f.activate(t, adminToken(), v.ID, g.RowVersion, key)
	if rec.Code != 200 || rec.Header().Get("Idempotency-Replayed") != "true" {
		t.Fatalf("gửi lại: %d %v", rec.Code, rec.Header())
	}
	// Đã vận hành: kích hoạt lại sai trạng thái → 409.
	expectProblem(t, f.activate(t, adminToken(), v.ID, act.RowVersion, ""), 409, "CONFIG-409-TENANT-STATE")
	// EVT-21: thư cấp phát xong và thư kích hoạt cho đầu mối.
	if n := outboxTopic(t, v.ID, "notify.message.requested.v1"); n != 2 {
		t.Fatalf("EVT-21: %d", n)
	}

	// Tạm ngừng (INF-26): lý do bắt buộc, AAL2, chỉ quản trị nền tảng, chỉ từ DANG_VAN_HANH; vận hành lại bằng activate.
	const lyDo = `{"reason":"Hết hạn hợp đồng số 45/HĐ"}`
	body := []byte(lyDo)
	if err := specs.ValidateRequest(configYAML, "/config/tenants/{id}/suspend", "post", "application/json", body); err != nil {
		t.Fatalf("thân yêu cầu lệch hợp đồng: %v", err)
	}
	expectProblem(t, f.suspend(t, adminToken(), v.ID, act.RowVersion, `{"reason":"  "}`), 422, "")
	expectProblem(t, f.suspend(t, aal1Admin(), v.ID, act.RowVersion, lyDo), 401, "COMMON-401-STEP-UP")
	expectProblem(t, f.suspend(t, tenantAdminToken(v.Code), v.ID, act.RowVersion, lyDo), 403, "")
	expectProblem(t, f.suspend(t, adminToken(), v.ID, act.RowVersion+1, lyDo), 409, "CONFIG-409-VERSION-CONFLICT")
	before := outboxTopic(t, v.ID, "registry.tenant.changed.v1")
	rec = f.suspend(t, adminToken(), v.ID, act.RowVersion, lyDo)
	if rec.Code != 200 {
		t.Fatalf("tạm ngừng: %d %s", rec.Code, rec.Body.String())
	}
	var sus struct {
		Status     string `json:"status"`
		RowVersion int    `json:"rowVersion"`
		StatusLog  []struct {
			ToStatus string `json:"toStatus"`
			Reason   string `json:"reason"`
		} `json:"statusLog"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &sus)
	if sus.Status != "TAM_NGUNG" || sus.StatusLog[0].ToStatus != "TAM_NGUNG" || !strings.Contains(sus.StatusLog[0].Reason, "45/HĐ") ||
		rec.Header().Get("ETag") != fmt.Sprintf(`"%d"`, sus.RowVersion) {
		t.Fatalf("sau tạm ngừng: %s", rec.Body.String())
	}
	if n := outboxTopic(t, v.ID, "registry.tenant.changed.v1"); n != before+1 {
		t.Fatalf("phải phát đúng một registry.tenant.changed.v1, có %d", n-before)
	}
	expectProblem(t, f.suspend(t, adminToken(), v.ID, sus.RowVersion, lyDo), 409, "CONFIG-409-TENANT-STATE")
	if rec = f.activate(t, adminToken(), v.ID, sus.RowVersion, ""); rec.Code != 200 || decode[cfgTenant](t, rec).Status != "DANG_VAN_HANH" {
		t.Fatalf("vận hành lại sau tạm ngừng: %d %s", rec.Code, rec.Body.String())
	}
}

func outboxTopic(t *testing.T, tid uuid.UUID, topic string) int {
	t.Helper()
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, db.AdminDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	var n int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM registry.outbox_event WHERE tenant_id = $1 AND topic = $2`, tid, topic).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestProvisionValidation(t *testing.T) {
	f := newSagaFixture(t)
	// Thiếu gói của một luồng (BR-01-04).
	v := mustCreate(t, f.fixture, uniq("tv"), "CHINH_QUYEN", 30000)
	rec := patch(t, f.fixture, adminToken(), v.ID, v.RowVersion, `{"tenancyModel":"bridge"}`)
	v = decode[cfgTenant](t, rec)
	expectProblem(t, f.provision(t, adminToken(), v.ID, v.RowVersion), 422, "CONFIG-422-FLOW-PACK-MISSING")
	if s := f.get(t, v.ID).Status; s != "KHOI_TAO" {
		t.Fatalf("không được đổi trạng thái: %s", s)
	}
	// Saga chưa cấu hình: 503.
	f2 := newFixture(t, db.SvcDSN)
	rec = f2.do(t, http.MethodPost, "/api/v1/config/tenants/"+v.ID.String()+"/provision", adminToken(), nil,
		map[string]string{"Idempotency-Key": uuid.NewString(), "If-Match": `"1"`})
	expectProblem(t, rec, 503, "")
}

// TestPromoteReady: DANG_CAU_HINH tự sang SAN_SANG khi IdP hoạt động, có cây tổ chức, không còn lệch mức Cao;
// sau đó kích hoạt từ SAN_SANG.
func TestPromoteReady(t *testing.T) {
	f := newSagaFixture(t)
	v := f.draft(t)
	if rec := f.provision(t, adminToken(), v.ID, v.RowVersion); rec.Code != 202 {
		t.Fatalf("provision: %d %s", rec.Code, rec.Body.String())
	}
	f.srv.WaitSagas()
	ctx := context.Background()
	if _, err := f.saga.PromoteReady(ctx); err != nil {
		t.Fatal(err)
	}
	if s := f.get(t, v.ID).Status; s != "DANG_CAU_HINH" {
		t.Fatalf("chưa đủ điều kiện mà đã chuyển: %s", s)
	}
	markIdpTested(t, f.pool, v.ID)
	f.cell.Lock()
	f.cell.Trees[v.ID] = true
	f.cell.Unlock()
	if _, err := f.saga.PromoteReady(ctx); err != nil {
		t.Fatal(err)
	}
	g := f.get(t, v.ID)
	if g.Status != "SAN_SANG" || g.OnboardingStep == nil || *g.OnboardingStep != 6 {
		t.Fatalf("phải SAN_SANG bước 6: %+v", g)
	}
	rec := f.activate(t, adminToken(), v.ID, g.RowVersion, "")
	if rec.Code != 200 || decode[cfgTenant](t, rec).Status != "DANG_VAN_HANH" {
		t.Fatalf("kích hoạt từ SAN_SANG: %d %s", rec.Code, rec.Body.String())
	}
}

type httpRec = httptest.ResponseRecorder
