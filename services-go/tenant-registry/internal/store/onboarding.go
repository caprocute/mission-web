package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// Onboarding ánh xạ registry.tenant_onboarding (bảng bổ sung, suy luận).
type Onboarding struct {
	ID              uuid.UUID
	TenantID        uuid.UUID
	ExpectedUnits   *int
	DataRegion      *string
	KekMode         string
	PolicyBindings  json.RawMessage
	UiPackVersionID *uuid.UUID
	StaffSources    json.RawMessage
	AdminContactEnc []byte
	DekID           *uuid.UUID
	RowVersion      int
}

// InsertOnboarding thêm bản nháp.
func InsertOnboarding(ctx context.Context, q Querier, o *Onboarding, actor uuid.UUID) error {
	if o.ID == uuid.Nil {
		o.ID = NewID()
	}
	if o.PolicyBindings == nil {
		o.PolicyBindings = json.RawMessage("[]")
	}
	if o.StaffSources == nil {
		o.StaffSources = json.RawMessage("[]")
	}
	_, err := q.Exec(ctx, `INSERT INTO registry.tenant_onboarding (id, tenant_id, expected_units, data_region, kek_mode, policy_bindings,
		ui_pack_version_id, staff_sources, admin_contact_enc, dek_id, created_by, updated_by)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$11)`,
		o.ID, o.TenantID, o.ExpectedUnits, o.DataRegion, o.KekMode, []byte(o.PolicyBindings), o.UiPackVersionID,
		[]byte(o.StaffSources), o.AdminContactEnc, o.DekID, actor)
	return mapErr(err)
}

// GetOnboarding đọc bản nháp của tenant (ErrNotFound nếu tenant tạo qua createRegistryTenant).
func GetOnboarding(ctx context.Context, q Querier, tenantID uuid.UUID) (*Onboarding, error) {
	var o Onboarding
	var pb, ss []byte
	err := q.QueryRow(ctx, `SELECT id, tenant_id, expected_units, data_region, kek_mode, policy_bindings, ui_pack_version_id,
		staff_sources, admin_contact_enc, dek_id, row_version FROM registry.tenant_onboarding WHERE tenant_id = $1`, tenantID).
		Scan(&o.ID, &o.TenantID, &o.ExpectedUnits, &o.DataRegion, &o.KekMode, &pb, &o.UiPackVersionID, &ss, &o.AdminContactEnc, &o.DekID, &o.RowVersion)
	if err != nil {
		return nil, mapErr(err)
	}
	o.PolicyBindings, o.StaffSources = pb, ss
	return &o, nil
}

// UpdateOnboarding ghi bản nháp (khoá ở tầng tenant: dòng tenant đã FOR UPDATE).
func UpdateOnboarding(ctx context.Context, q Querier, o *Onboarding, actor uuid.UUID) error {
	_, err := q.Exec(ctx, `UPDATE registry.tenant_onboarding SET expected_units = $2, data_region = $3, kek_mode = $4,
		policy_bindings = $5, ui_pack_version_id = $6, staff_sources = $7, admin_contact_enc = $8, dek_id = $9,
		updated_by = $10, updated_at = now(), row_version = row_version + 1 WHERE id = $1`,
		o.ID, o.ExpectedUnits, o.DataRegion, o.KekMode, []byte(o.PolicyBindings), o.UiPackVersionID, []byte(o.StaffSources),
		o.AdminContactEnc, o.DekID, actor)
	return mapErr(err)
}

// NextOnboardingSeq trả số thứ tự kế tiếp của mã ONB-<năm>-nnnn; khoá tư vấn theo năm trong giao dịch tránh
// hai yêu cầu cùng lấy một số.
func NextOnboardingSeq(ctx context.Context, tx pgx.Tx, year int) (int, error) {
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtext('registry.onboarding_code'), $1)`, year); err != nil {
		return 0, err
	}
	var max *int
	err := tx.QueryRow(ctx, `SELECT max(substring(onboarding_code from 10)::int) FROM registry.tenant
		WHERE onboarding_code ~ ('^ONB-' || $1 || '-[0-9]{4}$')`, fmt.Sprintf("%04d", year)).Scan(&max)
	if err != nil {
		return 0, err
	}
	if max == nil {
		return 1, nil
	}
	if *max >= 9999 {
		return 0, errors.New("store: hết số mã hồ sơ khởi tạo trong năm")
	}
	return *max + 1, nil
}

// TenantNameTaken kiểm tên tenant đã dùng (không phân biệt hoa thường), trừ chính tenant except.
func TenantNameTaken(ctx context.Context, q Querier, name string, except uuid.UUID) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM registry.tenant WHERE lower(name) = lower($1) AND id <> $2 AND status <> 'DA_CHAM_DUT')`,
		name, except).Scan(&ok)
	return ok, mapErr(err)
}

// ---------------------------------------------------------------- danh sách tenant SCR-0101

// TenantFilter là bộ lọc listConfigTenants.
type TenantFilter struct {
	Cell     string // mã cell
	Model    string // silo, bridge, pool
	Status   string
	Q        string
	BeforeID *uuid.UUID // con trỏ: id nhỏ hơn (UUIDv7 giảm dần = mới nhất trước)
	Limit    int        // 0: không giới hạn (xuất)
}

// TenantRow là một dòng danh sách.
type TenantRow struct {
	ID            uuid.UUID
	Code          string
	Name          string
	Domain        string
	CellCode      string
	Model         string // chữ thường
	AccountCount  int
	Status        string
	HasCell       bool
	HasDomain     bool
	HasKek        bool
	IdpCount      int
	StaffSrcCount int
}

// filterSQL dựng mệnh đề lọc dùng chung cho danh sách và đếm.
func filterSQL(f TenantFilter, args *[]any) string {
	var where []string
	add := func(cond string, v any) {
		*args = append(*args, v)
		where = append(where, strings.ReplaceAll(cond, "?", fmt.Sprintf("$%d", len(*args))))
	}
	if f.Cell != "" {
		add("c.code = ?", f.Cell)
	}
	if f.Model != "" {
		add("coalesce(tc.tenancy_model, lower(t.tenancy_model)) = ?", f.Model)
	}
	if f.Status != "" {
		add("t.status = ?", f.Status)
	}
	if f.Q != "" {
		add("(strpos(public.unaccent(lower(t.name)), public.unaccent(lower(?))) > 0 OR strpos(t.code, lower(?)) > 0 OR strpos(coalesce(d.domain, ''), lower(?)) > 0)", f.Q)
	}
	if len(where) == 0 {
		return "TRUE"
	}
	return strings.Join(where, " AND ")
}

const tenantRowFrom = `FROM registry.tenant t
	LEFT JOIN LATERAL (SELECT x.cell_id, x.tenancy_model FROM registry.tenant_cell x
	                   WHERE x.tenant_id = t.id AND x.valid_to > now() ORDER BY x.valid_from LIMIT 1) tc ON TRUE
	LEFT JOIN registry.cell c ON c.id = tc.cell_id
	LEFT JOIN registry.tenant_domain d ON d.tenant_id = t.id AND d.kind = 'PRIMARY'
	LEFT JOIN registry.tenant_onboarding o ON o.tenant_id = t.id`

// ListTenantRows trả dòng danh sách theo id giảm dần.
func ListTenantRows(ctx context.Context, q Querier, f TenantFilter) ([]TenantRow, error) {
	args := []any{}
	where := filterSQL(f, &args)
	if f.BeforeID != nil {
		args = append(args, *f.BeforeID)
		where += fmt.Sprintf(" AND t.id < $%d", len(args))
	}
	limit := ""
	if f.Limit > 0 {
		args = append(args, f.Limit)
		limit = fmt.Sprintf(" LIMIT $%d", len(args))
	}
	rows, err := q.Query(ctx, `SELECT t.id, t.code, t.name, coalesce(d.domain, ''), coalesce(c.code, ''),
		  coalesce(tc.tenancy_model, lower(t.tenancy_model)), t.account_count, t.status,
		  tc.cell_id IS NOT NULL, d.id IS NOT NULL, o.kek_mode IS NOT NULL OR o.id IS NULL,
		  (SELECT count(*) FROM registry.tenant_idp i WHERE i.tenant_id = t.id)::int,
		  coalesce(jsonb_array_length(o.staff_sources), 0)
		`+tenantRowFrom+` WHERE `+where+` ORDER BY t.id DESC`+limit, args...)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (TenantRow, error) {
		var x TenantRow
		err := r.Scan(&x.ID, &x.Code, &x.Name, &x.Domain, &x.CellCode, &x.Model, &x.AccountCount, &x.Status,
			&x.HasCell, &x.HasDomain, &x.HasKek, &x.IdpCount, &x.StaffSrcCount)
		return x, err
	})
}

// CountTenantRows đếm dòng khớp bộ lọc (registry dưới 100.000 dòng nên đếm chính xác được).
func CountTenantRows(ctx context.Context, q Querier, f TenantFilter) (int, error) {
	args := []any{}
	where := filterSQL(f, &args)
	var n int
	err := q.QueryRow(ctx, `SELECT count(*) `+tenantRowFrom+` WHERE `+where, args...).Scan(&n)
	return n, mapErr(err)
}

// Summary là số liệu tổng hợp đầu trang SCR-0101.
type Summary struct {
	Active, Total, Onboarding, Cells, DataCenters int
	Accounts                                      int64
}

// TenantSummary tính bốn chỉ số đầu trang.
func TenantSummary(ctx context.Context, q Querier) (Summary, error) {
	var s Summary
	err := q.QueryRow(ctx, `SELECT
		  count(*) FILTER (WHERE status = 'DANG_VAN_HANH'),
		  count(*) FILTER (WHERE status <> 'DA_CHAM_DUT'),
		  count(*) FILTER (WHERE status IN ('KHOI_TAO','DANG_CAP_PHAT','DANG_CAU_HINH','SAN_SANG')),
		  coalesce(sum(account_count) FILTER (WHERE status <> 'DA_CHAM_DUT'), 0),
		  (SELECT count(*) FROM registry.cell WHERE status <> 'NGUNG'),
		  (SELECT count(DISTINCT primary_dc) FROM registry.cell WHERE status <> 'NGUNG')
		FROM registry.tenant`).Scan(&s.Active, &s.Total, &s.Onboarding, &s.Accounts, &s.Cells, &s.DataCenters)
	return s, mapErr(err)
}

// ---------------------------------------------------------------- job

// Job ánh xạ registry.job.
type Job struct {
	ID             uuid.UUID
	Type           string
	Status         string
	Progress       int
	Message        *string
	Params         json.RawMessage
	ResultName     *string
	ResultType     *string
	ResultData     []byte
	ResultLocation *string
	Problem        json.RawMessage
	CreatedBy      uuid.UUID
	UpdatedAt      time.Time
}

// InsertJob thêm tác vụ ở trạng thái CHO.
func InsertJob(ctx context.Context, q Querier, j *Job) error {
	if j.ID == uuid.Nil {
		j.ID = NewID()
	}
	j.Status = "CHO"
	err := q.QueryRow(ctx, `INSERT INTO registry.job (id, job_type, status, params, created_by) VALUES ($1,$2,'CHO',$3,$4) RETURNING updated_at`,
		j.ID, j.Type, []byte(j.Params), j.CreatedBy).Scan(&j.UpdatedAt)
	return mapErr(err)
}

// UpdateJob ghi tiến độ hoặc kết quả.
func UpdateJob(ctx context.Context, q Querier, j *Job) error {
	_, err := q.Exec(ctx, `UPDATE registry.job SET status = $2, progress = $3, message = $4, result_name = $5, result_content_type = $6,
		result_data = $7, result_location = $8, problem = $9, updated_at = now() WHERE id = $1`,
		j.ID, j.Status, j.Progress, j.Message, j.ResultName, j.ResultType, j.ResultData, j.ResultLocation, nullJSON(j.Problem))
	return mapErr(err)
}

func nullJSON(b json.RawMessage) any {
	if len(b) == 0 {
		return nil
	}
	return []byte(b)
}

// GetJob đọc tác vụ (không đọc dữ liệu kết quả).
func GetJob(ctx context.Context, q Querier, id uuid.UUID) (*Job, error) {
	var j Job
	var params, prob []byte
	err := q.QueryRow(ctx, `SELECT id, job_type, status, progress, message, params, result_name, result_content_type, result_location,
		problem, created_by, updated_at FROM registry.job WHERE id = $1`, id).
		Scan(&j.ID, &j.Type, &j.Status, &j.Progress, &j.Message, &params, &j.ResultName, &j.ResultType, &j.ResultLocation, &prob, &j.CreatedBy, &j.UpdatedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	j.Params, j.Problem = params, prob
	return &j, nil
}

// JobResult đọc dữ liệu kết quả.
func JobResult(ctx context.Context, q Querier, id uuid.UUID) ([]byte, error) {
	var b []byte
	err := q.QueryRow(ctx, `SELECT result_data FROM registry.job WHERE id = $1`, id).Scan(&b)
	return b, mapErr(err)
}
