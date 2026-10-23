// Package store là tầng truy cập dữ liệu của schema registry (TL-03): mọi câu lệnh có tham số, khoá lạc quan
// bằng row_version (0 dòng cập nhật → ErrVersionConflict, ánh xạ 409 ở tầng API).
package store

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// Querier là phần chung của *pgxpool.Pool và pgx.Tx.
type Querier interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// Lỗi chuẩn của tầng dữ liệu.
var (
	ErrNotFound = errors.New("store: không tìm thấy")
	// ErrOverlap: vi phạm ex_tenant_cell__no_overlap (một tenant chỉ thuộc một cell tại một thời điểm).
	ErrOverlap = errors.New("store: khoảng gắn cell chồng lấn")
)

// VersionConflictError: row_version lệch.
type VersionConflictError struct{ Sent, Current int }

func (e *VersionConflictError) Error() string {
	return fmt.Sprintf("store: lệch phiên bản (gửi %d, hiện %d)", e.Sent, e.Current)
}

// DuplicateError: vi phạm ràng buộc duy nhất; Constraint là tên ràng buộc TL-03 (uq_...).
type DuplicateError struct{ Constraint string }

func (e *DuplicateError) Error() string { return "store: trùng " + e.Constraint }

// mapErr chuyển lỗi PostgreSQL sang lỗi chuẩn.
func mapErr(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	var pg *pgconn.PgError
	if errors.As(err, &pg) {
		switch pg.Code {
		case "23505":
			return &DuplicateError{Constraint: pg.ConstraintName}
		case "23P01":
			if pg.ConstraintName == "ex_tenant_cell__no_overlap" {
				return ErrOverlap
			}
		}
	}
	return err
}

// SystemActor là mã tác nhân hệ thống cố định (job nền, seed).
var SystemActor = uuid.MustParse("00000000-0000-7000-8000-000000000000")

// NewID sinh UUIDv7 (TL-03: sinh ở ứng dụng).
func NewID() uuid.UUID { return uuid.Must(uuid.NewV7()) }

// Infinity là giá trị valid_to mặc định ('infinity').
var Infinity = time.Date(9999, 12, 31, 23, 59, 59, 0, time.UTC)

// ---------------------------------------------------------------- tenant

// Tenant ánh xạ registry.tenant.
type Tenant struct {
	ID               uuid.UUID
	Code             string
	Name             string
	Sector           string
	TenancyModel     string
	Status           string
	ContractRef      *string
	ContractValidTo  *time.Time
	MaxAccounts      int
	CreatedAt        time.Time
	CreatedBy        uuid.UUID
	UpdatedAt        time.Time
	UpdatedBy        uuid.UUID
	RowVersion       int
	OnboardingCode   *string
	Flows            []string
	ExpectedAccounts *int
	AccountCount     int
	KekRef           string
}

const tenantCols = `id, code, name, sector, tenancy_model, status, contract_ref, contract_valid_to, max_accounts,
	created_at, created_by, updated_at, updated_by, row_version, onboarding_code, flows, expected_accounts,
	account_count, kek_ref`

func scanTenant(row pgx.Row) (*Tenant, error) {
	var t Tenant
	err := row.Scan(&t.ID, &t.Code, &t.Name, &t.Sector, &t.TenancyModel, &t.Status, &t.ContractRef, &t.ContractValidTo,
		&t.MaxAccounts, &t.CreatedAt, &t.CreatedBy, &t.UpdatedAt, &t.UpdatedBy, &t.RowVersion, &t.OnboardingCode,
		&t.Flows, &t.ExpectedAccounts, &t.AccountCount, &t.KekRef)
	if err != nil {
		return nil, mapErr(err)
	}
	return &t, nil
}

// InsertTenant thêm tenant (row_version = 0).
func InsertTenant(ctx context.Context, q Querier, t *Tenant) error {
	err := q.QueryRow(ctx, `INSERT INTO registry.tenant (id, code, name, sector, tenancy_model, status, contract_ref,
		contract_valid_to, max_accounts, created_by, updated_by, onboarding_code, flows, expected_accounts, account_count, kek_ref)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$10,$11,$12,$13,$14,$15)
		RETURNING created_at, updated_at, row_version`,
		t.ID, t.Code, t.Name, t.Sector, t.TenancyModel, t.Status, t.ContractRef, t.ContractValidTo, t.MaxAccounts,
		t.CreatedBy, t.OnboardingCode, t.Flows, t.ExpectedAccounts, t.AccountCount, t.KekRef).
		Scan(&t.CreatedAt, &t.UpdatedAt, &t.RowVersion)
	t.UpdatedBy = t.CreatedBy
	return mapErr(err)
}

// GetTenant đọc tenant theo id.
func GetTenant(ctx context.Context, q Querier, id uuid.UUID) (*Tenant, error) {
	return scanTenant(q.QueryRow(ctx, `SELECT `+tenantCols+` FROM registry.tenant WHERE id = $1`, id))
}

// GetTenantForUpdate đọc và khoá dòng tenant trong giao dịch.
func GetTenantForUpdate(ctx context.Context, q Querier, id uuid.UUID) (*Tenant, error) {
	return scanTenant(q.QueryRow(ctx, `SELECT `+tenantCols+` FROM registry.tenant WHERE id = $1 FOR UPDATE`, id))
}

// GetTenantByCode đọc tenant theo mã.
func GetTenantByCode(ctx context.Context, q Querier, code string) (*Tenant, error) {
	return scanTenant(q.QueryRow(ctx, `SELECT `+tenantCols+` FROM registry.tenant WHERE code = $1`, code))
}

// UpdateTenant ghi các cột sửa được nếu row_version = expected; trả phiên bản mới qua t.RowVersion.
func UpdateTenant(ctx context.Context, q Querier, t *Tenant, expected int) error {
	err := q.QueryRow(ctx, `UPDATE registry.tenant SET name = $3, sector = $4, tenancy_model = $5, status = $6,
		contract_ref = $7, contract_valid_to = $8, max_accounts = $9, flows = $10, expected_accounts = $11,
		kek_ref = $12, updated_by = $13, updated_at = now(), row_version = row_version + 1
		WHERE id = $1 AND row_version = $2
		RETURNING updated_at, row_version`,
		t.ID, expected, t.Name, t.Sector, t.TenancyModel, t.Status, t.ContractRef, t.ContractValidTo, t.MaxAccounts,
		t.Flows, t.ExpectedAccounts, t.KekRef, t.UpdatedBy).Scan(&t.UpdatedAt, &t.RowVersion)
	if errors.Is(err, pgx.ErrNoRows) {
		var cur int
		if e := q.QueryRow(ctx, `SELECT row_version FROM registry.tenant WHERE id = $1`, t.ID).Scan(&cur); e != nil {
			return mapErr(e)
		}
		return &VersionConflictError{Sent: expected, Current: cur}
	}
	return mapErr(err)
}

// SetAccountCount cập nhật số tài khoản đồng bộ từ cell. Không tăng row_version để không làm lệch ETag của
// người đang soạn cấu hình (suy luận: account_count là số đo, không phải dữ liệu người dùng sửa).
func SetAccountCount(ctx context.Context, q Querier, id uuid.UUID, n int) (bool, error) {
	tag, err := q.Exec(ctx, `UPDATE registry.tenant SET account_count = $2 WHERE id = $1 AND account_count IS DISTINCT FROM $2`, id, n)
	return tag.RowsAffected() > 0, mapErr(err)
}

// ---------------------------------------------------------------- tenant_status_log

// StatusLog ánh xạ registry.tenant_status_log.
type StatusLog struct {
	ID         uuid.UUID
	TenantID   uuid.UUID
	FromStatus *string
	ToStatus   string
	Reason     string
	ActorID    uuid.UUID
	ChangedAt  time.Time
}

// InsertStatusLog ghi thêm một dòng nhật ký trạng thái.
func InsertStatusLog(ctx context.Context, q Querier, l *StatusLog) error {
	if l.ID == uuid.Nil {
		l.ID = NewID()
	}
	err := q.QueryRow(ctx, `INSERT INTO registry.tenant_status_log (id, tenant_id, from_status, to_status, reason, actor_id, changed_at)
		VALUES ($1,$2,$3,$4,$5,$6, now()) RETURNING changed_at`,
		l.ID, l.TenantID, l.FromStatus, l.ToStatus, l.Reason, l.ActorID).Scan(&l.ChangedAt)
	return mapErr(err)
}

// ListStatusLog trả nhật ký trạng thái mới nhất trước.
func ListStatusLog(ctx context.Context, q Querier, tenantID uuid.UUID, limit int) ([]StatusLog, error) {
	rows, err := q.Query(ctx, `SELECT id, tenant_id, from_status, to_status, reason, actor_id, changed_at
		FROM registry.tenant_status_log WHERE tenant_id = $1 ORDER BY changed_at DESC, id DESC LIMIT $2`, tenantID, limit)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (StatusLog, error) {
		var l StatusLog
		err := r.Scan(&l.ID, &l.TenantID, &l.FromStatus, &l.ToStatus, &l.Reason, &l.ActorID, &l.ChangedAt)
		return l, err
	})
}

// ---------------------------------------------------------------- cell

// Cell ánh xạ registry.cell, kèm tải hiện tại khi đọc qua ListCells.
type Cell struct {
	ID        uuid.UUID
	Code      string
	Name      string
	PrimaryDC string
	StandbyDC *string
	// ActiveDC là TTDL đang phục vụ cell (cột active_dc, NULL thì bằng PrimaryDC; INF-20, DR-10).
	ActiveDC         string
	TenancyModel     string
	APIBaseURL       string
	CapacityAccounts int
	Status           string
	RowVersion       int
	TenantCount      int
	AccountCount     int
	// Reserved là tổng max(account_count, expected_accounts) của tenant chưa chấm dứt đang gắn cell, dùng cho
	// BR-01-02 (suy luận: tenant đang khởi tạo giữ chỗ theo quy mô dự kiến).
	Reserved int
}

const cellCols = `c.id, c.code, c.name, c.primary_dc, c.standby_dc, coalesce(c.active_dc, c.primary_dc), c.tenancy_model, c.api_base_url, c.capacity_accounts, c.status, c.row_version`

func scanCell(row pgx.Row) (*Cell, error) {
	var c Cell
	err := row.Scan(&c.ID, &c.Code, &c.Name, &c.PrimaryDC, &c.StandbyDC, &c.ActiveDC, &c.TenancyModel, &c.APIBaseURL,
		&c.CapacityAccounts, &c.Status, &c.RowVersion)
	if err != nil {
		return nil, mapErr(err)
	}
	return &c, nil
}

// InsertCell thêm cell (dùng cho seed và kiểm thử; API quản lý cell chưa có trong hợp đồng).
func InsertCell(ctx context.Context, q Querier, c *Cell) error {
	_, err := q.Exec(ctx, `INSERT INTO registry.cell (id, code, name, primary_dc, standby_dc, tenancy_model, api_base_url,
		capacity_accounts, status, created_by, updated_by) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$10)`,
		c.ID, c.Code, c.Name, c.PrimaryDC, c.StandbyDC, c.TenancyModel, c.APIBaseURL, c.CapacityAccounts, c.Status, SystemActor)
	return mapErr(err)
}

// GetCell đọc cell theo id.
func GetCell(ctx context.Context, q Querier, id uuid.UUID) (*Cell, error) {
	return scanCell(q.QueryRow(ctx, `SELECT `+cellCols+` FROM registry.cell c WHERE c.id = $1`, id))
}

// GetCellByCode đọc cell theo mã.
func GetCellByCode(ctx context.Context, q Querier, code string) (*Cell, error) {
	return scanCell(q.QueryRow(ctx, `SELECT `+cellCols+` FROM registry.cell c WHERE c.code = $1`, code))
}

// CellFilter là bộ lọc listCells.
type CellFilter struct {
	Status       string
	TenancyModel string
	AfterCode    string
	Limit        int
}

// ListCells trả cell theo mã tăng dần kèm số tenant, tổng tài khoản và mức giữ chỗ của gán cell đang hiệu lực.
func ListCells(ctx context.Context, q Querier, f CellFilter) ([]Cell, error) {
	if f.Limit <= 0 {
		f.Limit = 1000
	}
	rows, err := q.Query(ctx, `SELECT `+cellCols+`,
		  count(t.id)::int,
		  coalesce(sum(t.account_count), 0)::int,
		  coalesce(sum(greatest(t.account_count, coalesce(t.expected_accounts, 0))), 0)::int
		FROM registry.cell c
		LEFT JOIN registry.tenant_cell tc ON tc.cell_id = c.id AND tc.valid_from <= now() AND tc.valid_to > now()
		LEFT JOIN registry.tenant t ON t.id = tc.tenant_id AND t.status <> 'DA_CHAM_DUT'
		WHERE ($1 = '' OR c.status = $1) AND ($2 = '' OR c.tenancy_model = $2) AND ($3 = '' OR c.code > $3)
		GROUP BY c.id
		ORDER BY c.code
		LIMIT $4`, f.Status, f.TenancyModel, f.AfterCode, f.Limit)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Cell, error) {
		var c Cell
		err := r.Scan(&c.ID, &c.Code, &c.Name, &c.PrimaryDC, &c.StandbyDC, &c.ActiveDC, &c.TenancyModel, &c.APIBaseURL,
			&c.CapacityAccounts, &c.Status, &c.RowVersion, &c.TenantCount, &c.AccountCount, &c.Reserved)
		return c, err
	})
}

// ---------------------------------------------------------------- tenant_cell

// TenantCell ánh xạ registry.tenant_cell (kèm mã và URL cell khi đọc).
type TenantCell struct {
	ID           uuid.UUID
	TenantID     uuid.UUID
	CellID       uuid.UUID
	DatabaseName string
	ValidFrom    time.Time
	ValidTo      time.Time // Infinity khi còn hiệu lực
	TenancyModel string
	DataRegion   string
	RowVersion   int
	CellCode     string
	CellURL      string
}

// InsertTenantCell thêm một lần gắn cell; chồng lấn trả ErrOverlap.
func InsertTenantCell(ctx context.Context, q Querier, tc *TenantCell, actor uuid.UUID) error {
	if tc.ID == uuid.Nil {
		tc.ID = NewID()
	}
	var validTo any = tc.ValidTo
	if tc.ValidTo.IsZero() || !tc.ValidTo.Before(Infinity) {
		validTo = "infinity"
	}
	_, err := q.Exec(ctx, `INSERT INTO registry.tenant_cell (id, tenant_id, cell_id, database_name, valid_from, valid_to,
		created_by, updated_by, tenancy_model, data_region) VALUES ($1,$2,$3,$4,$5,$6::timestamptz,$7,$7,$8,$9)`,
		tc.ID, tc.TenantID, tc.CellID, tc.DatabaseName, tc.ValidFrom, validTo, actor, tc.TenancyModel, tc.DataRegion)
	return mapErr(err)
}

// UpdateDraftTenantCell sửa lần gắn cell còn nháp (tenant KHOI_TAO) theo row_version.
func UpdateDraftTenantCell(ctx context.Context, q Querier, tc *TenantCell, actor uuid.UUID) error {
	tag, err := q.Exec(ctx, `UPDATE registry.tenant_cell SET cell_id = $3, database_name = $4, tenancy_model = $5,
		data_region = $6, updated_by = $7, updated_at = now(), row_version = row_version + 1
		WHERE id = $1 AND row_version = $2`,
		tc.ID, tc.RowVersion, tc.CellID, tc.DatabaseName, tc.TenancyModel, tc.DataRegion, actor)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return &VersionConflictError{Sent: tc.RowVersion, Current: -1}
	}
	tc.RowVersion++
	return nil
}

// CloseTenantCell kết thúc lần gắn cell tại thời điểm at.
func CloseTenantCell(ctx context.Context, q Querier, id uuid.UUID, at time.Time, actor uuid.UUID) error {
	_, err := q.Exec(ctx, `UPDATE registry.tenant_cell SET valid_to = $2, updated_by = $3, updated_at = now(),
		row_version = row_version + 1 WHERE id = $1`, id, at, actor)
	return mapErr(err)
}

const tenantCellSelect = `SELECT tc.id, tc.tenant_id, tc.cell_id, tc.database_name, tc.valid_from,
	CASE WHEN tc.valid_to = 'infinity' THEN '9999-12-31 23:59:59+00'::timestamptz ELSE tc.valid_to END,
	tc.tenancy_model, tc.data_region, tc.row_version, c.code, c.api_base_url
	FROM registry.tenant_cell tc JOIN registry.cell c ON c.id = tc.cell_id`

func scanTenantCell(row pgx.Row) (*TenantCell, error) {
	var tc TenantCell
	err := row.Scan(&tc.ID, &tc.TenantID, &tc.CellID, &tc.DatabaseName, &tc.ValidFrom, &tc.ValidTo, &tc.TenancyModel,
		&tc.DataRegion, &tc.RowVersion, &tc.CellCode, &tc.CellURL)
	if err != nil {
		return nil, mapErr(err)
	}
	return &tc, nil
}

// CurrentTenantCell trả lần gắn cell hiệu lực tại thời điểm at.
func CurrentTenantCell(ctx context.Context, q Querier, tenantID uuid.UUID, at time.Time) (*TenantCell, error) {
	return scanTenantCell(q.QueryRow(ctx, tenantCellSelect+`
		WHERE tc.tenant_id = $1 AND tc.valid_from <= $2 AND tc.valid_to > $2`, tenantID, at))
}

// LatestTenantCell trả lần gắn cell có valid_to lớn nhất (gồm cả gắn tương lai, dùng cho bản nháp).
func LatestTenantCell(ctx context.Context, q Querier, tenantID uuid.UUID) (*TenantCell, error) {
	return scanTenantCell(q.QueryRow(ctx, tenantCellSelect+`
		WHERE tc.tenant_id = $1 ORDER BY tc.valid_to DESC, tc.valid_from DESC LIMIT 1`, tenantID))
}

// ---------------------------------------------------------------- tenant_domain

// Domain ánh xạ registry.tenant_domain.
type Domain struct {
	ID         uuid.UUID
	TenantID   uuid.UUID
	Domain     string
	Kind       string
	TLSCertRef *string
	VerifiedAt *time.Time
	RowVersion int
}

// InsertDomain thêm tên miền; trùng trả DuplicateError{uq_tenant_domain__domain}.
func InsertDomain(ctx context.Context, q Querier, d *Domain, actor uuid.UUID) error {
	if d.ID == uuid.Nil {
		d.ID = NewID()
	}
	_, err := q.Exec(ctx, `INSERT INTO registry.tenant_domain (id, tenant_id, domain, kind, tls_cert_ref, verified_at, created_by, updated_by)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$7)`, d.ID, d.TenantID, d.Domain, d.Kind, d.TLSCertRef, d.VerifiedAt, actor)
	return mapErr(err)
}

// DeleteDomains xoá tên miền của tenant theo loại (kind rỗng: mọi loại).
func DeleteDomains(ctx context.Context, q Querier, tenantID uuid.UUID, kind string) error {
	_, err := q.Exec(ctx, `DELETE FROM registry.tenant_domain WHERE tenant_id = $1 AND ($2 = '' OR kind = $2)`, tenantID, kind)
	return mapErr(err)
}

// ListDomains trả tên miền của tenant (PRIMARY trước).
func ListDomains(ctx context.Context, q Querier, tenantID uuid.UUID) ([]Domain, error) {
	rows, err := q.Query(ctx, `SELECT id, tenant_id, domain, kind, tls_cert_ref, verified_at, row_version
		FROM registry.tenant_domain WHERE tenant_id = $1 ORDER BY (kind = 'PRIMARY') DESC, domain`, tenantID)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Domain, error) {
		var d Domain
		err := r.Scan(&d.ID, &d.TenantID, &d.Domain, &d.Kind, &d.TLSCertRef, &d.VerifiedAt, &d.RowVersion)
		return d, err
	})
}

// TenantIDByDomain tra tenant theo tên miền (chỉ mục uq_tenant_domain__domain).
func TenantIDByDomain(ctx context.Context, q Querier, domain string) (uuid.UUID, error) {
	var id uuid.UUID
	err := q.QueryRow(ctx, `SELECT tenant_id FROM registry.tenant_domain WHERE domain = $1`, domain).Scan(&id)
	return id, mapErr(err)
}

// ---------------------------------------------------------------- tenant_idp

// Idp ánh xạ registry.tenant_idp.
type Idp struct {
	ID           uuid.UUID
	TenantID     uuid.UUID
	IdpAlias     string
	Protocol     string
	Issuer       string
	IsVneid      bool
	EmailDomains []string
	Status       string
	RowVersion   int
}

// InsertIdp thêm IdP; trùng bí danh trong tenant trả DuplicateError{uq_tenant_idp__tenant_id_idp_alias}.
func InsertIdp(ctx context.Context, q Querier, i *Idp, actor uuid.UUID) error {
	if i.ID == uuid.Nil {
		i.ID = NewID()
	}
	if i.EmailDomains == nil {
		i.EmailDomains = []string{}
	}
	if i.Status == "" {
		i.Status = "HOAT_DONG"
	}
	_, err := q.Exec(ctx, `INSERT INTO registry.tenant_idp (id, tenant_id, idp_alias, protocol, issuer, is_vneid, email_domains,
		status, created_by, updated_by) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$9)`,
		i.ID, i.TenantID, i.IdpAlias, i.Protocol, i.Issuer, i.IsVneid, i.EmailDomains, i.Status, actor)
	return mapErr(err)
}

// UpdateIdp sửa IdP theo row_version.
func UpdateIdp(ctx context.Context, q Querier, i *Idp, actor uuid.UUID) error {
	tag, err := q.Exec(ctx, `UPDATE registry.tenant_idp SET protocol = $3, issuer = $4, is_vneid = $5, email_domains = $6,
		status = $7, updated_by = $8, updated_at = now(), row_version = row_version + 1 WHERE id = $1 AND row_version = $2`,
		i.ID, i.RowVersion, i.Protocol, i.Issuer, i.IsVneid, i.EmailDomains, i.Status, actor)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return &VersionConflictError{Sent: i.RowVersion, Current: -1}
	}
	i.RowVersion++
	return nil
}

// DeleteIdps xoá IdP của tenant trừ các bí danh giữ lại.
func DeleteIdps(ctx context.Context, q Querier, tenantID uuid.UUID, keep []string) error {
	if keep == nil {
		keep = []string{}
	}
	_, err := q.Exec(ctx, `DELETE FROM registry.tenant_idp WHERE tenant_id = $1 AND NOT (idp_alias = ANY($2))`, tenantID, keep)
	return mapErr(err)
}

// ListIdps trả IdP của tenant; onlyActive lọc HOAT_DONG.
func ListIdps(ctx context.Context, q Querier, tenantID uuid.UUID, onlyActive bool) ([]Idp, error) {
	rows, err := q.Query(ctx, `SELECT id, tenant_id, idp_alias, protocol, issuer, is_vneid, email_domains, status, row_version
		FROM registry.tenant_idp WHERE tenant_id = $1 AND (NOT $2 OR status = 'HOAT_DONG') ORDER BY idp_alias`, tenantID, onlyActive)
	if err != nil {
		return nil, mapErr(err)
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Idp, error) {
		var i Idp
		err := r.Scan(&i.ID, &i.TenantID, &i.IdpAlias, &i.Protocol, &i.Issuer, &i.IsVneid, &i.EmailDomains, &i.Status, &i.RowVersion)
		return i, err
	})
}

// SetTenantCellDatabase ghi CSDL dữ liệu và thời điểm bắt đầu hiệu lực của lần gắn cell khi bước CSDL của saga
// cấp phát xong (DV-03-04).
func SetTenantCellDatabase(ctx context.Context, q Querier, id uuid.UUID, db string, validFrom time.Time, actor uuid.UUID) error {
	_, err := q.Exec(ctx, `UPDATE registry.tenant_cell SET database_name = $2, valid_from = $3, updated_by = $4, updated_at = now(),
		row_version = row_version + 1 WHERE id = $1`, id, db, validFrom, actor)
	return mapErr(err)
}

// SetTenantKekRef ghi tên KEK đã tạo trong OpenBao Transit (DV-03-04).
func SetTenantKekRef(ctx context.Context, q Querier, id uuid.UUID, ref string, actor uuid.UUID) error {
	_, err := q.Exec(ctx, `UPDATE registry.tenant SET kek_ref = $2, updated_by = $3, updated_at = now(),
		row_version = row_version + 1 WHERE id = $1`, id, ref, actor)
	return mapErr(err)
}
