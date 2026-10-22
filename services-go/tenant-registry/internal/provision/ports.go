// Package provision là saga cấp phát tenant phần 2 của SVC-03 (DV-03-05; QT-01 bước 4 và bước 9; TL-01
// "Tiếp nhận tenant"; TL-04 OPS-07): Organization trong Keycloak của cell, tên miền (CNAME tới điểm vào cell)
// và chứng chỉ TLS, gắn Policy Pack và UI Pack, lịch nghỉ mặc định, hạn mức cổng, kiểm thử khói; mỗi bước có
// hành động bù trừ. Gói này cũng hiện thực kiểm tra tổng thể và kích hoạt tenant (activateConfigTenant,
// BR-01-10) và tự chuyển DANG_CAU_HINH → SAN_SANG khi đủ điều kiện.
//
// Các hệ ngoài (Keycloak Admin API, PowerDNS, cert-manager qua API Kubernetes, API nội bộ của mission-core
// MOD-05/MOD-08, org-service, Prometheus) nằm sau các cổng dưới đây; bản hiện thực HTTP ở các tệp cùng gói,
// kiểm thử dùng container Keycloak thật và máy chủ giả cho phần còn lại (YC-PT-13).
package provision

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
)

// CellEndpoints là địa chỉ các hệ của một cell mà saga gọi tới.
type CellEndpoints struct {
	Cell        string // mã cell, ví dụ CELL-G2
	KeycloakURL string // gốc Keycloak của cell, ví dụ https://sso.cell-g2.okr.viettel.vn
	Realm       string // realm chứa Organization của tenant (mẫu DV-02-01: mission)
	TokenURL    string // điểm cấp token client_credentials của tài khoản dịch vụ cấp phát
	CoreURL     string // API nội bộ mission-core của cell (MOD-05, MOD-08)
	OrgURL      string // org-service của cell (SVC-04)
	EdgeHost    string // điểm vào của cell, đích CNAME (lấy từ registry.cell.api_base_url)
}

// EndpointResolver trả địa chỉ của cell theo mã cell và URL API công khai (registry.cell.api_base_url).
type EndpointResolver interface {
	Endpoints(cellCode, apiBaseURL string) (CellEndpoints, error)
}

// OrgSpec mô tả Organization của tenant trong Keycloak.
type OrgSpec struct {
	ID      string   // id đã biết (lần chạy lại)
	Name    string   // tên hiển thị (tên tenant)
	Alias   string   // mã tenant (Keycloak 26 alias, không đổi)
	Domains []string // tên miền thư điện tử của tổ chức
	Attrs   map[string][]string
}

// IdpSpec mô tả IdP liên kết của tenant cần đăng ký trong realm của cell (registry.tenant_idp).
type IdpSpec struct {
	Alias        string
	Protocol     string // OIDC, SAML
	Issuer       string
	ClientID     string
	ClientSecret string // đọc từ OpenBao lúc chạy, không lưu vào trạng thái saga
	MetadataURL  string
	EmailDomain  string
}

// UserSpec mô tả tài khoản đầu mối quản trị tenant (C2: không ghi vào nhật ký hay trạng thái saga).
type UserSpec struct {
	Email    string
	FullName string
}

// Keycloak là cổng Admin API của Keycloak cell (mạng quản trị).
type Keycloak interface {
	EnsureOrganization(ctx context.Context, ep CellEndpoints, spec OrgSpec) (string, error)
	OrganizationExists(ctx context.Context, ep CellEndpoints, id string) (bool, error)
	DeleteOrganization(ctx context.Context, ep CellEndpoints, id string) error
	EnsureIdentityProvider(ctx context.Context, ep CellEndpoints, idp IdpSpec) (created bool, err error)
	DeleteIdentityProvider(ctx context.Context, ep CellEndpoints, alias string) error
	LinkIdentityProvider(ctx context.Context, ep CellEndpoints, orgID, alias string) error
	EnsureUser(ctx context.Context, ep CellEndpoints, u UserSpec) (id string, created bool, err error)
	DeleteUser(ctx context.Context, ep CellEndpoints, id string) error
	AddMember(ctx context.Context, ep CellEndpoints, orgID, userID string) error
}

// DNS là cổng tạo bản ghi CNAME của tenant tới điểm vào cell (OPS-07, TL-04 "GeoDNS").
type DNS interface {
	EnsureCNAME(ctx context.Context, name, target string) error
	RemoveCNAME(ctx context.Context, name string) error
	// LookupCNAME trả đích CNAME hiện tại ("" khi chưa có).
	LookupCNAME(ctx context.Context, name string) (string, error)
}

// Certificates là cổng cấp chứng chỉ TLS tự động (cert-manager).
type Certificates interface {
	// Issue yêu cầu (hoặc lấy lại) chứng chỉ cho các tên miền, chờ tới khi sẵn sàng; trả tham chiếu
	// registry.tenant_domain.tls_cert_ref.
	Issue(ctx context.Context, name string, dnsNames []string) (ref string, err error)
	// Ref trả tham chiếu tương ứng tên chứng chỉ (để bù trừ cả khi lần cấp chưa xong).
	Ref(name string) string
	Ready(ctx context.Context, ref string) (bool, error)
	Revoke(ctx context.Context, ref string) error
}

// PackBinding là Policy Pack gắn cho một luồng.
type PackBinding struct {
	Flow          string    `json:"flow"`
	PackVersionID uuid.UUID `json:"packVersionId"`
}

// PackState là tình trạng gói đã gắn (kiểm tra tổng thể bước 6).
type PackState struct {
	Flow          string    `json:"flow"`
	PackVersionID uuid.UUID `json:"packVersionId"`
	PackCode      string    `json:"packCode,omitempty"`
	Version       string    `json:"version,omitempty"`
	Status        string    `json:"status"`         // trạng thái chuẩn Policy Pack, cần DA_PHAT_HANH
	GoldenPassPct float64   `json:"goldenPassRate"` // tỉ lệ đạt test case vàng, cần 100
}

// Core là cổng API nội bộ của mission-core trong cell: MOD-05 (policy.policy_binding) và MOD-08
// (config.ui_pack_version, config.holiday_calendar). Hợp đồng nội bộ chưa có trong contracts/ (wiki trang 14).
type Core interface {
	BindPolicyPacks(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID, bindings []PackBinding) error
	UnbindPolicyPacks(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) error
	PolicyPacks(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) ([]PackState, error)
	AssignUiPack(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID, sector string, versionID *uuid.UUID) error
	UnassignUiPack(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) error
	CreateDefaultCalendar(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID, year int) error
	DeleteDefaultCalendar(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) error
}

// Org là cổng đọc org-service (SVC-04) cho kiểm tra tổng thể: cây tổ chức đã có và số lệch mức Cao.
type Org interface {
	HasOrgTree(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) (bool, error)
	HighDiscrepancies(ctx context.Context, ep CellEndpoints, tenantID uuid.UUID) (int, error)
}

// ReplicaLag đo độ trễ bản sao dự phòng của cell (ngoại lệ RPO 15 phút của QT-01).
type ReplicaLag interface {
	Lag(ctx context.Context, cellCode string) (time.Duration, error)
}

// ---------------------------------------------------------------- lỗi

// StepError là lỗi một bước kèm lý do hiển thị cho người dùng (MSG-01-07 {lý do}); Err là chi tiết kỹ thuật
// chỉ ghi nhật ký. Permanent = không thử lại (ví dụ vi phạm BR-01-03).
type StepError struct {
	Reason    string
	Msg       string // mã MSG riêng khi có (MSG-01-05)
	Permanent bool
	Err       error
}

func (e *StepError) Error() string {
	if e.Err != nil {
		return e.Reason + ": " + e.Err.Error()
	}
	return e.Reason
}

func (e *StepError) Unwrap() error { return e.Err }

// Permanent bọc lỗi không nên thử lại.
func Permanent(reason string, err error) error {
	return &StepError{Reason: reason, Permanent: true, Err: err}
}

// Transient bọc lỗi có thể thử lại.
func Transient(reason string, err error) error { return &StepError{Reason: reason, Err: err} }

// PackNotReadyError: BR-01-03, gói chưa DA_PHAT_HANH hoặc chưa đạt 100% test case vàng (MSG-01-05).
type PackNotReadyError struct{ Pack string }

func (e *PackNotReadyError) Error() string {
	return fmt.Sprintf("Gói chính sách %s chưa đạt 100%% test case vàng nên chưa gắn được.", e.Pack)
}

// HTTPError là phản hồi lỗi của một hệ ngoài.
type HTTPError struct {
	System string
	Status int
	Body   string
}

func (e *HTTPError) Error() string {
	b := e.Body
	if len(b) > 300 {
		b = b[:300]
	}
	return fmt.Sprintf("%s trả HTTP %d: %s", e.System, e.Status, strings.TrimSpace(b))
}

// retryable: lỗi 4xx (trừ 408, 409, 425, 429) là lỗi cấu hình, thử lại không có ích.
func retryable(err error) bool {
	var se *StepError
	if errors.As(err, &se) && se.Permanent {
		return false
	}
	var he *HTTPError
	if errors.As(err, &he) && he.Status >= 400 && he.Status < 500 {
		switch he.Status {
		case 408, 409, 425, 429:
			return true
		}
		return false
	}
	return true
}
