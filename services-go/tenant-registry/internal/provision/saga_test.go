package provision_test

import (
	"context"
	"encoding/json"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/platform/crypto"
	"mission/services-go/tenant-registry/internal/platform/dbx"
	"mission/services-go/tenant-registry/internal/provision"
	"mission/services-go/tenant-registry/internal/provision/provisiontest"
	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
	"mission/services-go/tenant-registry/internal/testdb"
)

var admin = tenant.Actor{ID: uuid.MustParse("0192f1a2-3333-7000-8000-000000000003"), PlatformAdmin: true}

type env struct {
	saga  *provision.Saga
	cell  *provisiontest.Cell
	pool  *pgxpool.Pool
	admin *pgxpool.Pool
}

// newEnv dựng saga với Keycloak thật và hệ giả cho phần còn lại.
func newEnv(t *testing.T) *env {
	t.Helper()
	ctx := context.Background()
	cell := provisiontest.NewCell()
	t.Cleanup(cell.Close)
	enc, err := crypto.NewAESGCM([]byte(testdb.RandomSecret()[:32]))
	if err != nil {
		t.Fatal(err)
	}
	pool := testdb.Pool(ctx, t, db.SvcDSN)
	res, dns, certs, core, org, lag := cell.SagaDeps(kc.tokens, kc.baseURL, kc.tokenURL)
	s := &provision.Saga{Pool: pool, Tenants: tenant.NewService(tenant.DefaultRules(), enc), Log: testLog(), Endpoints: res,
		Keycloak: &provision.KeycloakAdmin{Tokens: kc.tokens}, DNS: dns, Certs: certs, Core: core, Org: org, Lag: lag,
		MaxAttempts: 3, Backoff: 10 * time.Millisecond, StepTimeout: 30 * time.Second}
	return &env{saga: s, cell: cell, pool: pool, admin: testdb.Pool(ctx, t, db.AdminDSN)}
}

func uniq(p string) string { return p + "_" + strings.ReplaceAll(uuid.NewString()[:8], "-", "") }

// seed tạo tenant bridge đi hết bước 1–3, khai một IdP OIDC đã kiểm kết nối, rồi khởi chạy cấp phát.
func (e *env) seed(t *testing.T, code string) (uuid.UUID, uuid.UUID) {
	t.Helper()
	return e.seedModel(t, code, "bridge")
}

// seedModel như seed nhưng chọn mô hình thuê bao (bridge, pool, silo).
func (e *env) seedModel(t *testing.T, code, model string) (uuid.UUID, uuid.UUID) {
	t.Helper()
	ctx := context.Background()
	var tid, jid uuid.UUID
	err := dbx.InTx(ctx, e.pool, func(tx pgx.Tx) error {
		exp, units := 30000, 300
		v, err := e.saga.Tenants.Create(ctx, tx, admin, tenant.CreateInput{Code: code, Name: "Tỉnh " + code, Sector: "CHINH_QUYEN",
			ContractRef: "HĐ-" + code, Flows: []string{"DANG", "NHA_NUOC"}, ExpectedAccounts: &exp, ExpectedUnits: &units,
			AdminContact: &tenant.AdminContact{FullName: "Trần Thị Hồng", Email: "quantri@" + strings.ReplaceAll(code, "_", "-") + ".gov.vn"}})
		if err != nil {
			return err
		}
		tid = v.ID
		patch := map[string]json.RawMessage{
			"tenancyModel":    json.RawMessage(`"` + model + `"`),
			"policyBindings":  json.RawMessage(`[{"flow":"DANG","packVersionId":"0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a70"},{"flow":"NHA_NUOC","packVersionId":"0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a71"}]`),
			"uiPackVersionId": json.RawMessage(`"0192e2d5-6e7f-7a81-8b2c-3d4e5f6a7b80"`),
		}
		v, err = e.saga.Tenants.Patch(ctx, tx, admin, tid, v.RowVersion, patch)
		if err != nil {
			return err
		}
		if err := store.InsertIdp(ctx, tx, &store.Idp{TenantID: tid, IdpAlias: "sso", Protocol: "OIDC", Issuer: "https://sso." + code + ".gov.vn",
			EmailDomains: []string{strings.ReplaceAll(code, "_", "-") + ".gov.vn"}}, admin.ID); err != nil {
			return err
		}
		ok := true
		now := time.Now()
		if err := store.SaveIdpMeta(ctx, tx, tid, map[string]store.IdpMeta{"sso": {ClientID: "mission", LastTestOk: &ok, LastTestedAt: &now}}, admin.ID); err != nil {
			return err
		}
		j, err := e.saga.Tenants.StartProvision(ctx, tx, admin, tid, v.RowVersion)
		if err != nil {
			return err
		}
		jid = j.ID
		return nil
	})
	if err != nil {
		t.Fatalf("chuẩn bị tenant: %v", err)
	}
	return tid, jid
}

func (e *env) tenant(t *testing.T, id uuid.UUID) *store.Tenant {
	t.Helper()
	x, err := store.GetTenant(context.Background(), e.pool, id)
	if err != nil {
		t.Fatal(err)
	}
	return x
}

func (e *env) job(t *testing.T, id uuid.UUID) (*store.Job, tenant.ProvisionState) {
	t.Helper()
	j, err := store.GetJob(context.Background(), e.pool, id)
	if err != nil {
		t.Fatal(err)
	}
	var st tenant.ProvisionState
	if err := json.Unmarshal(j.Params, &st); err != nil {
		t.Fatal(err)
	}
	return j, st
}

func (e *env) outbox(t *testing.T, tid uuid.UUID, topic string) []map[string]any {
	t.Helper()
	rows, err := e.admin.Query(context.Background(), `SELECT payload FROM registry.outbox_event WHERE tenant_id = $1 AND topic = $2 ORDER BY created_at`, tid, topic)
	if err != nil {
		t.Fatal(err)
	}
	ps, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (map[string]any, error) {
		var b []byte
		var m map[string]any
		if err := r.Scan(&b); err != nil {
			return nil, err
		}
		return m, json.Unmarshal(b, &m)
	})
	if err != nil {
		t.Fatal(err)
	}
	return ps
}

func orgCount(t *testing.T, code string) int {
	t.Helper()
	st, body := kcGet(t, "/organizations", url.Values{"search": {"Tỉnh " + code}, "exact": {"true"}})
	if st != 200 {
		t.Fatalf("đọc Organization: %d %s", st, body)
	}
	var orgs []map[string]any
	_ = json.Unmarshal([]byte(body), &orgs)
	return len(orgs)
}

func userCount(t *testing.T, code string) int {
	t.Helper()
	_, body := kcGet(t, "/users", url.Values{"email": {"quantri@" + strings.ReplaceAll(code, "_", "-") + ".gov.vn"}, "exact": {"true"}})
	var us []map[string]any
	_ = json.Unmarshal([]byte(body), &us)
	return len(us)
}

// TestSagaEndToEnd: tenant bridge đi từ KHOI_TAO tới DANG_CAU_HINH; ghi lại thời gian (tiêu chí ≤ 5 phút).
func TestSagaEndToEnd(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	code := uniq("e2e")
	start := time.Now()
	tid, jid := e.seed(t, code)
	if s := e.tenant(t, tid).Status; s != tenant.DangCapPhat {
		t.Fatalf("sau khi khởi chạy phải DANG_CAP_PHAT, đang %s", s)
	}
	if err := e.saga.Execute(ctx, jid); err != nil {
		t.Fatal(err)
	}
	elapsed := time.Since(start)
	t.Logf("thời gian KHOI_TAO → DANG_CAU_HINH: %s", elapsed)
	if elapsed > 5*time.Minute {
		t.Fatalf("quá ngân sách 5 phút: %s", elapsed)
	}
	if s := e.tenant(t, tid).Status; s != tenant.DangCauHinh {
		t.Fatalf("phải DANG_CAU_HINH, đang %s", s)
	}
	j, st := e.job(t, jid)
	if j.Status != "HOAN_TAT" || j.Progress != 100 || !strings.HasPrefix(*j.Message, "Cấp phát tenant thành công trong") || st.Phase != tenant.PhaseDone {
		t.Fatalf("tác vụ: %+v %+v", j, st)
	}
	for _, s := range st.Steps {
		if s.Status != tenant.StepDone {
			t.Fatalf("bước %s chưa xong: %+v", s.Name, s)
		}
	}
	// Keycloak thật: Organization, IdP liên kết đã gắn, tài khoản đầu mối là thành viên.
	if orgCount(t, code) != 1 || userCount(t, code) != 1 {
		t.Fatal("thiếu Organization hoặc tài khoản đầu mối trong Keycloak")
	}
	orgID := st.Steps[0].Out["orgId"]
	_, body := kcGet(t, "/organizations/"+orgID+"/identity-providers", nil)
	if !strings.Contains(body, provision.KeycloakAlias(codeOf(e, t, tid), "sso")) {
		t.Fatalf("IdP chưa gắn vào Organization: %s", body)
	}
	_, body = kcGet(t, "/organizations/"+orgID+"/members", nil)
	if !strings.Contains(body, st.Steps[0].Out["adminUserId"]) {
		t.Fatalf("đầu mối chưa là thành viên: %s", body)
	}
	// Tên miền, chứng chỉ, gói, lịch, hạn mức.
	ds, _ := store.ListDomains(ctx, e.pool, tid)
	if len(ds) != 1 || ds[0].VerifiedAt == nil || ds[0].TLSCertRef == nil || *ds[0].TLSCertRef != "k8s:apisix/"+provision.CertName(code) {
		t.Fatalf("tên miền: %+v", ds)
	}
	e.cell.Lock()
	cname := e.cell.DNS[ds[0].Domain+"."]
	packs, ui, cal := len(e.cell.Packs[tid]), e.cell.UiPacks[tid], e.cell.Calendars[tid]
	e.cell.Unlock()
	if !strings.HasPrefix(cname, "api.cell-g") || !strings.HasSuffix(cname, ".okrkpi.vn.") {
		t.Fatalf("CNAME sai: %q", cname)
	}
	if packs != 2 || !ui || cal != time.Now().Year() {
		t.Fatalf("gói/lịch: %d %v %d", packs, ui, cal)
	}
	qs, _ := store.ListQuotas(ctx, e.pool, tid)
	if len(qs) != 2 || qs[0].Key != "ACCOUNTS" || qs[0].Limit != 30000 || qs[1].Key != "API_RPS" || qs[1].Limit != 600 {
		t.Fatalf("hạn mức: %+v", qs)
	}
	// Outbox: sys.tenant.provisioned.v1 và EVT-21 không chứa C2.
	prov := e.outbox(t, tid, "sys.tenant.provisioned.v1")
	notif := e.outbox(t, tid, "notify.message.requested.v1")
	if len(prov) != 1 || prov[0]["tenantCode"] != code || len(notif) != 1 {
		t.Fatalf("outbox: %v %v", prov, notif)
	}
	raw, _ := json.Marshal(notif[0])
	if strings.Contains(string(raw), "quantri@") || strings.Contains(string(raw), "Trần Thị Hồng") {
		t.Fatalf("EVT-21 lộ dữ liệu C2: %s", raw)
	}
	// Chạy lại saga đã xong: không làm gì.
	if err := e.saga.Execute(ctx, jid); err != nil {
		t.Fatal(err)
	}
	if orgCount(t, code) != 1 {
		t.Fatal("chạy lại tạo thêm Organization")
	}
}

func codeOf(e *env, t *testing.T, id uuid.UUID) string { return e.tenant(t, id).Code }

// flakyDNS trả CNAME sai từ lần đọc thứ failAt (lỗi ở bước kiểm thử khói, bước cuối).
type flakyDNS struct {
	provision.DNS
	n, failAt int32
}

func (f *flakyDNS) LookupCNAME(ctx context.Context, name string) (string, error) {
	if atomic.AddInt32(&f.n, 1) >= f.failAt {
		return "noi-khac.example.vn", nil
	}
	return f.DNS.LookupCNAME(ctx, name)
}

// TestSagaCompensateFromLastStep: bước cuối (kiểm thử khói) lỗi sau 3 lần thử → bù trừ toàn bộ, tenant về KHOI_TAO.
func TestSagaCompensateFromLastStep(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	e.saga.DNS = &flakyDNS{DNS: e.saga.DNS, failAt: 2}
	code := uniq("bt")
	tid, jid := e.seed(t, code)
	if err := e.saga.Execute(ctx, jid); err != nil {
		t.Fatal(err)
	}
	x := e.tenant(t, tid)
	if x.Status != tenant.KhoiTao {
		t.Fatalf("phải về KHOI_TAO, đang %s", x.Status)
	}
	j, st := e.job(t, jid)
	if j.Status != "THAT_BAI" || st.Phase != tenant.PhaseCompensated || st.FailedStep != provision.StepSmoke {
		t.Fatalf("tác vụ: %s %+v", j.Status, st)
	}
	if !strings.HasPrefix(*j.Message, "Cấp phát lỗi ở bước kiểm thử khói:") || !strings.HasSuffix(*j.Message, "Hệ thống đã hoàn tác các bước trước.") {
		t.Fatalf("MSG-01-07 sai: %s", *j.Message)
	}
	var prob map[string]any
	_ = json.Unmarshal(j.Problem, &prob)
	if prob["msg"] != "MSG-01-07" || prob["code"] != "CONFIG-502-PROVISION-FAILED" {
		t.Fatalf("problem: %v", prob)
	}
	smoke := st.Steps[len(st.Steps)-1]
	if smoke.Attempts != 3 {
		t.Fatalf("bước cuối phải thử 3 lần: %+v", smoke)
	}
	for _, s := range st.Steps {
		if s.Status != tenant.StepCompensated {
			t.Fatalf("bước %s chưa bù trừ: %+v", s.Name, s)
		}
	}
	// Không còn tài nguyên treo.
	if orgCount(t, code) != 0 || userCount(t, code) != 0 {
		t.Fatal("Organization hoặc tài khoản còn trong Keycloak")
	}
	if st, _ := kcGet(t, "/identity-provider/instances/"+provision.KeycloakAlias(code, "sso"), nil); st != 404 {
		t.Fatalf("IdP còn trong realm: %d", st)
	}
	e.cell.Lock()
	left := len(e.cell.DNS) + len(e.cell.Certs) + len(e.cell.Secrets) + len(e.cell.Packs) + len(e.cell.UiPacks) + len(e.cell.Calendars)
	e.cell.Unlock()
	if left != 0 {
		t.Fatalf("còn tài nguyên trên cell giả: %d", left)
	}
	ds, _ := store.ListDomains(ctx, e.pool, tid)
	if len(ds) != 1 || ds[0].VerifiedAt != nil || ds[0].TLSCertRef != nil {
		t.Fatalf("tên miền chưa thu hồi: %+v", ds)
	}
	qs, _ := store.ListQuotas(ctx, e.pool, tid)
	for _, q := range qs {
		if q.EffectiveTo == nil {
			t.Fatalf("hạn mức chưa đóng: %+v", q)
		}
	}
	logs, _ := store.ListStatusLog(ctx, e.pool, tid, 1)
	if len(logs) != 1 || logs[0].ToStatus != tenant.KhoiTao || !strings.Contains(logs[0].Reason, "kiểm thử khói") {
		t.Fatalf("nhật ký trạng thái: %+v", logs)
	}
	if n := len(e.outbox(t, tid, "sys.tenant.provisioned.v1")); n != 0 {
		t.Fatal("không được phát sys.tenant.provisioned.v1 khi lỗi")
	}
	// Bản nháp còn nguyên: cấp phát lại được và lần này thành công.
	e.saga.DNS = e.saga.DNS.(*flakyDNS).DNS
	var jid2 uuid.UUID
	if err := dbx.InTx(ctx, e.pool, func(tx pgx.Tx) error {
		j, err := e.saga.Tenants.StartProvision(ctx, tx, admin, tid, x.RowVersion)
		if err == nil {
			jid2 = j.ID
		}
		return err
	}); err != nil {
		t.Fatal(err)
	}
	if err := e.saga.Execute(ctx, jid2); err != nil {
		t.Fatal(err)
	}
	if s := e.tenant(t, tid).Status; s != tenant.DangCauHinh {
		t.Fatalf("cấp phát lại phải thành công, đang %s", s)
	}
}

// TestSagaPackNotReady: MOD-05 từ chối gói (BR-01-03) → không thử lại, bù trừ sạch về KHOI_TAO, MSG-01-05.
func TestSagaPackNotReady(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	e.cell.PackNotReady = "goi-dang"
	code := uniq("goi")
	tid, jid := e.seed(t, code)
	if err := e.saga.Execute(ctx, jid); err != nil {
		t.Fatal(err)
	}
	if s := e.tenant(t, tid).Status; s != tenant.KhoiTao {
		t.Fatalf("phải về KHOI_TAO, đang %s", s)
	}
	j, st := e.job(t, jid)
	if st.FailedStep != provision.StepPacks || !strings.Contains(*j.Message, "Gói chính sách goi-dang chưa đạt 100% test case vàng nên chưa gắn được") {
		t.Fatalf("lý do: %s %+v", *j.Message, st)
	}
	var prob map[string]any
	_ = json.Unmarshal(j.Problem, &prob)
	if prob["code"] != "CONFIG-422-POLICY-PACK-NOT-READY" || prob["status"] != float64(422) {
		t.Fatalf("problem: %v", prob)
	}
	for _, s := range st.Steps {
		if s.Name == provision.StepPacks && s.Attempts != 1 {
			t.Fatalf("lỗi BR-01-03 không được thử lại: %+v", s)
		}
		if s.Name == provision.StepCalendar && s.Status != tenant.StepPending {
			t.Fatalf("bước sau bước lỗi không được chạy: %+v", s)
		}
	}
	if orgCount(t, code) != 0 || userCount(t, code) != 0 {
		t.Fatal("còn tài nguyên Keycloak")
	}
	e.cell.Lock()
	left := len(e.cell.DNS) + len(e.cell.Certs) + len(e.cell.Secrets) + len(e.cell.Packs) + len(e.cell.UiPacks)
	e.cell.Unlock()
	if left != 0 {
		t.Fatalf("còn tài nguyên trên cell giả: %d", left)
	}
}

// TestSagaRetry: lỗi tạm thời được thử lại với thời gian chờ tăng dần rồi thành công.
func TestSagaRetry(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	e.cell.CalendarFails = 2
	e.cell.CertReadyAfter = 2
	tid, jid := e.seed(t, uniq("tl"))
	if err := e.saga.Execute(ctx, jid); err != nil {
		t.Fatal(err)
	}
	if s := e.tenant(t, tid).Status; s != tenant.DangCauHinh {
		t.Fatalf("phải DANG_CAU_HINH, đang %s", s)
	}
	_, st := e.job(t, jid)
	for _, s := range st.Steps {
		if s.Name == provision.StepCalendar && s.Attempts != 3 {
			t.Fatalf("bước lịch phải thử 3 lần: %+v", s)
		}
	}
}

// TestSagaSingleRunner: hai tiến trình cùng chạy một saga, khoá tư vấn chỉ cho một tiến trình làm.
func TestSagaSingleRunner(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	code := uniq("dl")
	tid, jid := e.seed(t, code)
	done := make(chan error, 2)
	for range 2 {
		go func() { done <- e.saga.Execute(ctx, jid) }()
	}
	for range 2 {
		if err := <-done; err != nil {
			t.Fatal(err)
		}
	}
	// Tiến trình bị khoá thoát ngay; chạy lại để chắc chắn saga hoàn tất.
	if err := e.saga.Execute(ctx, jid); err != nil {
		t.Fatal(err)
	}
	if s := e.tenant(t, tid).Status; s != tenant.DangCauHinh {
		t.Fatalf("phải DANG_CAU_HINH, đang %s", s)
	}
	if orgCount(t, code) != 1 || len(e.outbox(t, tid, "sys.tenant.provisioned.v1")) != 1 {
		t.Fatal("chạy song song tạo trùng tài nguyên hoặc sự kiện")
	}
}
