package store_test

import (
	"context"
	"encoding/json"
	"errors"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/testdb"
)

// Migration chạy sạch từ đầu trên một CSDL trống khác (độc lập với CSDL dùng chung của gói).
func TestMigrationsApplyCleanFromScratch(t *testing.T) {
	ctx := context.Background()
	fresh, err := startPG(ctx, false)
	if err != nil {
		t.Fatal(err)
	}
	defer fresh.Terminate(ctx)
	if err := fresh.Migrate(ctx); err != nil {
		t.Fatalf("migration lỗi: %v", err)
	}
	conn, err := pgx.Connect(ctx, fresh.AdminDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	want := []string{"tenant", "cell", "tenant_cell", "tenant_domain", "tenant_idp", "tenant_status_log", "tenant_quota", "outbox_event"}
	for _, tbl := range want {
		var ok bool
		if err := conn.QueryRow(ctx, `SELECT to_regclass('registry.'||$1) IS NOT NULL`, tbl).Scan(&ok); err != nil || !ok {
			t.Errorf("thiếu bảng registry.%s", tbl)
		}
	}
	var cells int
	_ = conn.QueryRow(ctx, `SELECT count(*) FROM registry.cell`).Scan(&cells)
	if cells != 8 {
		t.Errorf("seed dev: muốn 8 cell, có %d", cells)
	}
	var owner string
	_ = conn.QueryRow(ctx, `SELECT tableowner FROM pg_tables WHERE schemaname='registry' AND tablename='tenant'`).Scan(&owner)
	if owner != "mig_registry" {
		t.Errorf("chủ sở hữu bảng phải là mig_registry, có %q", owner)
	}
}

// Lint quy ước tên TL-03 "Quy ước đặt tên" và chú thích phân loại cột (thay tạm lint NEN-030).
func TestMigrationNamingAndClassificationLint(t *testing.T) {
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, db.AdminDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)

	prefix := map[string]string{"p": "pk_", "f": "fk_", "u": "uq_", "c": "ck_", "x": "ex_"}
	rows, err := conn.Query(ctx, `SELECT con.conname, con.contype::text, rel.relname
		FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid JOIN pg_namespace n ON n.oid = rel.relnamespace
		WHERE n.nspname = 'registry' AND con.contype IN ('p','f','u','c','x') AND rel.relname NOT LIKE '%_default'`)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for rows.Next() {
		var name, typ, rel string
		_ = rows.Scan(&name, &typ, &rel)
		n++
		if !strings.HasPrefix(name, prefix[typ]+rel) {
			t.Errorf("ràng buộc %s của bảng %s phải bắt đầu bằng %s%s", name, rel, prefix[typ], rel)
		}
		if typ == "f" && !regexp.MustCompile(`^fk_[a-z_]+__[a-z_]+$`).MatchString(name) {
			t.Errorf("khoá ngoại %s phải dạng fk_<bảng>__<bảng đích>", name)
		}
	}
	rows.Close()
	if n < 20 {
		t.Errorf("quá ít ràng buộc (%d), kiểm lại truy vấn lint", n)
	}

	idx, _ := conn.Query(ctx, `SELECT indexname FROM pg_indexes WHERE schemaname='registry'`)
	for idx.Next() {
		var name string
		_ = idx.Scan(&name)
		if !regexp.MustCompile(`^(pk|uq|ex|ix)_[a-z0-9_]+$`).MatchString(name) && !strings.Contains(name, "_default") {
			t.Errorf("chỉ mục %s sai quy ước", name)
		}
	}
	idx.Close()

	cols, err := conn.Query(ctx, `SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod), col_description(c.oid, a.attnum)
		FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
		WHERE n.nspname = 'registry' AND c.relkind IN ('r','p') AND a.attnum > 0 AND NOT a.attisdropped
		  AND c.relname NOT LIKE '%_default'`)
	if err != nil {
		t.Fatal(err)
	}
	defer cols.Close()
	for cols.Next() {
		var rel, col, typ string
		var comment *string
		_ = cols.Scan(&rel, &col, &typ, &comment)
		if col != strings.ToLower(col) {
			t.Errorf("%s.%s có chữ hoa", rel, col)
		}
		if strings.HasSuffix(col, "_at") && typ != "timestamp with time zone" {
			t.Errorf("%s.%s phải là timestamptz", rel, col)
		}
		if (strings.HasPrefix(col, "is_") || strings.HasPrefix(col, "has_")) && typ != "boolean" {
			t.Errorf("%s.%s phải là boolean", rel, col)
		}
		if comment == nil {
			t.Errorf("%s.%s thiếu chú thích phân loại", rel, col)
			continue
		}
		var c struct {
			Cls string `json:"cls"`
		}
		if err := json.Unmarshal([]byte(*comment), &c); err != nil || !regexp.MustCompile(`^C[0-3]$`).MatchString(c.Cls) {
			t.Errorf("%s.%s chú thích phân loại sai: %s", rel, col, *comment)
		}
		if c.Cls == "C2" && (!strings.HasSuffix(col, "_enc") || typ != "bytea") {
			t.Errorf("%s.%s là C2 nhưng không phải cột _enc bytea", rel, col)
		}
	}
}

func seedTenant(t *testing.T, ctx context.Context, q store.Querier, code string) *store.Tenant {
	t.Helper()
	ten := &store.Tenant{ID: store.NewID(), Code: code, Name: "Đơn vị " + code, Sector: "CHINH_QUYEN", TenancyModel: "BRIDGE",
		Status: "KHOI_TAO", MaxAccounts: 1000, CreatedBy: store.SystemActor, Flows: []string{"DANG"}, KekRef: "transit/keys/t-" + code}
	if err := store.InsertTenant(ctx, q, ten); err != nil {
		t.Fatal(err)
	}
	return ten
}

func TestTenantCellOverlapRejected(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Pool(ctx, t, db.SvcDSN)
	ten := seedTenant(t, ctx, pool, "overlap_a")
	g1, _ := store.GetCellByCode(ctx, pool, "CELL-G1")
	g2, _ := store.GetCellByCode(ctx, pool, "CELL-G2")
	t0 := time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)
	first := &store.TenantCell{TenantID: ten.ID, CellID: g1.ID, DatabaseName: "td_overlap_a", ValidFrom: t0,
		ValidTo: t0.AddDate(0, 1, 0), TenancyModel: "bridge", DataRegion: "HOALAC"}
	if err := store.InsertTenantCell(ctx, pool, first, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	// Chồng lấn 15 ngày với lần gắn trước: CSDL phải từ chối.
	second := &store.TenantCell{TenantID: ten.ID, CellID: g2.ID, DatabaseName: "td_overlap_a", ValidFrom: t0.AddDate(0, 0, 15),
		TenancyModel: "bridge", DataRegion: "HOALAC"}
	if err := store.InsertTenantCell(ctx, pool, second, store.SystemActor); !errors.Is(err, store.ErrOverlap) {
		t.Fatalf("muốn ErrOverlap, có %v", err)
	}
	// Nối tiếp đúng biên (valid_from = valid_to cũ) được chấp nhận.
	second.ID, second.ValidFrom = store.NewID(), first.ValidTo
	if err := store.InsertTenantCell(ctx, pool, second, store.SystemActor); err != nil {
		t.Fatalf("gắn nối tiếp phải hợp lệ: %v", err)
	}
	// Tenant khác cùng khoảng thời gian không bị ảnh hưởng.
	other := seedTenant(t, ctx, pool, "overlap_b")
	third := &store.TenantCell{TenantID: other.ID, CellID: g1.ID, DatabaseName: "td_overlap_b", ValidFrom: t0,
		TenancyModel: "bridge", DataRegion: "HOALAC"}
	if err := store.InsertTenantCell(ctx, pool, third, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	cur, err := store.CurrentTenantCell(ctx, pool, ten.ID, t0.AddDate(0, 2, 0))
	if err != nil || cur.CellCode != "CELL-G2" || !cur.ValidTo.Equal(store.Infinity) {
		t.Fatalf("gắn cell hiện hành sai: %+v %v", cur, err)
	}
}

func pgCode(err error) string {
	var pg *pgconn.PgError
	if errors.As(err, &pg) {
		return pg.Code
	}
	return ""
}

func TestSvcRoleHasNoDDLAndStatusLogAppendOnly(t *testing.T) {
	ctx := context.Background()
	svc, err := pgx.Connect(ctx, db.SvcDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Close(ctx)
	ddl := []string{
		`CREATE TABLE registry.hack (id int)`,
		`CREATE TABLE public.hack (id int)`,
		`ALTER TABLE registry.tenant ADD COLUMN hack int`,
		`DROP TABLE registry.tenant_quota`,
		`CREATE INDEX ix_hack ON registry.tenant (name)`,
		`TRUNCATE registry.tenant_status_log`,
		`CREATE SCHEMA hack`,
	}
	for _, s := range ddl {
		if _, err := svc.Exec(ctx, s); pgCode(err) != "42501" {
			t.Errorf("svc_registry phải bị từ chối %q, có %v", s, err)
		}
	}
	ten := seedTenant(t, ctx, svc, "logonly")
	l := &store.StatusLog{TenantID: ten.ID, ToStatus: "KHOI_TAO", Reason: "Khai báo", ActorID: store.SystemActor}
	if err := store.InsertStatusLog(ctx, svc, l); err != nil {
		t.Fatalf("svc_registry phải ghi thêm được nhật ký: %v", err)
	}
	for _, s := range []string{`DELETE FROM registry.tenant_status_log`, `UPDATE registry.tenant_status_log SET reason = 'x'`,
		`DELETE FROM registry.tenant`, `DELETE FROM registry.outbox_event`} {
		if _, err := svc.Exec(ctx, s); pgCode(err) != "42501" {
			t.Errorf("svc_registry phải bị từ chối %q, có %v", s, err)
		}
	}
	// Kể cả chủ sở hữu bảng cũng không sửa được nhật ký (trigger tg_tenant_status_log__no_update).
	mig, _ := pgx.Connect(ctx, db.MigDSN)
	defer mig.Close(ctx)
	if _, err := mig.Exec(ctx, `UPDATE registry.tenant_status_log SET reason = 'x'`); pgCode(err) != "42501" {
		t.Errorf("trigger chỉ ghi thêm không chặn UPDATE: %v", err)
	}
	ro, _ := pgx.Connect(ctx, db.RoDSN)
	defer ro.Close(ctx)
	if _, err := ro.Exec(ctx, `UPDATE registry.tenant SET name = 'x'`); pgCode(err) != "42501" {
		t.Errorf("ro_registry không được ghi: %v", err)
	}
	var n int
	if err := ro.QueryRow(ctx, `SELECT count(*) FROM registry.tenant`).Scan(&n); err != nil {
		t.Errorf("ro_registry phải đọc được: %v", err)
	}
	var bypass bool
	_ = ro.QueryRow(ctx, `SELECT bool_or(rolbypassrls) FROM pg_roles WHERE rolname IN ('svc_registry','ro_registry','mig_registry')`).Scan(&bypass)
	if bypass {
		t.Error("không vai trò nào của registry được BYPASSRLS")
	}
}

func TestOptimisticLockAndDuplicates(t *testing.T) {
	ctx := context.Background()
	pool := testdb.Pool(ctx, t, db.SvcDSN)
	ten := seedTenant(t, ctx, pool, "lock_a")
	ten.Name = "Tên mới"
	if err := store.UpdateTenant(ctx, pool, ten, 0); err != nil || ten.RowVersion != 1 {
		t.Fatalf("cập nhật lần đầu: %v rv=%d", err, ten.RowVersion)
	}
	var vc *store.VersionConflictError
	if err := store.UpdateTenant(ctx, pool, ten, 0); !errors.As(err, &vc) || vc.Current != 1 {
		t.Fatalf("muốn lệch phiên bản (hiện 1), có %v", err)
	}
	missing := *ten
	missing.ID = store.NewID()
	if err := store.UpdateTenant(ctx, pool, &missing, 0); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("muốn ErrNotFound, có %v", err)
	}
	var dup *store.DuplicateError
	again := &store.Tenant{ID: store.NewID(), Code: "lock_a", Name: "x", Sector: "DANG", TenancyModel: "POOL", Status: "KHOI_TAO",
		MaxAccounts: 1, CreatedBy: store.SystemActor, Flows: []string{"DANG"}, KekRef: "k"}
	if err := store.InsertTenant(ctx, pool, again); !errors.As(err, &dup) || dup.Constraint != "uq_tenant__code" {
		t.Fatalf("muốn trùng uq_tenant__code, có %v", err)
	}
	d := &store.Domain{TenantID: ten.ID, Domain: "lock-a.okr.viettel.vn", Kind: "PRIMARY"}
	if err := store.InsertDomain(ctx, pool, d, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	other := seedTenant(t, ctx, pool, "lock_b")
	d2 := &store.Domain{TenantID: other.ID, Domain: "lock-a.okr.viettel.vn", Kind: "PRIMARY"}
	if err := store.InsertDomain(ctx, pool, d2, store.SystemActor); !errors.As(err, &dup) || dup.Constraint != "uq_tenant_domain__domain" {
		t.Fatalf("muốn trùng uq_tenant_domain__domain, có %v", err)
	}
	if id, err := store.TenantIDByDomain(ctx, pool, "lock-a.okr.viettel.vn"); err != nil || id != ten.ID {
		t.Fatalf("tra theo tên miền sai: %v", err)
	}
	idp := &store.Idp{TenantID: ten.ID, IdpAlias: "vneid", Protocol: "OIDC", Issuer: "https://sso.vneid.gov.vn", IsVneid: true}
	if err := store.InsertIdp(ctx, pool, idp, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	idp2 := &store.Idp{TenantID: ten.ID, IdpAlias: "vneid", Protocol: "SAML", Issuer: "x"}
	if err := store.InsertIdp(ctx, pool, idp2, store.SystemActor); !errors.As(err, &dup) || dup.Constraint != "uq_tenant_idp__tenant_id_idp_alias" {
		t.Fatalf("muốn trùng bí danh IdP, có %v", err)
	}
	idp.Status = "TAM_DUNG"
	if err := store.UpdateIdp(ctx, pool, idp, store.SystemActor); err != nil {
		t.Fatal(err)
	}
	idp.RowVersion = 0
	if err := store.UpdateIdp(ctx, pool, idp, store.SystemActor); !errors.As(err, &vc) {
		t.Fatalf("muốn lệch phiên bản IdP, có %v", err)
	}
	if list, _ := store.ListIdps(ctx, pool, ten.ID, true); len(list) != 0 {
		t.Fatalf("IdP tạm dừng không được trả khi lọc hoạt động")
	}
	// CHECK của TL-03.
	bad := &store.Tenant{ID: store.NewID(), Code: "Hoa-Chu", Name: "x", Sector: "DANG", TenancyModel: "POOL", Status: "KHOI_TAO",
		MaxAccounts: 1, CreatedBy: store.SystemActor, Flows: []string{"KHAC"}, KekRef: "k"}
	if err := store.InsertTenant(ctx, pool, bad); pgCode(err) != "23514" {
		t.Fatalf("muốn vi phạm CHECK, có %v", err)
	}
}
