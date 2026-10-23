package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"mission/services-go/tenant-registry/internal/platform/auth/authtest"
	"mission/services-go/tenant-registry/internal/resolve"
	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/pkg/routecookie"
)

const platformYAML = "modules/platform.yaml"

// seedResolvable tạo tenant đang vận hành gắn cell, có tên miền và IdP VNeID.
func seedResolvable(t testing.TB, q store.Querier, code, cell, status string) *store.Tenant {
	t.Helper()
	ctx := context.Background()
	exp := 52000
	ten := &store.Tenant{ID: store.NewID(), Code: code, Name: "Đảng uỷ " + code, Sector: "DANG", TenancyModel: "BRIDGE", Status: status,
		MaxAccounts: 60000, ExpectedAccounts: &exp, AccountCount: 51870, CreatedBy: store.SystemActor, Flows: []string{"DANG"}, KekRef: "transit/keys/t-" + code}
	if err := store.InsertTenant(ctx, q, ten); err != nil {
		t.Fatal(err)
	}
	c, err := store.GetCellByCode(ctx, q, cell)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.InsertTenantCell(ctx, q, &store.TenantCell{TenantID: ten.ID, CellID: c.ID, DatabaseName: "td_" + code,
		ValidFrom: time.Now().Add(-24 * time.Hour), TenancyModel: "bridge", DataRegion: c.PrimaryDC}, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	if err := store.InsertDomain(ctx, q, &store.Domain{TenantID: ten.ID, Domain: "kpi." + code + ".dcs.vn", Kind: "PRIMARY"}, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	if err := store.InsertIdp(ctx, q, &store.Idp{TenantID: ten.ID, IdpAlias: "vneid", Protocol: "OIDC", Issuer: "https://sso.vneid.gov.vn", IsVneid: true}, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	return ten
}

type resolution struct {
	TenantID     uuid.UUID `json:"tenantId"`
	TenantCode   string    `json:"tenantCode"`
	Status       string    `json:"status"`
	TenancyModel string    `json:"tenancyModel"`
	Cell         struct {
		Code       string `json:"code"`
		APIBaseURL string `json:"apiBaseUrl"`
	} `json:"cell"`
	Idps []struct {
		IdpAlias string `json:"idpAlias"`
	} `json:"idps"`
	CacheTTLSeconds int `json:"cacheTtlSeconds"`
}

func TestResolveTenantCell(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	ten := seedResolvable(t, f.pool, "resolve_a", "CELL-HN", "DANG_VAN_HANH")

	for name, target := range map[string]string{
		"host":    "/api/v1/registry/resolve?host=KPI.resolve_a.dcs.vn",
		"code":    "/api/v1/registry/resolve?tenant=resolve_a",
		"id":      "/api/v1/registry/resolve?tenant=" + ten.ID.String(),
		"service": "/api/v1/registry/resolve?host=kpi.resolve_a.dcs.vn",
	} {
		t.Run(name, func(t *testing.T) {
			tok := userToken("resolve_a", "", "member")
			if name == "service" {
				tok = serviceToken()
			}
			rec := f.do(t, http.MethodGet, target, tok, nil, nil)
			if rec.Code != http.StatusOK {
				t.Fatalf("muốn 200, có %d: %s", rec.Code, rec.Body)
			}
			conform(t, platformYAML, "/registry/resolve", "get", rec)
			var got resolution
			_ = json.Unmarshal(rec.Body.Bytes(), &got)
			if got.TenantID != ten.ID || got.Cell.Code != "CELL-HN" || got.Cell.APIBaseURL != "https://api.cell-hn.okrkpi.vn" ||
				got.Status != "DANG_VAN_HANH" || got.TenancyModel != "BRIDGE" || len(got.Idps) != 1 || got.CacheTTLSeconds != 300 {
				t.Fatalf("kết quả sai: %+v", got)
			}
			if cc := rec.Header().Get("Cache-Control"); cc != "private, max-age=300" {
				t.Fatalf("Cache-Control sai: %q", cc)
			}
			res := http.Response{Header: rec.Header()}
			var ck *http.Cookie
			for _, c := range res.Cookies() {
				if c.Name == routecookie.Name {
					ck = c
				}
			}
			if ck == nil || !ck.HttpOnly || !ck.Secure || ck.MaxAge != 300 {
				t.Fatalf("thiếu hoặc sai cookie định tuyến: %+v", ck)
			}
			cl, err := f.cookies.Verify(ck.Value, time.Now())
			if err != nil || cl.TenantID != ten.ID.String() || cl.CellCode != "CELL-HN" {
				t.Fatalf("cookie không kiểm được: %v %+v", err, cl)
			}
		})
	}
}

func TestResolveValidationAndAuthorization(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	seedResolvable(t, f.pool, "resolve_b", "CELL-G1", "DANG_VAN_HANH")
	tok := userToken("resolve_b", "", "member")
	for _, target := range []string{"/api/v1/registry/resolve", "/api/v1/registry/resolve?tenant=resolve_b&host=kpi.resolve_b.dcs.vn",
		"/api/v1/registry/resolve?host=" + strings.Repeat("a", 254)} {
		rec := f.do(t, http.MethodGet, target, tok, nil, nil)
		if rec.Code != http.StatusUnprocessableEntity {
			t.Fatalf("%s: muốn 422, có %d", target, rec.Code)
		}
		conform(t, platformYAML, "/registry/resolve", "get", rec)
	}
	rec := f.do(t, http.MethodGet, "/api/v1/registry/resolve?tenant=resolve_b", "", nil, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("thiếu token muốn 401, có %d", rec.Code)
	}
	conform(t, platformYAML, "/registry/resolve", "get", rec)
	expired := keys.Token(authtest.Claims{Subject: "x", Tenant: "resolve_b", Roles: []string{"member"}, TTL: -time.Hour})
	if rec := f.do(t, http.MethodGet, "/api/v1/registry/resolve?tenant=resolve_b", expired, nil, nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("token hết hạn muốn 401, có %d", rec.Code)
	}
	rec = f.do(t, http.MethodGet, "/api/v1/registry/resolve?tenant=resolve_b", userToken("tenant_khac", "", "member"), nil, nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("tenant khác claim muốn 403, có %d", rec.Code)
	}
	conform(t, platformYAML, "/registry/resolve", "get", rec)
	rec = f.do(t, http.MethodGet, "/api/v1/registry/resolve?host=khong-co.okr.viettel.vn", serviceToken(), nil, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("tên miền không có muốn 404, có %d", rec.Code)
	}
	conform(t, platformYAML, "/registry/resolve", "get", rec)
}

func TestResolveSuspendedTenantReportsStatus(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	seedResolvable(t, f.pool, "resolve_c", "CELL-G2", "TAM_NGUNG")
	rec := f.do(t, http.MethodGet, "/api/v1/registry/resolve?host=kpi.resolve_c.dcs.vn", serviceToken(), nil, nil)
	var got resolution
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if rec.Code != http.StatusOK || got.Status != "TAM_NGUNG" {
		t.Fatalf("tenant tạm ngừng phải trả trạng thái để hiện MSG-00-07: %d %+v", rec.Code, got)
	}
	conform(t, platformYAML, "/registry/resolve", "get", rec)
}

// Tenant đã chuyển cell: request mang cookie hoặc claim của cell cũ nhận 421 kèm Location tới cell mới.
func TestResolveMisdirectedAfterCellMove(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	ctx := context.Background()
	ten := seedResolvable(t, f.pool, "resolve_d", "CELL-G3", "DANG_CHUYEN_CELL")
	oldCookie := f.cookies.Sign(ten.ID.String(), "CELL-G3", time.Now())
	// Cắt chuyển: đóng gắn cũ, mở gắn mới ở CELL-G4.
	cur, err := store.CurrentTenantCell(ctx, f.pool, ten.ID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	cut := time.Now().Add(-time.Second)
	if err := store.CloseTenantCell(ctx, f.pool, cur.ID, cut, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	g4, _ := store.GetCellByCode(ctx, f.pool, "CELL-G4")
	if err := store.InsertTenantCell(ctx, f.pool, &store.TenantCell{TenantID: ten.ID, CellID: g4.ID, DatabaseName: "td_resolve_d",
		ValidFrom: cut, TenancyModel: "bridge", DataRegion: "PHIANAM"}, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	tok := userToken("resolve_d", "", "member")
	rec := f.do(t, http.MethodGet, "/api/v1/registry/resolve?tenant=resolve_d", tok, nil,
		map[string]string{"Cookie": routecookie.Name + "=" + oldCookie})
	if rec.Code != http.StatusMisdirectedRequest || rec.Header().Get("Location") != "https://api.cell-g4.okrkpi.vn" {
		t.Fatalf("muốn 421 tới cell mới, có %d %q: %s", rec.Code, rec.Header().Get("Location"), rec.Body)
	}
	conform(t, platformYAML, "/registry/resolve", "get", rec)
	var p struct{ Code string }
	_ = json.Unmarshal(rec.Body.Bytes(), &p)
	if p.Code != "REGISTRY-421-TENANT-MOVED" {
		t.Fatalf("mã lỗi sai: %s", p.Code)
	}
	// Claim cell cũ trong token cũng bị chuyển hướng.
	if rec := f.do(t, http.MethodGet, "/api/v1/registry/resolve?tenant=resolve_d", userToken("resolve_d", "CELL-G3", "member"), nil, nil); rec.Code != http.StatusMisdirectedRequest {
		t.Fatalf("claim cell cũ muốn 421, có %d", rec.Code)
	}
	// Cookie giả mạo bị bỏ qua (không dùng làm cell nguồn); không có cell nguồn thì trả cell mới.
	rec = f.do(t, http.MethodGet, "/api/v1/registry/resolve?tenant=resolve_d", tok, nil,
		map[string]string{"Cookie": routecookie.Name + "=" + oldCookie + "x"})
	var got resolution
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	if rec.Code != http.StatusOK || got.Cell.Code != "CELL-G4" {
		t.Fatalf("muốn 200 với cell mới, có %d %+v", rec.Code, got)
	}
}

// Mất CSDL: tenant có trong đệm vẫn phân giải được, tenant chưa có trong đệm nhận 503 + Retry-After: 30.
func TestResolveDatabaseDown(t *testing.T) {
	if os.Getenv("REGISTRY_TEST_PG_DSN") != "" {
		t.Skip("cần Testcontainers để dựng và dừng một CSDL riêng")
	}
	ctx := context.Background()
	own, err := startPG(ctx, true)
	if err != nil {
		t.Fatal(err)
	}
	defer own.Terminate(ctx)
	f := newFixture(t, own.SvcDSN, func(_ *apiDeps, o *resolve.Options) {
		o.TTL = 50 * time.Millisecond
		o.StaleMax = time.Minute
		o.Timeout = 500 * time.Millisecond
	})
	seedResolvable(t, f.pool, "resolve_e", "CELL-SH", "DANG_VAN_HANH")
	seedResolvable(t, f.pool, "resolve_f", "CELL-SH", "DANG_VAN_HANH")
	if rec := f.do(t, http.MethodGet, "/api/v1/registry/resolve?host=kpi.resolve_e.dcs.vn", serviceToken(), nil, nil); rec.Code != http.StatusOK {
		t.Fatalf("nạp đệm: %d", rec.Code)
	}
	timeout := 10 * time.Second
	if err := own.c.Stop(ctx, &timeout); err != nil {
		t.Fatal(err)
	}
	time.Sleep(100 * time.Millisecond) // quá TTL: buộc đọc lại CSDL
	rec := f.do(t, http.MethodGet, "/api/v1/registry/resolve?host=kpi.resolve_e.dcs.vn", serviceToken(), nil, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("CSDL mất, tenant có đệm muốn 200, có %d: %s", rec.Code, rec.Body)
	}
	conform(t, platformYAML, "/registry/resolve", "get", rec)
	rec = f.do(t, http.MethodGet, "/api/v1/registry/resolve?host=kpi.resolve_f.dcs.vn", serviceToken(), nil, nil)
	if rec.Code != http.StatusServiceUnavailable || rec.Header().Get("Retry-After") != "30" {
		t.Fatalf("CSDL mất, tenant chưa đệm muốn 503 + Retry-After: 30, có %d %q", rec.Code, rec.Header().Get("Retry-After"))
	}
	conform(t, platformYAML, "/registry/resolve", "get", rec)
	if rec := f.do(t, http.MethodGet, "/readyz", "", nil, nil); rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("readyz khi mất CSDL muốn 503, có %d", rec.Code)
	}
}

// Thay đổi ghi ở một replica (NOTIFY trong giao dịch) vô hiệu đệm ở replica khác đang LISTEN.
func TestResolveInvalidatedByNotify(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	f := newFixture(t, db.SvcDSN, func(_ *apiDeps, o *resolve.Options) { o.TTL = time.Hour })
	go f.resolver.Listen(ctx, f.pool, slogDiscard())
	ten := seedResolvable(t, f.pool, "resolve_g", "CELL-G1", "DANG_VAN_HANH")
	time.Sleep(200 * time.Millisecond) // chờ LISTEN sẵn sàng
	if r, err := f.resolver.ByTenant(ctx, "resolve_g"); err != nil || r.Status != "DANG_VAN_HANH" {
		t.Fatal(err)
	}
	tx, _ := f.pool.Begin(ctx)
	if _, err := tx.Exec(ctx, `UPDATE registry.tenant SET status = 'TAM_NGUNG' WHERE id = $1`, ten.ID); err != nil {
		t.Fatal(err)
	}
	if err := resolve.Notify(ctx, tx, ten.ID); err != nil {
		t.Fatal(err)
	}
	_ = tx.Commit(ctx)
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if r, err := f.resolver.ByTenant(ctx, "resolve_g"); err == nil && r.Status == "TAM_NGUNG" {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("đệm không bị vô hiệu sau NOTIFY")
}

func TestListCells(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	seedResolvable(t, f.pool, "cells_a", "CELL-G2", "DANG_VAN_HANH")
	if rec := f.do(t, http.MethodGet, "/api/v1/registry/cells", "", nil, nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("muốn 401, có %d", rec.Code)
	}
	rec := f.do(t, http.MethodGet, "/api/v1/registry/cells", userToken("cells_a", "", "tenant_admin"), nil, nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("không phải platform_admin muốn 403, có %d", rec.Code)
	}
	conform(t, platformYAML, "/registry/cells", "get", rec)
	stale := keys.Token(authtest.Claims{Subject: "a", Roles: []string{"platform_admin"}, ACR: "aal2", AuthTime: time.Now().Add(-10 * time.Minute)})
	rec = f.do(t, http.MethodGet, "/api/v1/registry/cells", stale, nil, nil)
	if rec.Code != http.StatusUnauthorized || !strings.Contains(rec.Header().Get("WWW-Authenticate"), "insufficient_user_authentication") {
		t.Fatalf("AAL2 quá 5 phút muốn 401 step-up, có %d %q", rec.Code, rec.Header().Get("WWW-Authenticate"))
	}
	conform(t, platformYAML, "/registry/cells", "get", rec)

	var codes []string
	cursor := ""
	for page := 0; page < 5; page++ {
		target := "/api/v1/registry/cells?limit=3"
		if cursor != "" {
			target += "&cursor=" + cursor
		}
		rec := f.do(t, http.MethodGet, target, adminToken(), nil, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("muốn 200, có %d: %s", rec.Code, rec.Body)
		}
		conform(t, platformYAML, "/registry/cells", "get", rec)
		var p struct {
			Items []struct {
				Code         string
				TenantCount  int
				AccountCount int
			}
			NextCursor *string
		}
		_ = json.Unmarshal(rec.Body.Bytes(), &p)
		for _, it := range p.Items {
			codes = append(codes, it.Code)
			if it.Code == "CELL-G2" && (it.TenantCount < 1 || it.AccountCount < 51870) {
				t.Fatalf("tải CELL-G2 sai: %+v", it)
			}
		}
		if p.NextCursor == nil {
			break
		}
		cursor = *p.NextCursor
	}
	if len(codes) != 8 || !sort.StringsAreSorted(codes) {
		t.Fatalf("phân trang con trỏ phải trả đủ 8 cell theo thứ tự: %v", codes)
	}
	rec = f.do(t, http.MethodGet, "/api/v1/registry/cells?tenancyModel=BRIDGE&status=HOAT_DONG", adminToken(), nil, nil)
	var p struct {
		Items []struct{ TenancyModel string }
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &p)
	if len(p.Items) != 4 {
		t.Fatalf("lọc BRIDGE muốn 4 cell, có %d", len(p.Items))
	}
	for _, target := range []string{"?limit=0", "?limit=201", "?status=XX", "?tenancyModel=bridge", "?cursor=***"} {
		rec := f.do(t, http.MethodGet, "/api/v1/registry/cells"+target, adminToken(), nil, nil)
		if rec.Code != http.StatusUnprocessableEntity {
			t.Fatalf("%s muốn 422, có %d", target, rec.Code)
		}
		conform(t, platformYAML, "/registry/cells", "get", rec)
	}
}

// Đường trúng đệm của resolve (QT-00 bước 2: ≤ 50 ms). Đo qua toàn bộ handler: kiểm JWT RS256, đệm, ký
// cookie, tuần tự hoá JSON, nhật ký.
func TestResolveCacheHitP95(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	seedResolvable(t, f.pool, "bench_a", "CELL-HN", "DANG_VAN_HANH")
	tok := serviceToken()
	req := func() *httptest.ResponseRecorder {
		return f.do(t, http.MethodGet, "/api/v1/registry/resolve?host=kpi.bench_a.dcs.vn", tok, nil, nil)
	}
	if rec := req(); rec.Code != http.StatusOK {
		t.Fatalf("nạp đệm: %d", rec.Code)
	}
	const n = 5000
	d := make([]time.Duration, n)
	for i := range d {
		start := time.Now()
		if rec := req(); rec.Code != http.StatusOK {
			t.Fatalf("lần %d: %d", i, rec.Code)
		}
		d[i] = time.Since(start)
	}
	sort.Slice(d, func(i, j int) bool { return d[i] < d[j] })
	p50, p95, p99 := d[n/2], d[n*95/100], d[n*99/100]
	t.Logf("resolve trúng đệm, %d request: p50=%v p95=%v p99=%v max=%v", n, p50, p95, p99, d[n-1])
	if p95 > 50*time.Millisecond {
		t.Fatalf("p95 %v vượt 50 ms", p95)
	}
}

func BenchmarkResolveCacheHit(b *testing.B) {
	f := newFixture(b, db.SvcDSN)
	code := "bench_" + strings.ReplaceAll(uuid.NewString()[:8], "-", "")
	seedResolvable(b, f.pool, code, "CELL-HN", "DANG_VAN_HANH")
	tok := serviceToken()
	target := "/api/v1/registry/resolve?host=kpi." + code + ".dcs.vn"
	if rec := f.do(b, http.MethodGet, target, tok, nil, nil); rec.Code != http.StatusOK {
		b.Fatalf("nạp đệm: %d", rec.Code)
	}
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		rec := f.do(b, http.MethodGet, target, tok, nil, nil)
		if rec.Code != http.StatusOK {
			b.Fatal(rec.Code)
		}
	}
}
