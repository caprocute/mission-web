package api

import (
	"encoding/base64"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/google/uuid"

	"mission/services-go/tenant-registry/internal/platform/auth"
	"mission/services-go/tenant-registry/internal/platform/httpx"
	"mission/services-go/tenant-registry/internal/platform/problem"
	"mission/services-go/tenant-registry/internal/resolve"
	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/pkg/routecookie"
)

// Kiểu phản hồi theo platform.yaml (TenantResolution, RegistryCellRef, TenantIdpInfo, RegistryCell, CellPage).

type registryCellRef struct {
	ID         uuid.UUID `json:"id"`
	Code       string    `json:"code"`
	APIBaseURL string    `json:"apiBaseUrl,omitempty"`
}

type tenantIdpInfo struct {
	IdpAlias     string   `json:"idpAlias"`
	Protocol     string   `json:"protocol"`
	Issuer       string   `json:"issuer"`
	IsVneid      bool     `json:"isVneid"`
	EmailDomains []string `json:"emailDomains,omitempty"`
}

type tenantResolution struct {
	TenantID        uuid.UUID       `json:"tenantId"`
	TenantCode      string          `json:"tenantCode"`
	TenantName      string          `json:"tenantName,omitempty"`
	Status          string          `json:"status"`
	TenancyModel    string          `json:"tenancyModel"`
	Cell            registryCellRef `json:"cell"`
	Idps            []tenantIdpInfo `json:"idps"`
	CacheTTLSeconds int             `json:"cacheTtlSeconds"`
}

// resolveCacheSeconds: hạn đệm phía client và APISIX (hợp đồng: Cache-Control private, max-age=300).
const resolveCacheSeconds = 300

// resolveTenantCell: GET /api/v1/registry/resolve (QT-00 bước 2, SCR-0001; x-mission.owner SVC-03).
func (s *Server) resolveTenantCell(w http.ResponseWriter, r *http.Request) {
	p, ok := s.authenticate(w, r)
	if !ok {
		return
	}
	q := r.URL.Query()
	tenant, host := strings.TrimSpace(q.Get("tenant")), strings.TrimSpace(q.Get("host"))
	if (tenant == "") == (host == "") {
		s.fail(w, r, problem.Validation(problem.FieldError{Field: "tenant", Message: "Phải có đúng một trong hai tham số tenant hoặc host"}))
		return
	}
	if len(tenant) > 64 || len(host) > 253 {
		s.fail(w, r, problem.Validation(problem.FieldError{Field: "host", Message: "Tham số quá dài"}))
		return
	}
	var (
		res *resolve.Resolution
		err error
	)
	if host != "" {
		res, err = s.Resolver.ByHost(r.Context(), host)
	} else {
		res, err = s.Resolver.ByTenant(r.Context(), tenant)
	}
	switch {
	case errors.Is(err, resolve.ErrNotFound):
		s.fail(w, r, problem.NotFound())
		return
	case errors.Is(err, resolve.ErrUnavailable):
		s.fail(w, r, problem.Unavailable(30))
		return
	case err != nil:
		s.fail(w, r, err)
		return
	}
	// Người dùng chỉ phân giải được tenant của chính mình (claim tenant/tid); client nội bộ của APISIX và
	// quản trị nền tảng phân giải được mọi tenant (x-mission.roles của hợp đồng).
	if !p.HasAnyRole(auth.RoleServiceClient, auth.RolePlatformAdmin) &&
		!strings.EqualFold(p.Tenant, res.TenantCode) && p.Tenant != res.TenantID.String() {
		s.fail(w, r, problem.Forbidden())
		return
	}
	// Cell mà request đang đi qua: cookie định tuyến hợp lệ của cùng tenant, nếu không có thì claim cell.
	origin := p.Cell
	if c, err := r.Cookie(routecookie.Name); err == nil {
		if cl, err := s.Cookies.Verify(c.Value, s.Now()); err == nil && cl.TenantID == res.TenantID.String() {
			origin = cl.CellCode
		}
	}
	if origin != "" && !strings.EqualFold(origin, res.CellCode) {
		s.fail(w, r, problem.New(http.StatusMisdirectedRequest, "tenant-moved", "Đơn vị đã chuyển sang hệ thống phục vụ khác", "REGISTRY-421-TENANT-MOVED").
			WithDetail("Vui lòng tải lại trang để tiếp tục.").WithHeader("Location", res.CellURL))
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name: routecookie.Name, Value: s.Cookies.Sign(res.TenantID.String(), res.CellCode, s.Now()),
		Path: "/", Domain: s.CookieDomain, MaxAge: int(routecookie.TTL.Seconds()),
		HttpOnly: true, Secure: true, SameSite: http.SameSiteLaxMode,
	})
	w.Header().Set("Cache-Control", "private, max-age="+strconv.Itoa(resolveCacheSeconds))
	out := tenantResolution{TenantID: res.TenantID, TenantCode: res.TenantCode, TenantName: res.TenantName, Status: res.Status,
		TenancyModel: res.TenancyModel, Cell: registryCellRef{ID: res.CellID, Code: res.CellCode, APIBaseURL: res.CellURL},
		Idps: []tenantIdpInfo{}, CacheTTLSeconds: resolveCacheSeconds}
	for _, i := range res.Idps {
		out.Idps = append(out.Idps, tenantIdpInfo{IdpAlias: i.Alias, Protocol: i.Protocol, Issuer: i.Issuer, IsVneid: i.IsVneid, EmailDomains: i.EmailDomains})
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}

type registryCell struct {
	ID               uuid.UUID `json:"id"`
	Code             string    `json:"code"`
	Name             string    `json:"name"`
	PrimaryDC        string    `json:"primaryDc"`
	StandbyDC        *string   `json:"standbyDc"`
	TenancyModel     string    `json:"tenancyModel"`
	APIBaseURL       string    `json:"apiBaseUrl"`
	CapacityAccounts int       `json:"capacityAccounts"`
	Status           string    `json:"status"`
	TenantCount      int       `json:"tenantCount"`
	AccountCount     int       `json:"accountCount"`
}

type cellPage struct {
	Items      []registryCell `json:"items"`
	NextCursor *string        `json:"nextCursor"`
}

var (
	cellStatuses  = []string{"HOAT_DONG", "BAO_TRI", "NGUNG"}
	tenancyModels = []string{"SILO", "BRIDGE", "POOL", "AIRGAP"}
)

func oneOf(v string, set []string) bool {
	for _, s := range set {
		if v == s {
			return true
		}
	}
	return false
}

// parseLimit đọc limit (1..200, mặc định 20; baseline mục 13).
func parseLimit(r *http.Request) (int, error) {
	raw := r.URL.Query().Get("limit")
	if raw == "" {
		return 20, nil
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 1 || n > 200 {
		return 0, problem.Validation(problem.FieldError{Field: "limit", Message: "Số dòng mỗi trang phải từ 1 đến 200"})
	}
	return n, nil
}

func encodeCursor(s string) *string {
	v := base64.RawURLEncoding.EncodeToString([]byte(s))
	return &v
}

func decodeCursor(r *http.Request) (string, error) {
	raw := r.URL.Query().Get("cursor")
	if raw == "" {
		return "", nil
	}
	b, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil || len(raw) > 512 {
		return "", problem.Validation(problem.FieldError{Field: "cursor", Message: "Con trỏ trang không hợp lệ"})
	}
	return string(b), nil
}

// listCells: GET /api/v1/registry/cells (SCR-0101, SCR-0102; platform_admin, AAL2).
func (s *Server) listCells(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin); !ok {
		return
	}
	q := r.URL.Query()
	f := store.CellFilter{Status: q.Get("status"), TenancyModel: q.Get("tenancyModel")}
	if f.Status != "" && !oneOf(f.Status, cellStatuses) {
		s.fail(w, r, problem.Validation(problem.FieldError{Field: "status", Message: "Trạng thái cell không hợp lệ"}))
		return
	}
	if f.TenancyModel != "" && !oneOf(f.TenancyModel, tenancyModels) {
		s.fail(w, r, problem.Validation(problem.FieldError{Field: "tenancyModel", Message: "Mô hình thuê bao không hợp lệ"}))
		return
	}
	limit, err := parseLimit(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if f.AfterCode, err = decodeCursor(r); err != nil {
		s.fail(w, r, err)
		return
	}
	f.Limit = limit + 1
	cells, err := store.ListCells(r.Context(), s.Pool, f)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	page := cellPage{Items: []registryCell{}}
	if len(cells) > limit {
		cells = cells[:limit]
		page.NextCursor = encodeCursor(cells[limit-1].Code)
	}
	for _, c := range cells {
		page.Items = append(page.Items, registryCell{ID: c.ID, Code: c.Code, Name: c.Name, PrimaryDC: c.PrimaryDC, StandbyDC: c.StandbyDC,
			TenancyModel: c.TenancyModel, APIBaseURL: c.APIBaseURL, CapacityAccounts: c.CapacityAccounts, Status: c.Status,
			TenantCount: c.TenantCount, AccountCount: c.AccountCount})
	}
	httpx.WriteJSON(w, http.StatusOK, page)
}
