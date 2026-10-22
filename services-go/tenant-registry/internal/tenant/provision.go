package tenant

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"mission/services-go/tenant-registry/internal/store"
)

// DV-03-05: khởi chạy saga cấp phát (provisionConfigTenant, QT-01 bước 4). Phần kiểm tra cấu hình bước 1–3 và
// chuyển KHOI_TAO → DANG_CAP_PHAT nằm ở đây (cùng giao dịch với khoá chống ghi trùng); các bước của saga do
// gói provision chạy ngoài vòng đời request.

// ReadinessCheck là một mục kiểm tra tổng thể ở bước 6 (config.yaml ReadinessCheck).
type ReadinessCheck struct {
	Code   string `json:"code"`
	Passed bool   `json:"passed"`
	Detail string `json:"detail,omitempty"`
}

// ProvisionState là trạng thái bền của saga, lưu trong registry.job.params của tác vụ PROVISION (bảng bổ sung
// registry.job, suy luận; bảng flowable.saga_instance của SVC-06 chưa có giá trị saga_type cho cấp phát, xem
// wiki trang 14). Chỉ mang mã và tham chiếu, không mang khoá hay mật khẩu.
type ProvisionState struct {
	TenantID   uuid.UUID   `json:"tenantId"`
	StartedAt  time.Time   `json:"startedAt"`
	Phase      string      `json:"phase"`
	Steps      []StepState `json:"steps"`
	FailedStep string      `json:"failedStep,omitempty"`
	Reason     string      `json:"reason,omitempty"`
	FinishedAt *time.Time  `json:"finishedAt,omitempty"`
}

// StepState là trạng thái một bước con của saga.
type StepState struct {
	Name     string            `json:"name"`
	Status   string            `json:"status"`
	Attempts int               `json:"attempts"`
	Out      map[string]string `json:"out,omitempty"`
	Error    string            `json:"error,omitempty"`
}

// Pha của saga.
const (
	PhaseRun          = "CHAY"
	PhaseCompensate   = "BU_TRU"
	PhaseDone         = "XONG"
	PhaseCompensated  = "DA_BU_TRU"
	PhaseCompensateKO = "BU_TRU_LOI"
)

// Trạng thái bước con.
const (
	StepPending     = "CHO"
	StepDone        = "XONG"
	StepFailed      = "LOI"
	StepCompensated = "DA_BU_TRU"
)

// StartProvision: provisionConfigTenant. Kiểm cấu hình bước 1–3 (BR-01-01, BR-01-02, BR-01-04, tên miền,
// đầu mối quản trị), chuyển KHOI_TAO → DANG_CAP_PHAT và ghi tác vụ PROVISION ở trạng thái CHO.
func (s *Service) StartProvision(ctx context.Context, tx pgx.Tx, a Actor, id uuid.UUID, expected int) (*store.Job, error) {
	if !a.PlatformAdmin {
		return nil, ErrForbidden
	}
	t, err := store.GetTenantForUpdate(ctx, tx, id)
	if errors.Is(err, store.ErrNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if t.RowVersion != expected {
		return nil, &VersionError{Sent: expected, Current: t.RowVersion}
	}
	if t.Status != KhoiTao {
		return nil, &StateError{Status: t.Status}
	}
	o, err := store.GetOnboarding(ctx, tx, id)
	if errors.Is(err, store.ErrNotFound) {
		o = &store.Onboarding{TenantID: id, KekMode: DefaultKekMode(Lower(t.TenancyModel)), PolicyBindings: json.RawMessage("[]")}
	} else if err != nil {
		return nil, err
	}
	model := Lower(t.TenancyModel)
	exp := 0
	if t.ExpectedAccounts != nil {
		exp = *t.ExpectedAccounts
	}
	if err := s.Rules.CheckModel(model, exp, t.Sector, t.Code); err != nil {
		return nil, err
	}
	if err := CheckKekMode(model, o.KekMode); err != nil {
		return nil, err
	}
	var missing []FieldError
	binding, err := store.LatestTenantCell(ctx, tx, id)
	if errors.Is(err, store.ErrNotFound) {
		missing = append(missing, FieldError{Field: "cellCode", Message: "Chưa chọn cell"})
	} else if err != nil {
		return nil, err
	} else if err := s.checkCellStillFits(ctx, tx, t, binding); err != nil {
		return nil, err
	}
	ds, err := store.ListDomains(ctx, tx, id)
	if err != nil {
		return nil, err
	}
	hasPrimary := false
	for _, d := range ds {
		hasPrimary = hasPrimary || d.Kind == "PRIMARY"
	}
	if !hasPrimary {
		missing = append(missing, FieldError{Field: "domain", Message: "Chưa có tên miền truy cập"})
	}
	if o.AdminContactEnc == nil {
		missing = append(missing, FieldError{Field: "adminContact", Message: "Chưa có đầu mối quản trị tenant"})
	}
	if len(missing) > 0 {
		return nil, &ValidationError{Fields: missing}
	}
	// BR-01-04: mỗi luồng của tenant gắn đúng một Policy Pack.
	var pbs []PolicyBinding
	_ = json.Unmarshal(o.PolicyBindings, &pbs)
	seen := map[string]bool{}
	for _, b := range pbs {
		seen[b.Flow] = true
	}
	for _, f := range t.Flows {
		if !seen[f] {
			return nil, &PackMissingError{Flow: f}
		}
	}
	if err := s.Transition(ctx, tx, a, t, expected, DangCapPhat, "Bắt đầu cấp phát tenant"); err != nil {
		return nil, err
	}
	st := ProvisionState{TenantID: id, StartedAt: s.Now().UTC(), Phase: PhaseRun, Steps: []StepState{}}
	params, _ := json.Marshal(st)
	j := &store.Job{Type: store.JobTypeProvision, Params: params, CreatedBy: a.ID}
	if err := store.InsertJob(ctx, tx, j); err != nil {
		return nil, err
	}
	return j, nil
}

// checkCellStillFits áp lại BR-01-02 lúc khởi chạy saga (tải cell có thể đã đổi từ khi lưu nháp).
func (s *Service) checkCellStillFits(ctx context.Context, tx pgx.Tx, t *store.Tenant, b *store.TenantCell) error {
	cells, err := store.ListCells(ctx, tx, store.CellFilter{})
	if err != nil {
		return err
	}
	for _, c := range cells {
		if c.ID != b.CellID {
			continue
		}
		if c.Status != "HOAT_DONG" {
			return &CellUnavailableError{Cell: c.Code}
		}
		if c.TenancyModel != Upper(b.TenancyModel) {
			return &ValidationError{Fields: []FieldError{{Field: "cellCode", Message: "Cell không phục vụ mô hình thuê bao đã chọn"}}}
		}
		// Reserved đã gồm phần giữ chỗ của chính tenant (gắn nháp hiệu lực từ lúc chọn).
		l := CellLoad{Code: c.Code, Capacity: c.CapacityAccounts, Reserved: c.Reserved}
		if !s.Rules.FitsAfterAdding(l, 0) {
			return &CapacityError{Cell: c.Code, Model: Lower(c.TenancyModel), LoadAfter: LoadAfter(l, 0)}
		}
		return nil
	}
	return &ValidationError{Fields: []FieldError{{Field: "cellCode", Message: "Không có cell này"}}}
}

// AdminContactOf giải mã đầu mối quản trị tenant (C2) để saga tạo tài khoản quản trị trong Keycloak; không ghi
// giá trị này vào nhật ký, outbox hay trạng thái saga.
func (s *Service) AdminContactOf(ctx context.Context, q store.Querier, tenantID uuid.UUID) (*AdminContact, error) {
	o, err := store.GetOnboarding(ctx, q, tenantID)
	if err != nil {
		return nil, err
	}
	c := s.openContact(o, tenantID)
	if c == nil {
		return nil, errors.New("tenant: không đọc được đầu mối quản trị")
	}
	return c, nil
}

// PolicyBindingsOf trả Policy Pack đã chọn theo luồng (bản nháp của policy.policy_binding).
func PolicyBindingsOf(o *store.Onboarding) []PolicyBinding {
	var pbs []PolicyBinding
	if o != nil {
		_ = json.Unmarshal(o.PolicyBindings, &pbs)
	}
	return pbs
}
