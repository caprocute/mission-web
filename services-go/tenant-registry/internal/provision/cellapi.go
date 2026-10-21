package provision

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
)

// HeaderTenant mang tenant đích của lời gọi dịch vụ tới dịch vụ trong cell (suy luận; tài khoản dịch vụ không
// có claim tenant cố định, xem wiki trang 14).
const HeaderTenant = "X-Tenant-Id"

// ---------------------------------------------------------------- mission-core (MOD-05, MOD-08)

// CoreHTTP hiện thực cổng Core bằng API nội bộ của mission-core trong cell. Đường dẫn /internal/v1/... là suy
// luận (cổng api của MOD-05, MOD-08 chưa có hợp đồng HTTP trong contracts/), đề nghị chốt ở wiki trang 14.
type CoreHTTP struct {
	HTTP   *http.Client
	Tokens TokenSource
}

const sysCore = "mission-core"

func (c *CoreHTTP) call(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID, method, path string, body any, ok ...int) (*result, error) {
	tok, err := c.Tokens.Token(ctx, ep.TokenURL)
	if err != nil {
		return nil, err
	}
	return do(ctx, c.HTTP, call{system: sysCore, method: method, url: ep.CoreURL + "/internal/v1/tenants/" + tenantID.String() + path,
		body: body, token: tok, headers: map[string]string{HeaderTenant: tenantID.String()}, ok: ok})
}

// BindPolicyPacks gắn Policy Pack theo luồng (policy.policy_binding); MOD-05 từ chối gói chưa DA_PHAT_HANH
// hoặc chưa đạt 100% test case vàng bằng 422 mã POLICY-422-PACK-NOT-READY (BR-01-03).
func (c *CoreHTTP) BindPolicyPacks(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID, bindings []PackBinding) error {
	r, err := c.call(ctx, ep, tenantID, http.MethodPut, "/policy-bindings", map[string]any{"bindings": bindings}, http.StatusUnprocessableEntity)
	if err != nil {
		return err
	}
	if r.status == http.StatusUnprocessableEntity {
		var p struct {
			Code     string `json:"code"`
			PackCode string `json:"packCode"`
		}
		_ = json.Unmarshal(r.body, &p)
		if p.Code == "POLICY-422-PACK-NOT-READY" {
			return &PackNotReadyError{Pack: p.PackCode}
		}
		return &HTTPError{System: sysCore, Status: r.status, Body: string(r.body)}
	}
	return nil
}

// UnbindPolicyPacks gỡ gắn gói (bù trừ).
func (c *CoreHTTP) UnbindPolicyPacks(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) error {
	_, err := c.call(ctx, ep, tenantID, http.MethodDelete, "/policy-bindings", nil, http.StatusNotFound)
	return err
}

// PolicyPacks đọc gói đã gắn kèm trạng thái và tỉ lệ đạt test case vàng.
func (c *CoreHTTP) PolicyPacks(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) ([]PackState, error) {
	r, err := c.call(ctx, ep, tenantID, http.MethodGet, "/policy-bindings", nil)
	if err != nil {
		return nil, err
	}
	var out struct {
		Items []PackState `json:"items"`
	}
	return out.Items, decode(r, &out)
}

// AssignUiPack gắn UI Pack (config.ui_pack_version); versionID nil = UI Pack mặc định của khối (sector).
func (c *CoreHTTP) AssignUiPack(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID, sector string, versionID *uuid.UUID) error {
	_, err := c.call(ctx, ep, tenantID, http.MethodPut, "/ui-pack", map[string]any{"sector": sector, "uiPackVersionId": versionID})
	return err
}

// UnassignUiPack gỡ UI Pack (bù trừ).
func (c *CoreHTTP) UnassignUiPack(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) error {
	_, err := c.call(ctx, ep, tenantID, http.MethodDelete, "/ui-pack", nil, http.StatusNotFound)
	return err
}

// CreateDefaultCalendar tạo lịch nghỉ mặc định (config.holiday_calendar) theo lịch quốc gia của năm.
func (c *CoreHTTP) CreateDefaultCalendar(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID, year int) error {
	_, err := c.call(ctx, ep, tenantID, http.MethodPut, "/holiday-calendars/default", map[string]any{"year": year})
	return err
}

// DeleteDefaultCalendar xoá lịch nghỉ mặc định do saga tạo (bù trừ).
func (c *CoreHTTP) DeleteDefaultCalendar(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) error {
	_, err := c.call(ctx, ep, tenantID, http.MethodDelete, "/holiday-calendars/default", nil, http.StatusNotFound)
	return err
}

// ---------------------------------------------------------------- org-service (SVC-04)

// OrgHTTP hiện thực cổng Org bằng API công khai của org-service (org.yaml getOrgTree, listOrgDiscrepancies)
// với token dịch vụ.
type OrgHTTP struct {
	HTTP   *http.Client
	Tokens TokenSource
}

const sysOrg = "org-service"

func (o *OrgHTTP) get(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID, path string, v any) error {
	tok, err := o.Tokens.Token(ctx, ep.TokenURL)
	if err != nil {
		return err
	}
	r, err := do(ctx, o.HTTP, call{system: sysOrg, method: http.MethodGet, url: ep.OrgURL + path, token: tok,
		headers: map[string]string{HeaderTenant: tenantID.String()}})
	if err != nil {
		return err
	}
	return decode(r, v)
}

// HasOrgTree: cây tổ chức có ít nhất một gốc.
func (o *OrgHTTP) HasOrgTree(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) (bool, error) {
	var t struct {
		Roots []json.RawMessage `json:"roots"`
	}
	if err := o.get(ctx, ep, tenantID, "/api/v1/org/units/tree?depth=1", &t); err != nil {
		return false, err
	}
	return len(t.Roots) > 0, nil
}

// HighDiscrepancies đếm lệch mức Cao chưa giải quyết (counts.CAO của listOrgDiscrepancies).
func (o *OrgHTTP) HighDiscrepancies(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) (int, error) {
	var p struct {
		Counts struct {
			CAO *int `json:"CAO"`
		} `json:"counts"`
	}
	q := url.Values{"severity": {"CAO"}, "unresolved": {"true"}, "limit": {"1"}}
	if err := o.get(ctx, ep, tenantID, "/api/v1/org/discrepancies?"+q.Encode(), &p); err != nil {
		return 0, err
	}
	if p.Counts.CAO == nil {
		return 0, errors.New("provision: org-service không trả số lệch mức Cao")
	}
	return *p.Counts.CAO, nil
}

// ---------------------------------------------------------------- Prometheus (độ trễ bản sao)

// PrometheusLag đo độ trễ bản sao dự phòng của cell bằng truy vấn tức thời Prometheus (API /api/v1/query).
// Query có chỗ {cell} (mã cell chữ thường), ví dụ max(cnpg_pg_replication_lag{cell="{cell}"}) theo giây.
type PrometheusLag struct {
	BaseURL string
	Query   string
	HTTP    *http.Client
}

// Lag trả độ trễ lớn nhất.
func (p *PrometheusLag) Lag(ctx context.Context, cellCode string) (time.Duration, error) {
	q := strings.ReplaceAll(p.Query, "{cell}", strings.ToLower(cellCode))
	r, err := do(ctx, p.HTTP, call{system: "prometheus", method: http.MethodGet,
		url: strings.TrimRight(p.BaseURL, "/") + "/api/v1/query?" + url.Values{"query": {q}}.Encode()})
	if err != nil {
		return 0, err
	}
	var res struct {
		Status string `json:"status"`
		Data   struct {
			Result []struct {
				Value []any `json:"value"`
			} `json:"result"`
		} `json:"data"`
	}
	if err := decode(r, &res); err != nil {
		return 0, err
	}
	if res.Status != "success" || len(res.Data.Result) == 0 || len(res.Data.Result[0].Value) != 2 {
		return 0, errors.New("provision: không có số đo độ trễ bản sao")
	}
	s, _ := res.Data.Result[0].Value[1].(string)
	sec, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0, fmt.Errorf("provision: số đo độ trễ không hợp lệ: %w", err)
	}
	return time.Duration(sec * float64(time.Second)), nil
}
