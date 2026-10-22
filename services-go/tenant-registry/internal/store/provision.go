package store

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// DV-03-05: truy cập dữ liệu cho saga cấp phát phần 2 (tên miền, TLS, hạn mức) và tác vụ PROVISION.

// JobTypeProvision là loại tác vụ của provisionConfigTenant (config.yaml: "Trả 202, job loại PROVISION").
const JobTypeProvision = "PROVISION"

// SetDomainVerified ghi thời điểm xác minh tên miền (nil để xoá khi bù trừ).
func SetDomainVerified(ctx context.Context, q Querier, domainID uuid.UUID, verifiedAt *time.Time, actor uuid.UUID) error {
	_, err := q.Exec(ctx, `UPDATE registry.tenant_domain SET verified_at = $2, updated_by = $3, updated_at = now(),
		row_version = row_version + 1 WHERE id = $1`, domainID, verifiedAt, actor)
	return mapErr(err)
}

// SetDomainCert ghi tham chiếu chứng chỉ TLS của tên miền (nil để xoá khi bù trừ).
func SetDomainCert(ctx context.Context, q Querier, domainID uuid.UUID, certRef *string, actor uuid.UUID) error {
	_, err := q.Exec(ctx, `UPDATE registry.tenant_domain SET tls_cert_ref = $2, updated_by = $3, updated_at = now(),
		row_version = row_version + 1 WHERE id = $1`, domainID, certRef, actor)
	return mapErr(err)
}

// Quota ánh xạ registry.tenant_quota.
type Quota struct {
	TenantID      uuid.UUID
	Key           string
	Limit         int64
	SoftLimitPct  int16
	Used          int64
	EffectiveFrom time.Time
	EffectiveTo   *time.Time
}

// UpsertQuota đặt hạn mức (mở lại nếu đã đóng); idempotent theo (tenant_id, quota_key).
func UpsertQuota(ctx context.Context, q Querier, x Quota, actor uuid.UUID) error {
	if x.SoftLimitPct == 0 {
		x.SoftLimitPct = 85
	}
	_, err := q.Exec(ctx, `INSERT INTO registry.tenant_quota (id, tenant_id, quota_key, limit_value, soft_limit_pct, effective_from, updated_by)
		VALUES ($1,$2,$3,$4,$5,$6,$7)
		ON CONFLICT ON CONSTRAINT uq_tenant_quota__tenant_id_quota_key DO UPDATE SET limit_value = EXCLUDED.limit_value,
		  soft_limit_pct = EXCLUDED.soft_limit_pct, effective_from = EXCLUDED.effective_from, effective_to = NULL,
		  updated_by = EXCLUDED.updated_by, updated_at = now()`,
		NewID(), x.TenantID, x.Key, x.Limit, x.SoftLimitPct, x.EffectiveFrom, actor)
	return mapErr(err)
}

// CloseQuotas đóng mọi hạn mức đang mở của tenant (bù trừ; svc_registry không có quyền DELETE trên bảng này).
func CloseQuotas(ctx context.Context, q Querier, tenantID uuid.UUID, on time.Time, actor uuid.UUID) error {
	_, err := q.Exec(ctx, `UPDATE registry.tenant_quota SET effective_to = greatest($2::date, effective_from), updated_by = $3,
		updated_at = now() WHERE tenant_id = $1 AND effective_to IS NULL`, tenantID, on, actor)
	return mapErr(err)
}

// ListQuotas trả hạn mức của tenant theo khoá.
func ListQuotas(ctx context.Context, q Querier, tenantID uuid.UUID) ([]Quota, error) {
	rows, err := q.Query(ctx, `SELECT tenant_id, quota_key, limit_value, soft_limit_pct, used_value, effective_from, effective_to
		FROM registry.tenant_quota WHERE tenant_id = $1 ORDER BY quota_key`, tenantID)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Quota, error) {
		var x Quota
		err := r.Scan(&x.TenantID, &x.Key, &x.Limit, &x.SoftLimitPct, &x.Used, &x.EffectiveFrom, &x.EffectiveTo)
		return x, err
	})
}

// GetJobForUpdate đọc tác vụ và khoá dòng trong giao dịch.
func GetJobForUpdate(ctx context.Context, tx pgx.Tx, id uuid.UUID) (*Job, error) {
	var j Job
	var params, prob []byte
	err := tx.QueryRow(ctx, `SELECT id, job_type, status, progress, message, params, result_name, result_content_type, result_location,
		problem, created_by, updated_at FROM registry.job WHERE id = $1 FOR UPDATE`, id).
		Scan(&j.ID, &j.Type, &j.Status, &j.Progress, &j.Message, &params, &j.ResultName, &j.ResultType, &j.ResultLocation, &prob, &j.CreatedBy, &j.UpdatedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	j.Params, j.Problem = params, prob
	return &j, nil
}

// UpdateJobState ghi trạng thái, tiến độ, thông điệp, tham số (trạng thái saga) và problem của tác vụ.
func UpdateJobState(ctx context.Context, q Querier, j *Job) error {
	_, err := q.Exec(ctx, `UPDATE registry.job SET status = $2, progress = $3, message = $4, params = $5, problem = $6,
		updated_at = now() WHERE id = $1`, j.ID, j.Status, j.Progress, j.Message, []byte(j.Params), nullJSON(j.Problem))
	return mapErr(err)
}

// LatestJobForTenant trả tác vụ mới nhất loại jobType có params.tenantId = tenantID.
func LatestJobForTenant(ctx context.Context, q Querier, jobType string, tenantID uuid.UUID) (*Job, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `SELECT id FROM registry.job WHERE job_type = $1 AND params->>'tenantId' = $2
		ORDER BY created_at DESC, id DESC LIMIT 1`, jobType, tenantID.String()).Scan(&id)
	if err != nil {
		return nil, mapErr(err)
	}
	return GetJob(ctx, q, id)
}

// PendingJobs trả id các tác vụ loại jobType còn CHO hoặc DANG_CHAY (tiếp tục sau khi khởi động lại).
func PendingJobs(ctx context.Context, q Querier, jobType string) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT id FROM registry.job WHERE job_type = $1 AND status IN ('CHO', 'DANG_CHAY') ORDER BY created_at`, jobType)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}

// TenantIDsByStatus trả id tenant ở một trạng thái (registry dưới 100.000 dòng).
func TenantIDsByStatus(ctx context.Context, q Querier, status string) ([]uuid.UUID, error) {
	rows, err := q.Query(ctx, `SELECT id FROM registry.tenant WHERE status = $1 ORDER BY id`, status)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, pgx.RowTo[uuid.UUID])
}
