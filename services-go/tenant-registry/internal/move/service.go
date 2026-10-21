// Package move hiện thực chuyển tenant giữa các cell bằng sao chép logic có bộ lọc dòng, hai người duyệt
// (DV-03-07; ADR-29, ADR-07; TL-01 "Chuyển tenant giữa các cell"; hợp đồng platform.yaml requestTenantMove,
// approveTenantMove, getTenantMove, cancelTenantMove). Saga sáu bước: PROVISION_TARGET, INITIAL_SNAPSHOT,
// WAL_CATCHUP, READ_ONLY_FREEZE, SWITCH_ACTIVE_CELL, REOPEN_WRITE.
//
// TL-03 chưa có bảng lưu đề nghị chuyển cell; đề nghị và trạng thái saga lưu trong registry.job (loại
// TENANT_MOVE, id job = moveId) và vết đổi trạng thái tenant ở registry.tenant_status_log; đề xuất bảng
// registry.tenant_move ghi ở wiki trang 14 (suy luận), chờ duyệt trước khi viết migration.
package move

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
)

// JobType là loại tác vụ của lệnh chuyển cell.
const JobType = "TENANT_MOVE"

// Trạng thái lệnh chuyển (platform.yaml TenantMoveStatus).
const (
	ChoDuyet   = "CHO_DUYET"
	DangChuyen = "DANG_CHUYEN"
	HoanTat    = "HOAN_TAT"
	ThatBai    = "THAT_BAI"
	DaHuy      = "DA_HUY"
)

// Bước saga (platform.yaml TenantMove.currentStep).
const (
	StepProvisionTarget = "PROVISION_TARGET"
	StepSnapshot        = "INITIAL_SNAPSHOT"
	StepCatchUp         = "WAL_CATCHUP"
	StepFreeze          = "READ_ONLY_FREEZE"
	StepSwitch          = "SWITCH_ACTIVE_CELL"
	StepReopen          = "REOPEN_WRITE"
)

// Steps là thứ tự bước.
var Steps = []string{StepProvisionTarget, StepSnapshot, StepCatchUp, StepFreeze, StepSwitch, StepReopen}

// TableCheck là kết quả so khớp một bảng.
type TableCheck struct {
	Table      string `json:"table"`
	SourceRows int64  `json:"sourceRows"`
	TargetRows int64  `json:"targetRows"`
	SourceSum  string `json:"sourceSum"`
	TargetSum  string `json:"targetSum"`
	OK         bool   `json:"ok"`
}

// State là trạng thái bền của lệnh chuyển (registry.job.params). Chỉ mang mã và tham chiếu, không mang mật
// khẩu hay chuỗi kết nối.
type State struct {
	TenantID    uuid.UUID  `json:"tenantId"`
	TenantCode  string     `json:"tenantCode"`
	FromCellID  uuid.UUID  `json:"fromCellId"`
	FromCell    string     `json:"fromCell"`
	FromURL     string     `json:"fromUrl"`
	FromDB      string     `json:"fromDb"`
	FromModel   string     `json:"fromModel"`
	ToCellID    uuid.UUID  `json:"toCellId"`
	ToCell      string     `json:"toCell"`
	ToURL       string     `json:"toUrl"`
	ToDB        string     `json:"toDb"`
	TargetModel string     `json:"targetModel"` // chữ hoa như registry.cell.tenancy_model
	DataRegion  string     `json:"dataRegion"`
	Reason      string     `json:"reason"`
	PlannedAt   *time.Time `json:"plannedAt,omitempty"`
	RequestedBy uuid.UUID  `json:"requestedBy"`
	RequestedAt time.Time  `json:"requestedAt"`
	ApprovedBy  *uuid.UUID `json:"approvedBy,omitempty"`
	ApprovedAt  *time.Time `json:"approvedAt,omitempty"`
	Comment     string     `json:"comment,omitempty"`
	Status      string     `json:"status"`
	RowVersion  int        `json:"rowVersion"`
	CurrentStep *string    `json:"currentStep"`

	Steps             []tenant.StepState `json:"steps,omitempty"`
	CancelRequestedBy *uuid.UUID         `json:"cancelRequestedBy,omitempty"`
	CancelConfirmedBy *uuid.UUID         `json:"cancelConfirmedBy,omitempty"`
	Tables            []Table            `json:"tables,omitempty"`
	ReplicaIdentity   []string           `json:"replicaIdentity,omitempty"`
	CreatedPartitions []string           `json:"createdPartitions,omitempty"`
	WriteLocked       bool               `json:"writeLocked,omitempty"`
	ReadOnlyFrom      *time.Time         `json:"readOnlyFrom,omitempty"`
	ReadOnlyTo        *time.Time         `json:"readOnlyTo,omitempty"`
	ReadOnlySeconds   float64            `json:"readOnlySeconds,omitempty"`
	Verification      []TableCheck       `json:"verification,omitempty"`
	Objects           *ObjectReport      `json:"objects,omitempty"`
	Ledger            *Handover          `json:"ledger,omitempty"`
	SwitchedAt        *time.Time         `json:"switchedAt,omitempty"`
	RetainUntil       *time.Time         `json:"retainUntil,omitempty"`
	PurgedAt          *time.Time         `json:"purgedAt,omitempty"`
	PurgedRows        int64              `json:"purgedRows,omitempty"`
	Failure           string             `json:"failure,omitempty"`
}

// Name là tên publication, subscription, slot, trigger rào chặn của lệnh chuyển.
func Name(moveID uuid.UUID) string { return "mv_" + strings.ReplaceAll(moveID.String(), "-", "") }

// CellRef là RegistryCellRef.
type CellRef struct {
	ID         uuid.UUID `json:"id"`
	Code       string    `json:"code"`
	APIBaseURL string    `json:"apiBaseUrl,omitempty"`
}

// View là TenantMove (platform.yaml).
type View struct {
	ID                 uuid.UUID  `json:"id"`
	TenantID           uuid.UUID  `json:"tenantId"`
	FromCell           CellRef    `json:"fromCell"`
	ToCell             CellRef    `json:"toCell"`
	TargetTenancyModel string     `json:"targetTenancyModel,omitempty"`
	DataRegion         string     `json:"dataRegion,omitempty"`
	Status             string     `json:"status"`
	Reason             string     `json:"reason"`
	PlannedAt          *time.Time `json:"plannedAt,omitempty"`
	RequestedBy        uuid.UUID  `json:"requestedBy"`
	RequestedAt        time.Time  `json:"requestedAt"`
	ApprovedBy         *uuid.UUID `json:"approvedBy,omitempty"`
	ApprovedAt         *time.Time `json:"approvedAt,omitempty"`
	CurrentStep        *string    `json:"currentStep"`
	RowVersion         int        `json:"rowVersion"`
}

// ViewOf dựng TenantMove.
func ViewOf(id uuid.UUID, st *State) View {
	return View{ID: id, TenantID: st.TenantID, FromCell: CellRef{ID: st.FromCellID, Code: st.FromCell, APIBaseURL: st.FromURL},
		ToCell: CellRef{ID: st.ToCellID, Code: st.ToCell, APIBaseURL: st.ToURL}, TargetTenancyModel: st.TargetModel,
		DataRegion: st.DataRegion, Status: st.Status, Reason: st.Reason, PlannedAt: st.PlannedAt, RequestedBy: st.RequestedBy,
		RequestedAt: st.RequestedAt, ApprovedBy: st.ApprovedBy, ApprovedAt: st.ApprovedAt, CurrentStep: st.CurrentStep, RowVersion: st.RowVersion}
}

// ---------------------------------------------------------------- lỗi

// Lỗi nghiệp vụ của lệnh chuyển (ánh xạ Problem ở tầng API).
var (
	ErrSameApprover = errors.New("move: người duyệt trùng người đề nghị")
	ErrSameCell     = errors.New("move: cell đích trùng cell hiện tại")
	ErrInProgress   = errors.New("move: tenant đang có lần chuyển khác")
	ErrCutover      = errors.New("move: đã cắt chuyển, không huỷ được")
	ErrNotFound     = errors.New("move: không có lệnh chuyển")
)

// MoveStateError: thao tác không hợp với trạng thái lệnh chuyển.
type MoveStateError struct{ Status string }

func (e *MoveStateError) Error() string { return "move: lệnh chuyển đang " + e.Status }

// ---------------------------------------------------------------- nghiệp vụ

// Service là nghiệp vụ đề nghị, duyệt, xem, huỷ lệnh chuyển; Runner chạy saga.
type Service struct {
	Tenants *tenant.Service
	Now     func() time.Time
}

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now().UTC()
	}
	return time.Now().UTC()
}

// RequestInput là TenantMoveCreate.
type RequestInput struct {
	TargetCellID       uuid.UUID  `json:"targetCellId"`
	TargetTenancyModel string     `json:"targetTenancyModel,omitempty"`
	DataRegion         string     `json:"dataRegion,omitempty"`
	Reason             string     `json:"reason"`
	PlannedAt          *time.Time `json:"plannedAt,omitempty"`
}

// ActiveMove trả lệnh chuyển đang chờ duyệt hoặc đang chạy của tenant (ErrNotFound nếu không có).
func ActiveMove(ctx context.Context, q store.Querier, tenantID uuid.UUID) (uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `SELECT id FROM registry.job WHERE job_type = $1 AND params->>'tenantId' = $2
		AND status IN ('CHO', 'DANG_CHAY') ORDER BY created_at DESC LIMIT 1`, JobType, tenantID.String()).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return uuid.Nil, ErrNotFound
	}
	return id, err
}

// Request: requestTenantMove. Tạo đề nghị CHO_DUYET; không đổi trạng thái tenant (expected là row_version của
// tenant trong If-Match).
func (s *Service) Request(ctx context.Context, tx pgx.Tx, a tenant.Actor, tenantID uuid.UUID, expected int, in RequestInput) (*View, error) {
	if !a.PlatformAdmin {
		return nil, tenant.ErrForbidden
	}
	t, err := store.GetTenantForUpdate(ctx, tx, tenantID)
	if errors.Is(err, store.ErrNotFound) {
		return nil, tenant.ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if t.RowVersion != expected {
		return nil, &tenant.VersionError{Sent: expected, Current: t.RowVersion}
	}
	var fe []tenant.FieldError
	reason := strings.TrimSpace(in.Reason)
	if n := len([]rune(reason)); n == 0 || n > 2000 {
		fe = append(fe, tenant.FieldError{Field: "reason", Message: "Lý do bắt buộc, tối đa 2000 ký tự"})
	}
	if in.TargetCellID == uuid.Nil {
		fe = append(fe, tenant.FieldError{Field: "targetCellId", Message: "Thiếu cell đích"})
	}
	if len(in.DataRegion) > 32 {
		fe = append(fe, tenant.FieldError{Field: "dataRegion", Message: "Vùng dữ liệu tối đa 32 ký tự"})
	}
	if in.PlannedAt != nil && in.PlannedAt.Before(s.now().Add(-time.Minute)) {
		fe = append(fe, tenant.FieldError{Field: "plannedAt", Message: "Thời điểm cắt chuyển phải ở tương lai"})
	}
	if len(fe) > 0 {
		return nil, &tenant.ValidationError{Fields: fe}
	}
	if t.Status != tenant.DangVanHanh {
		return nil, &tenant.StateError{Status: t.Status}
	}
	if _, err := ActiveMove(ctx, tx, tenantID); err == nil {
		return nil, ErrInProgress
	} else if !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	cur, err := store.CurrentTenantCell(ctx, tx, tenantID, s.now())
	if err != nil {
		return nil, err
	}
	if cur.CellID == in.TargetCellID {
		return nil, ErrSameCell
	}
	cells, err := store.ListCells(ctx, tx, store.CellFilter{})
	if err != nil {
		return nil, err
	}
	var target *store.Cell
	for i := range cells {
		if cells[i].ID == in.TargetCellID {
			target = &cells[i]
		}
	}
	if target == nil {
		return nil, &tenant.ValidationError{Fields: []tenant.FieldError{{Field: "targetCellId", Message: "Không có cell này"}}}
	}
	if target.Status != "HOAT_DONG" {
		return nil, &tenant.CellUnavailableError{Cell: target.Code}
	}
	model := strings.ToUpper(strings.TrimSpace(in.TargetTenancyModel))
	if model == "" {
		model = target.TenancyModel
	}
	if model != target.TenancyModel || model == "AIRGAP" {
		return nil, &tenant.ValidationError{Fields: []tenant.FieldError{{Field: "targetTenancyModel", Message: "Cell đích không phục vụ mô hình thuê bao này"}}}
	}
	exp := t.AccountCount
	if t.ExpectedAccounts != nil {
		exp = max(exp, *t.ExpectedAccounts)
	}
	rules := s.Tenants.Rules
	if err := rules.CheckModel(tenant.Lower(model), exp, t.Sector, t.Code); err != nil {
		return nil, err
	}
	load := tenant.CellLoad{Code: target.Code, Capacity: target.CapacityAccounts, Reserved: target.Reserved}
	if !rules.FitsAfterAdding(load, exp) {
		return nil, &tenant.CapacityError{Cell: target.Code, Model: tenant.Lower(model), LoadAfter: tenant.LoadAfter(load, exp)}
	}
	region := strings.TrimSpace(in.DataRegion)
	if region == "" {
		region = target.PrimaryDC
	}
	st := State{TenantID: t.ID, TenantCode: t.Code, FromCellID: cur.CellID, FromCell: cur.CellCode, FromURL: cur.CellURL, FromDB: cur.DatabaseName,
		FromModel: strings.ToUpper(cur.TenancyModel), ToCellID: target.ID, ToCell: target.Code, ToURL: target.APIBaseURL,
		ToDB: tenant.DatabaseName(tenant.Lower(model), t.Code), TargetModel: model, DataRegion: region, Reason: reason,
		PlannedAt: in.PlannedAt, RequestedBy: a.ID, RequestedAt: s.now(), Status: ChoDuyet}
	params, _ := json.Marshal(st)
	j := &store.Job{Type: JobType, Params: params, CreatedBy: a.ID}
	if err := store.InsertJob(ctx, tx, j); err != nil {
		return nil, err
	}
	msg := fmt.Sprintf("Chờ người thứ hai duyệt chuyển cell %s → %s", st.FromCell, st.ToCell)
	j.Message = &msg
	if err := store.UpdateJobState(ctx, tx, j); err != nil {
		return nil, err
	}
	v := ViewOf(j.ID, &st)
	return &v, nil
}

// load đọc lệnh chuyển (khoá dòng job) của đúng tenant.
func load(ctx context.Context, tx pgx.Tx, tenantID, moveID uuid.UUID) (*store.Job, *State, error) {
	j, err := store.GetJobForUpdate(ctx, tx, moveID)
	if errors.Is(err, store.ErrNotFound) {
		return nil, nil, ErrNotFound
	}
	if err != nil {
		return nil, nil, err
	}
	if j.Type != JobType {
		return nil, nil, ErrNotFound
	}
	var st State
	if err := json.Unmarshal(j.Params, &st); err != nil {
		return nil, nil, err
	}
	if st.TenantID != tenantID {
		return nil, nil, ErrNotFound
	}
	return j, &st, nil
}

// save ghi trạng thái lệnh chuyển; jobStatus theo trạng thái lệnh.
func save(ctx context.Context, q store.Querier, j *store.Job, st *State, msg string) error {
	b, err := json.Marshal(st)
	if err != nil {
		return err
	}
	switch st.Status {
	case ChoDuyet:
		j.Status = "CHO"
	case DangChuyen:
		if j.Status != "DANG_CHAY" {
			j.Status = "CHO"
		}
	case HoanTat:
		j.Status, j.Progress = "HOAN_TAT", 100
	case ThatBai:
		j.Status = "THAT_BAI"
	case DaHuy:
		j.Status = "DA_HUY"
	}
	j.Params = b
	if msg != "" {
		j.Message = &msg
	}
	return store.UpdateJobState(ctx, q, j)
}

// Approve: approveTenantMove. Người duyệt khác người đề nghị (bốn mắt); tenant DANG_VAN_HANH →
// DANG_CHUYEN_CELL; lệnh sang DANG_CHUYEN, chờ Runner chạy. expected là rowVersion của lệnh.
func (s *Service) Approve(ctx context.Context, tx pgx.Tx, a tenant.Actor, tenantID, moveID uuid.UUID, expected int, comment string) (*store.Job, error) {
	if !a.PlatformAdmin {
		return nil, tenant.ErrForbidden
	}
	j, st, err := load(ctx, tx, tenantID, moveID)
	if err != nil {
		return nil, err
	}
	if st.RequestedBy == a.ID {
		return nil, ErrSameApprover
	}
	if st.RowVersion != expected {
		return nil, &tenant.VersionError{Sent: expected, Current: st.RowVersion}
	}
	if st.Status != ChoDuyet {
		return nil, &MoveStateError{Status: st.Status}
	}
	t, err := store.GetTenantForUpdate(ctx, tx, tenantID)
	if err != nil {
		return nil, err
	}
	if t.Status != tenant.DangVanHanh {
		return nil, &tenant.StateError{Status: t.Status}
	}
	if cur, err := store.CurrentTenantCell(ctx, tx, tenantID, s.now()); err != nil || cur.CellID != st.FromCellID {
		return nil, &MoveStateError{Status: "đã đổi cell nguồn"}
	}
	reason := fmt.Sprintf("Bắt đầu chuyển từ %s sang %s: %s", st.FromCell, st.ToCell, st.Reason)
	if err := s.Tenants.Transition(ctx, tx, a, t, t.RowVersion, tenant.DangChuyenCell, truncate(reason, 2000)); err != nil {
		return nil, err
	}
	now := s.now()
	id := a.ID
	st.ApprovedBy, st.ApprovedAt, st.Comment = &id, &now, strings.TrimSpace(comment)
	st.Status, st.RowVersion = DangChuyen, st.RowVersion+1
	if err := save(ctx, tx, j, st, "Đã duyệt, đang chuẩn bị chuyển cell"); err != nil {
		return nil, err
	}
	return j, nil
}

// Get: getTenantMove.
func (s *Service) Get(ctx context.Context, q store.Querier, a tenant.Actor, tenantID, moveID uuid.UUID) (*View, *State, error) {
	if !a.PlatformAdmin {
		return nil, nil, tenant.ErrForbidden
	}
	j, err := store.GetJob(ctx, q, moveID)
	if errors.Is(err, store.ErrNotFound) {
		return nil, nil, ErrNotFound
	}
	if err != nil {
		return nil, nil, err
	}
	var st State
	if j.Type != JobType || json.Unmarshal(j.Params, &st) != nil || st.TenantID != tenantID {
		return nil, nil, ErrNotFound
	}
	v := ViewOf(j.ID, &st)
	return &v, &st, nil
}

// CancelResult cho biết lệnh huỷ cần Runner quay lui.
type CancelResult struct {
	View     View
	Rollback bool // đã xác nhận huỷ một lệnh đang chạy: Runner quay lui
}

// Cancel: cancelTenantMove. CHO_DUYET: huỷ ngay. DANG_CHUYEN trước cắt chuyển: bốn mắt, người thứ nhất ghi đề
// nghị huỷ, người thứ hai khác người thứ nhất xác nhận thì Runner quay lui về trạng thái cũ (suy luận). Đã cắt
// chuyển: ErrCutover.
func (s *Service) Cancel(ctx context.Context, tx pgx.Tx, a tenant.Actor, tenantID, moveID uuid.UUID, expected int, comment string) (*CancelResult, error) {
	if !a.PlatformAdmin {
		return nil, tenant.ErrForbidden
	}
	j, st, err := load(ctx, tx, tenantID, moveID)
	if err != nil {
		return nil, err
	}
	if st.RowVersion != expected {
		return nil, &tenant.VersionError{Sent: expected, Current: st.RowVersion}
	}
	note := strings.TrimSpace(comment)
	switch st.Status {
	case ChoDuyet:
		st.Status, st.RowVersion, st.Failure = DaHuy, st.RowVersion+1, note
		if err := save(ctx, tx, j, st, "Đã huỷ đề nghị chuyển cell"); err != nil {
			return nil, err
		}
		return &CancelResult{View: ViewOf(j.ID, st)}, nil
	case DangChuyen:
		if st.SwitchedAt != nil {
			return nil, ErrCutover
		}
		id := a.ID
		if st.CancelRequestedBy == nil {
			st.CancelRequestedBy, st.RowVersion = &id, st.RowVersion+1
			if err := save(ctx, tx, j, st, "Đã ghi nhận đề nghị huỷ, chờ người thứ hai xác nhận"); err != nil {
				return nil, err
			}
			return &CancelResult{View: ViewOf(j.ID, st)}, nil
		}
		if *st.CancelRequestedBy == id {
			return nil, ErrSameApprover
		}
		st.CancelConfirmedBy, st.RowVersion = &id, st.RowVersion+1
		if note != "" {
			st.Failure = note
		}
		if err := save(ctx, tx, j, st, "Đã xác nhận huỷ, đang quay lui"); err != nil {
			return nil, err
		}
		return &CancelResult{View: ViewOf(j.ID, st), Rollback: true}, nil
	case HoanTat:
		return nil, ErrCutover
	}
	return nil, &MoveStateError{Status: st.Status}
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}
