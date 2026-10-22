package provision

// Saga cấp phát phần 1 (DV-03-04; QT-01 bước 4; TL-01 "Tiếp nhận tenant"; TL-04 OPS-07): bước CSDL (bridge,
// silo: CSDL logic td_<mã> trên cụm của cell, vai trò và Flyway mọi schema; pool: kiểm CSDL dùng chung td_pool)
// và bước khoá (KEK trong OpenBao Transit, khoá HMAC chỉ mục mù). Hai bước chạy trước phần 2 qua Saga.PreSteps.
// Trạng thái bước chỉ chứa tên và cờ (không có chuỗi kết nối, mật khẩu hay vật liệu khoá).

import (
	"context"
	"errors"
	"regexp"
	"strings"

	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
)

// Mã bước của saga cấp phát phần 1.
const (
	StepDatabase = "CSDL"
	StepKeys     = "KHOA_MA_HOA"
)

// CellDatabases là cổng tạo CSDL dữ liệu tenant trên cụm PostgreSQL của cell, qua tài khoản quản trị riêng có
// quyền hẹp (CREATEDB, không SUPERUSER), không dùng tài khoản ứng dụng.
type CellDatabases interface {
	// EnsureDatabase tạo CSDL nếu chưa có; created = true khi chính lần gọi này tạo.
	EnsureDatabase(ctx context.Context, cell, db, tenancy string) (created bool, err error)
	// DropIfEmpty xoá CSDL khi không bảng nào có dòng; còn dữ liệu thì trả ErrDatabaseNotEmpty.
	DropIfEmpty(ctx context.Context, cell, db string) error
	// SharedReady kiểm CSDL dùng chung của pool đã có và mang app.tenancy_model = 'pool'.
	SharedReady(ctx context.Context, cell, db string) error
}

// ErrDatabaseNotEmpty: CSDL đã có dữ liệu nên bù trừ không xoá (cần xử lý theo hướng dẫn vận hành).
var ErrDatabaseNotEmpty = errors.New("provision: CSDL đã có dữ liệu, không xoá")

// Migrator tạo vai trò mig_/svc_/ro_/cdc_ (DatabaseBootstrap, NEN-031) và chạy Flyway mọi schema
// (MigrationRunner, NEN-030) cho một CSDL dữ liệu tenant.
type Migrator interface {
	Migrate(ctx context.Context, cell, db, tenancy string) error
}

// Keys là cổng khoá trong OpenBao Transit (HSM là seal của OpenBao).
type Keys interface {
	// EnsureKey tạo khoá tên name loại typ (aes256-gcm96 cho KEK, hmac cho chỉ mục mù) nếu chưa có.
	EnsureKey(ctx context.Context, name, typ string) (created bool, err error)
	// DeleteUnusedKey huỷ khoá chỉ khi chưa xoay vòng (latest_version = 1); khoá đã dùng trả ErrKeyInUse.
	DeleteUnusedKey(ctx context.Context, name string) error
}

// ErrKeyInUse: khoá đã xoay vòng nên không còn là khoá mới tạo, bù trừ không huỷ.
var ErrKeyInUse = errors.New("provision: khoá đã được dùng, không huỷ")

// Loại khoá Transit.
const (
	KeyTypeKEK  = "aes256-gcm96"
	KeyTypeHMAC = "hmac"
)

// DataSteps trả hai bước phần 1 để gán vào Saga.PreSteps.
func DataSteps() []Step { return []Step{databaseStep{}, keyStep{}} }

var dbNameRe = regexp.MustCompile(`^td_[a-z0-9_]{1,60}$`)

// ValidDatabaseName: chỉ nhận tên td_<mã> chữ thường (tên đi vào câu lệnh DDL).
func ValidDatabaseName(db string) bool { return dbNameRe.MatchString(db) }

// ---------------------------------------------------------------- CSDL

type databaseStep struct{}

func (databaseStep) Name() string  { return StepDatabase }
func (databaseStep) Label() string { return "khởi tạo cơ sở dữ liệu" }

// Do: bridge, silo tạo CSDL td_<mã> rồi tạo vai trò và chạy migration mọi schema; pool chỉ kiểm CSDL dùng chung.
// Cả hai ghi registry.tenant_cell (database_name, valid_from).
func (databaseStep) Do(ctx context.Context, r *Run, out map[string]string) error {
	s := r.s
	if s.Databases == nil || s.Migrator == nil {
		return Permanent("Chưa cấu hình kết nối cơ sở dữ liệu của cell", nil)
	}
	model := r.Binding.TenancyModel
	db := tenant.DatabaseName(model, r.Tenant.Code)
	if !ValidDatabaseName(db) {
		return Permanent("Mã tenant không hợp lệ để đặt tên cơ sở dữ liệu", nil)
	}
	out["database"] = db
	if model == tenant.Pool {
		if err := s.Databases.SharedReady(ctx, r.Cell.Code, db); err != nil {
			return Permanent("Cơ sở dữ liệu dùng chung của cell chưa sẵn sàng", err)
		}
	} else {
		created, err := s.Databases.EnsureDatabase(ctx, r.Cell.Code, db, model)
		if created {
			out["databaseCreated"] = "true"
		}
		if err != nil {
			return Transient("Không tạo được cơ sở dữ liệu của tenant", err)
		}
		if err := s.Migrator.Migrate(ctx, r.Cell.Code, db, model); err != nil {
			return Transient("Không khởi tạo được lược đồ dữ liệu của tenant", err)
		}
	}
	now := s.now().UTC()
	if err := store.SetTenantCellDatabase(ctx, s.Pool, r.Binding.ID, db, now, r.Actor.ID); err != nil {
		return err
	}
	r.Binding.DatabaseName, r.Binding.ValidFrom = db, now
	return nil
}

// Undo xoá CSDL do saga tạo khi còn rỗng; pool không có gì để gỡ ngoài lần gắn cell (giữ bản nháp để cấp phát lại).
func (databaseStep) Undo(ctx context.Context, r *Run, out map[string]string) error {
	if out["databaseCreated"] == "true" && out["database"] != "" {
		if err := r.s.Databases.DropIfEmpty(ctx, r.Cell.Code, out["database"]); err != nil {
			return err
		}
		delete(out, "databaseCreated")
	}
	return nil
}

// ---------------------------------------------------------------- khoá

type keyStep struct{}

func (keyStep) Name() string  { return StepKeys }
func (keyStep) Label() string { return "tạo khoá mã hoá" }

// KeyName trả tên khoá Transit từ registry.tenant.kek_ref (bỏ tiền tố transit/keys/).
func KeyName(kekRef string) string {
	_, name, found := strings.Cut(kekRef, "/keys/")
	if !found {
		return kekRef
	}
	return name
}

// BlindIndexKey trả tên khoá HMAC chỉ mục mù của tenant (luôn riêng theo tenant, suy luận, wiki trang 14).
func BlindIndexKey(tenantCode string) string { return "t-" + tenantCode + "-bidx" }

// Do tạo KEK (riêng tenant, hoặc dùng chung của cell với pool) và khoá HMAC chỉ mục mù riêng của tenant, rồi
// ghi registry.tenant.kek_ref. Silo bắt buộc KEK riêng (TL-02a).
func (keyStep) Do(ctx context.Context, r *Run, out map[string]string) error {
	s := r.s
	if s.Keys == nil {
		return Permanent("Chưa cấu hình kho khoá", nil)
	}
	mode := tenant.KekRieng
	if strings.Contains(r.Tenant.KekRef, "/keys/cell-") {
		mode = tenant.KekDungChung
	}
	if err := tenant.CheckKekMode(r.Binding.TenancyModel, mode); err != nil {
		return Permanent("Tenant silo bắt buộc dùng khoá riêng", err)
	}
	ref := tenant.KekRef(mode, r.Tenant.Code, r.Cell.Code)
	kek := KeyName(ref)
	created, err := s.Keys.EnsureKey(ctx, kek, KeyTypeKEK)
	if err != nil {
		return Transient("Không tạo được khoá mã hoá của tenant", err)
	}
	// Khoá dùng chung của cell không bao giờ bị bù trừ huỷ, kể cả khi lần cấp phát này tạo ra nó.
	if created && mode == tenant.KekRieng {
		out["keysCreated"] = addToList(out["keysCreated"], kek)
	}
	out["kek"] = kek
	bidx := BlindIndexKey(r.Tenant.Code)
	created, err = s.Keys.EnsureKey(ctx, bidx, KeyTypeHMAC)
	if err != nil {
		return Transient("Không tạo được khoá chỉ mục tìm kiếm của tenant", err)
	}
	if created {
		out["keysCreated"] = addToList(out["keysCreated"], bidx)
	}
	out["blindIndexKey"] = bidx
	if ref != r.Tenant.KekRef {
		if err := store.SetTenantKekRef(ctx, s.Pool, r.Tenant.ID, ref, r.Actor.ID); err != nil {
			return err
		}
		r.Tenant.KekRef = ref
	}
	return nil
}

// Undo huỷ các khoá do saga tạo mà chưa dùng, theo thứ tự ngược.
func (keyStep) Undo(ctx context.Context, r *Run, out map[string]string) error {
	ks := splitList(out["keysCreated"])
	for i := len(ks) - 1; i >= 0; i-- {
		if err := r.s.Keys.DeleteUnusedKey(ctx, ks[i]); err != nil {
			return err
		}
		out["keysCreated"] = strings.Join(ks[:i], ",")
	}
	delete(out, "keysCreated")
	return nil
}
