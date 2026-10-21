package store_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"mission/services-go/ledger/internal/partition"
	"mission/services-go/ledger/internal/store"
	"mission/services-go/ledger/internal/testdb"
)

func pgCode(err error) string {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		return pgErr.Code
	}
	return ""
}

// TestMigrationsApplyCleanFromScratch kiểm chứng migration chạy sạch từ đầu trên CSDL trống bằng mig_ledger.
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

	wantTables := []string{
		"ledger_entry",
		"chain_head",
		"cell_seal",
		"merkle_seal",
		"anchor_receipt",
		"verify_run",
		"archive_segment",
		"outbox_event",
	}
	for _, tbl := range wantTables {
		var ok bool
		if err := conn.QueryRow(ctx, `SELECT to_regclass('ledger.' || $1) IS NOT NULL`, tbl).Scan(&ok); err != nil || !ok {
			t.Errorf("thiếu bảng ledger.%s: %v", tbl, err)
		}
		var owner string
		if err := conn.QueryRow(ctx, `
			SELECT r.rolname
			  FROM pg_class c
			  JOIN pg_namespace n ON n.oid = c.relnamespace
			  JOIN pg_roles r ON r.oid = c.relowner
			 WHERE n.nspname = 'ledger' AND c.relname = $1
		`, tbl).Scan(&owner); err != nil || owner != "mig_ledger" {
			t.Errorf("bảng ledger.%s phải thuộc sở hữu mig_ledger, có %q (err=%v)", tbl, owner, err)
		}
	}

	// Kiểm tra chain_head có fillfactor=50 theo TL-03 §2.8
	var relopts []string
	if err := conn.QueryRow(ctx, `
		SELECT COALESCE(c.reloptions, ARRAY[]::text[])
		  FROM pg_class c
		  JOIN pg_namespace n ON n.oid = c.relnamespace
		 WHERE n.nspname = 'ledger' AND c.relname = 'chain_head'
	`).Scan(&relopts); err != nil {
		t.Fatalf("đọc reloptions của chain_head: %v", err)
	}
	foundFillfactor := false
	for _, opt := range relopts {
		if opt == "fillfactor=50" {
			foundFillfactor = true
			break
		}
	}
	if !foundFillfactor {
		t.Errorf("bảng ledger.chain_head phải có fillfactor=50, có %v", relopts)
	}
}

// TestMigrationNamingAndClassificationLint kiểm tra quy ước đặt tên và chú thích phân loại cột theo TL-03 §12.
func TestMigrationNamingAndClassificationLint(t *testing.T) {
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, db.AdminDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)

	prefix := map[string]string{
		"p": "pk_",
		"f": "fk_",
		"u": "uq_",
		"c": "ck_",
		"x": "ex_",
	}
	rows, err := conn.Query(ctx, `
		SELECT con.conname, con.contype::text, rel.relname
		  FROM pg_constraint con
		  JOIN pg_class rel ON rel.oid = con.conrelid
		  JOIN pg_namespace n ON n.oid = rel.relnamespace
		 WHERE n.nspname = 'ledger'
		   AND con.contype IN ('p', 'f', 'u', 'c', 'x')
		   AND rel.relname NOT LIKE '%_default'
		   AND rel.relname NOT LIKE 'ledger_entry_t_%'
	`)
	if err != nil {
		t.Fatal(err)
	}
	constraintCount := 0
	for rows.Next() {
		var name, typ, rel string
		_ = rows.Scan(&name, &typ, &rel)
		constraintCount++
		if !strings.HasPrefix(name, prefix[typ]+rel) {
			t.Errorf("ràng buộc %s của bảng %s phải bắt đầu bằng %s%s", name, rel, prefix[typ], rel)
		}
		if typ == "f" && !regexp.MustCompile(`^fk_[a-z_]+__[a-z_]+$`).MatchString(name) {
			t.Errorf("khoá ngoại %s phải dạng fk_<bảng>__<bảng đích>", name)
		}
	}
	rows.Close()
	if constraintCount < 25 {
		t.Errorf("quá ít ràng buộc (%d), kiểm tra lại schema ledger", constraintCount)
	}

	idxRows, err := conn.Query(ctx, `
		SELECT indexname, tablename
		  FROM pg_indexes
		 WHERE schemaname = 'ledger'
		   AND tablename NOT LIKE '%_default'
		   AND tablename NOT LIKE 'ledger_entry_t_%'
	`)
	if err != nil {
		t.Fatal(err)
	}
	for idxRows.Next() {
		var idxName, tblName string
		_ = idxRows.Scan(&idxName, &tblName)
		if !regexp.MustCompile(`^(pk|uq|ex|ix)_[a-z0-9_]+$`).MatchString(idxName) {
			t.Errorf("chỉ mục %s trên bảng %s sai quy ước đặt tên", idxName, tblName)
		}
	}
	idxRows.Close()

	colRows, err := conn.Query(ctx, `
		SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod), col_description(c.oid, a.attnum)
		  FROM pg_attribute a
		  JOIN pg_class c ON c.oid = a.attrelid
		  JOIN pg_namespace n ON n.oid = c.relnamespace
		 WHERE n.nspname = 'ledger'
		   AND c.relkind IN ('r', 'p')
		   AND a.attnum > 0
		   AND NOT a.attisdropped
		   AND c.relname NOT LIKE '%_default'
		   AND c.relname NOT LIKE 'ledger_entry_t_%'
	`)
	if err != nil {
		t.Fatal(err)
	}
	defer colRows.Close()

	for colRows.Next() {
		var rel, col, typ string
		var comment *string
		_ = colRows.Scan(&rel, &col, &typ, &comment)
		if col != strings.ToLower(col) {
			t.Errorf("%s.%s chứa chữ hoa", rel, col)
		}
		if strings.HasSuffix(col, "_at") && typ != "timestamp with time zone" {
			t.Errorf("%s.%s phải có kiểu timestamptz, có %s", rel, col, typ)
		}
		if (strings.HasPrefix(col, "is_") || strings.HasPrefix(col, "has_")) && typ != "boolean" {
			t.Errorf("%s.%s phải có kiểu boolean, có %s", rel, col, typ)
		}
		if comment == nil {
			t.Errorf("%s.%s thiếu chú thích phân loại JSON", rel, col)
			continue
		}
		var meta struct {
			Cls           string `json:"cls"`
			Desc          string `json:"desc"`
			TypeException string `json:"type_exception"`
		}
		if err := json.Unmarshal([]byte(*comment), &meta); err != nil || !regexp.MustCompile(`^C[0-3]$`).MatchString(meta.Cls) {
			t.Errorf("%s.%s chú thích phân loại sai: %s", rel, col, *comment)
		}
		if typ == "smallint" && strings.TrimSpace(meta.TypeException) == "" {
			t.Errorf("%s.%s dùng kiểu smallint phải khai báo type_exception trong comment JSON", rel, col)
		}
	}
}

// TestImmutabilityPrivilegesAndRoleMatrix kiểm chứng:
// - UPDATE, DELETE, TRUNCATE bằng svc_ledger, ro_ledger, cdc_ledger và mig_ledger trên
//   ledger_entry, cell_seal, merkle_seal, anchor_receipt đều bị từ chối với mã lỗi 42501.
// - Ma trận phân quyền đọc/ghi của từng role đúng chuẩn SEC-06 / TL-03 §12.2.
func TestImmutabilityPrivilegesAndRoleMatrix(t *testing.T) {
	ctx := context.Background()

	svc, err := pgx.Connect(ctx, db.SvcDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Close(ctx)

	ro, err := pgx.Connect(ctx, db.RoDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer ro.Close(ctx)

	cdc, err := pgx.Connect(ctx, db.CdcDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer cdc.Close(ctx)

	mig, err := pgx.Connect(ctx, db.MigDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer mig.Close(ctx)

	// 1. Không role nào được BYPASSRLS hay SUPERUSER
	var bypassOrSuper bool
	if err := ro.QueryRow(ctx, `
		SELECT bool_or(rolbypassrls OR rolsuper)
		  FROM pg_roles
		 WHERE rolname IN ('mig_ledger', 'svc_ledger', 'ro_ledger', 'cdc_ledger')
	`).Scan(&bypassOrSuper); err != nil {
		t.Fatal(err)
	}
	if bypassOrSuper {
		t.Fatal("không vai trò nào của ledger được phép BYPASSRLS hoặc SUPERUSER")
	}

	// 2. Tạo phân vùng cho một tenant kiểm thử và chèn bản ghi mẫu bằng svc_ledger
	tenantID := uuid.MustParse("11111111-1111-4111-8111-111111111111")
	if _, err := partition.EnsureTenantPartitions(ctx, svc, tenantID, 202610, 202611); err != nil {
		t.Fatalf("svc_ledger phải gọi được fn_ensure_tenant_partitions: %v", err)
	}

	hash32 := bytes.Repeat([]byte{0xab}, 32)
	prev32 := bytes.Repeat([]byte{0x00}, 32)
	tx, err := svc.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenantID.String()); err != nil {
		t.Fatal(err)
	}

	entryID := uuid.New()
	eventID := uuid.New()
	if _, err := tx.Exec(ctx, `
		INSERT INTO ledger.ledger_entry (
			id, tenant_id, recorded_month, chain_partition, seq_no, event_id, event_type,
			aggregate_type, aggregate_id, actor_id, actor_kind,
			occurred_at, recorded_at, payload_hash, metadata, metadata_hash,
			prev_hash, entry_hash
		) VALUES (
			$1, $2, 202610, 0, 1, $3, 'EVT-01',
			'Task', $4, $5, 'NGUOI',
			'2026-10-15T08:00:00Z', '2026-10-15T08:00:01Z', $6, '{"event":"test"}'::jsonb, $6,
			$7, $6
		)
	`, entryID, tenantID, eventID, uuid.New(), store.SystemActor, hash32, prev32); err != nil {
		t.Fatalf("svc_ledger phải INSERT được vào ledger_entry: %v", err)
	}

	if _, err := tx.Exec(ctx, `
		INSERT INTO ledger.chain_head (tenant_id, chain_partition, last_seq_no, last_hash, updated_at)
		VALUES ($1, 0, 1, $2, now())
	`, tenantID, hash32); err != nil {
		t.Fatalf("svc_ledger phải INSERT được vào chain_head: %v", err)
	}

	if _, err := tx.Exec(ctx, `
		UPDATE ledger.chain_head SET last_seq_no = 2, updated_at = now()
		 WHERE tenant_id = $1 AND chain_partition = 0
	`, tenantID); err != nil {
		t.Fatalf("svc_ledger phải UPDATE được chain_head: %v", err)
	}

	cellSealID := uuid.New()
	if _, err := tx.Exec(ctx, `
		INSERT INTO ledger.cell_seal (
			id, cell_code, window_start, window_end, cell_root_hash,
			tenant_count, total_entries, signer_key_id, signature,
			signer_cert_sha256, rfc3161_tst, prev_cell_root_hash
		) VALUES (
			$1, 'CELL-SHARED-01', '2026-10-15T08:00:00Z', '2026-10-15T08:05:00Z', $2,
			1, 1, 'key-cell-01', $2, $2, $2, $3
		)
	`, cellSealID, hash32, prev32); err != nil {
		t.Fatalf("svc_ledger phải INSERT được vào cell_seal: %v", err)
	}

	merkleSealID := uuid.New()
	if _, err := tx.Exec(ctx, `
		INSERT INTO ledger.merkle_seal (
			id, tenant_id, cell_seal_id, window_start, window_end,
			seq_ranges, leaf_count, tenant_root_hash, inclusion_proof, prev_root_hash
		) VALUES (
			$1, $2, $3, '2026-10-15T08:00:00Z', '2026-10-15T08:05:00Z',
			'[]'::jsonb, 1, $4, '[]'::jsonb, $5
		)
	`, merkleSealID, tenantID, cellSealID, hash32, prev32); err != nil {
		t.Fatalf("svc_ledger phải INSERT được vào merkle_seal: %v", err)
	}

	if _, err := tx.Exec(ctx, `
		INSERT INTO ledger.anchor_receipt (
			id, cell_seal_id, anchor_target, receipt, anchored_at
		) VALUES (
			$1, $2, 'TSA_DOC_LAP', $3, '2026-10-15T08:05:05Z'
		)
	`, uuid.New(), cellSealID, hash32); err != nil {
		t.Fatalf("svc_ledger phải INSERT được vào anchor_receipt: %v", err)
	}

	verifyID := uuid.New()
	if _, err := tx.Exec(ctx, `
		INSERT INTO ledger.verify_run (
			id, tenant_id, verify_kind, range_from, range_to,
			entries_checked, mismatches, started_at
		) VALUES (
			$1, $2, 'DAU_CHUOI', '2026-10-15T08:00:00Z', '2026-10-15T09:00:00Z',
			1, 0, now()
		)
	`, verifyID, tenantID); err != nil {
		t.Fatalf("svc_ledger phải INSERT được vào verify_run: %v", err)
	}
	if _, err := tx.Exec(ctx, `
		UPDATE ledger.verify_run SET finished_at = now() WHERE id = $1
	`, verifyID); err != nil {
		t.Fatalf("svc_ledger phải UPDATE được verify_run: %v", err)
	}

	segID := uuid.New()
	if _, err := tx.Exec(ctx, `
		INSERT INTO ledger.archive_segment (
			id, tenant_id, recorded_month, seq_ranges, object_key,
			file_sha256, row_count, lock_until, verified_at
		) VALUES (
			$1, $2, 202610, '[]'::jsonb, 's3://ledger/seg1.parquet',
			$3, 1, '2036-10-15', now()
		)
	`, segID, tenantID, hash32); err != nil {
		t.Fatalf("svc_ledger phải INSERT được vào archive_segment: %v", err)
	}
	if _, err := tx.Exec(ctx, `
		UPDATE ledger.archive_segment SET detached_at = now() WHERE id = $1
	`, segID); err != nil {
		t.Fatalf("svc_ledger phải UPDATE được archive_segment: %v", err)
	}

	if _, err := tx.Exec(ctx, `
		INSERT INTO ledger.outbox_event (
			id, tenant_id, aggregate_type, aggregate_id, aggregate_version,
			event_type, topic, event_key, payload, schema_version
		) VALUES (
			$1, $2, 'LedgerChain', $2, 1,
			'EVT-19', 'sys.ledger.seal.v1', $3, $4, 1
		)
	`, uuid.New(), tenantID, tenantID.String(), []byte(`{"ok":true}`)); err != nil {
		t.Fatalf("svc_ledger phải INSERT được vào outbox_event: %v", err)
	}

	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}

	// 3. Kiểm tra UPDATE, DELETE, TRUNCATE trên 4 bảng bất biến bằng cả 4 role đều trả về 42501
	immutableMutations := []string{
		`UPDATE ledger.ledger_entry SET event_type = 'TAMPERED'`,
		`DELETE FROM ledger.ledger_entry`,
		`TRUNCATE ledger.ledger_entry`,
		`UPDATE ledger.cell_seal SET total_entries = 999`,
		`DELETE FROM ledger.cell_seal`,
		`TRUNCATE ledger.cell_seal`,
		`UPDATE ledger.merkle_seal SET leaf_count = 999`,
		`DELETE FROM ledger.merkle_seal`,
		`TRUNCATE ledger.merkle_seal`,
		`UPDATE ledger.anchor_receipt SET anchor_target = 'KHO_VPTW'`,
		`DELETE FROM ledger.anchor_receipt`,
		`TRUNCATE ledger.anchor_receipt`,
	}

	roles := []struct {
		name string
		conn *pgx.Conn
	}{
		{"svc_ledger", svc},
		{"ro_ledger", ro},
		{"cdc_ledger", cdc},
		{"mig_ledger", mig},
	}

	for _, r := range roles {
		for _, sqlStmt := range immutableMutations {
			err := execWithTenant(ctx, r.conn, tenantID, sqlStmt)
			if code := pgCode(err); code != "42501" {
				t.Errorf("role %s chạy %q phải bị từ chối với 42501, nhưng nhận err=%v (code=%q)", r.name, sqlStmt, err, code)
			}
		}
	}

	// Kiểm tra thêm TRUNCATE trực tiếp trên phân vùng lá bằng mig_ledger cũng bị chặn bởi trigger 42501
	leafTable := fmt.Sprintf("ledger.ledger_entry_t_%s_m202610", strings.ReplaceAll(tenantID.String(), "-", ""))
	if err := execWithTenant(ctx, mig, tenantID, "TRUNCATE "+leafTable); pgCode(err) != "42501" {
		t.Errorf("mig_ledger chạy TRUNCATE trên phân vùng lá %s phải bị từ chối với 42501, có %v", leafTable, err)
	}

	// 4. Kiểm tra svc_ledger không có quyền DDL trực tiếp
	for _, ddl := range []string{
		`CREATE TABLE ledger.hack (id int)`,
		`ALTER TABLE ledger.chain_head ADD COLUMN hack int`,
		`DROP TABLE ledger.verify_run`,
	} {
		if _, err := svc.Exec(ctx, ddl); pgCode(err) != "42501" {
			t.Errorf("svc_ledger phải bị từ chối DDL %q với 42501, có %v", ddl, err)
		}
	}

	// 5. Kiểm tra ro_ledger chỉ đọc được các bảng sổ cái và không đọc được outbox_event
	if err := execWithTenant(ctx, ro, tenantID, `SELECT 1 FROM ledger.ledger_entry LIMIT 1`); err != nil {
		t.Errorf("ro_ledger phải SELECT được ledger_entry: %v", err)
	}
	if err := execWithTenant(ctx, ro, tenantID, `SELECT 1 FROM ledger.outbox_event LIMIT 1`); pgCode(err) != "42501" {
		t.Errorf("ro_ledger không được SELECT outbox_event (42501), có %v", err)
	}
	if err := execWithTenant(ctx, ro, tenantID, `UPDATE ledger.chain_head SET last_seq_no = 99`); pgCode(err) != "42501" {
		t.Errorf("ro_ledger không được UPDATE chain_head (42501), có %v", err)
	}

	// 6. Kiểm tra cdc_ledger chỉ đọc được ledger_entry và outbox_event
	if err := execWithTenant(ctx, cdc, tenantID, `SELECT 1 FROM ledger.ledger_entry LIMIT 1`); err != nil {
		t.Errorf("cdc_ledger phải SELECT được ledger_entry: %v", err)
	}
	if err := execWithTenant(ctx, cdc, tenantID, `SELECT 1 FROM ledger.outbox_event LIMIT 1`); err != nil {
		t.Errorf("cdc_ledger phải SELECT được outbox_event: %v", err)
	}
	if err := execWithTenant(ctx, cdc, tenantID, `SELECT 1 FROM ledger.chain_head LIMIT 1`); pgCode(err) != "42501" {
		t.Errorf("cdc_ledger không được SELECT chain_head (42501), có %v", err)
	}
}

// TestTwoLevelPartitioningPruningAndRLS kiểm chứng:
// - Tạo phân vùng 2 tầng LIST(tenant_id) -> RANGE(recorded_month) cho 2 tenant qua 2 tháng (idempotent).
// - Chèn 100 bản ghi mẫu mỗi tenant.
// - EXPLAIN xác nhận partition pruning chỉ quét đúng 1 phân vùng lá.
// - RLS chặn đọc chéo tenant và chặn đọc khi chưa đặt GUC app.tenant_id.
func TestTwoLevelPartitioningPruningAndRLS(t *testing.T) {
	ctx := context.Background()
	svcPool := testdb.Pool(ctx, t, db.SvcDSN)
	roPool := testdb.Pool(ctx, t, db.RoDSN)

	tenantA := uuid.MustParse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
	tenantB := uuid.MustParse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

	// Tạo phân vùng tháng 202610..202611 cho cả 2 tenant
	createdA, err := partition.EnsureTenantPartitions(ctx, svcPool, tenantA, 202610, 202611)
	if err != nil {
		t.Fatalf("tạo phân vùng tenantA: %v", err)
	}
	if createdA != 3 { // 1 parent LIST + 2 leaf RANGE
		t.Fatalf("lần đầu tạo phân vùng cho tenantA phải tạo 3 bảng, có %d", createdA)
	}
	// Gọi lại lần 2 phải idempotent (0 phân vùng mới)
	createdAgain, err := partition.EnsureTenantPartitions(ctx, svcPool, tenantA, 202610, 202611)
	if err != nil || createdAgain != 0 {
		t.Fatalf("EnsureTenantPartitions phải idempotent: created=%d err=%v", createdAgain, err)
	}

	if _, err := partition.EnsureTenantPartitions(ctx, svcPool, tenantB, 202610, 202611); err != nil {
		t.Fatalf("tạo phân vùng tenantB: %v", err)
	}

	// Kiểm tra hàm tạo trước phân vùng tháng kế tiếp cho mọi tenant
	if _, err := partition.EnsureUpcomingMonthPartitions(ctx, svcPool, time.Date(2026, 11, 15, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatalf("EnsureUpcomingMonthPartitions lỗi: %v", err)
	}

	// Chèn 100 bản ghi cho mỗi tenant (50 ở 202610, 50 ở 202611)
	for _, ten := range []uuid.UUID{tenantA, tenantB} {
		tx, err := svcPool.Begin(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, ten.String()); err != nil {
			_ = tx.Rollback(ctx)
			t.Fatal(err)
		}
		for i := 1; i <= 100; i++ {
			month := 202610
			recordedAt := time.Date(2026, 10, 10, 12, 0, i%60, 0, time.UTC)
			if i > 50 {
				month = 202611
				recordedAt = time.Date(2026, 11, 10, 12, 0, i%60, 0, time.UTC)
			}
			h := bytes.Repeat([]byte{byte(i)}, 32)
			p := bytes.Repeat([]byte{byte(i - 1)}, 32)
			if _, err := tx.Exec(ctx, `
				INSERT INTO ledger.ledger_entry (
					id, tenant_id, recorded_month, chain_partition, seq_no, event_id, event_type,
					aggregate_type, aggregate_id, actor_id, actor_kind,
					occurred_at, recorded_at, payload_hash, metadata, metadata_hash,
					prev_hash, entry_hash
				) VALUES (
					$1, $2, $9, $3, $4, $5, 'EVT-02',
					'KeyResult', $6, $7, 'NGUOI',
					$8, $8, $10, '{"seq":1}'::jsonb, $10,
					$11, $10
				)
			`, uuid.New(), ten, int16(i%64), int64(i), uuid.New(), uuid.New(), store.SystemActor, recordedAt, month, h, p); err != nil {
				_ = tx.Rollback(ctx)
				t.Fatalf("chèn ledger_entry cho tenant %s dòng %d: %v", ten, i, err)
			}
		}
		if err := tx.Commit(ctx); err != nil {
			t.Fatal(err)
		}
	}

	// Kiểm tra EXPLAIN (FORMAT JSON) xác nhận partition pruning chỉ chạm đúng 1 phân vùng lá
	txExplain, err := roPool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = txExplain.Rollback(ctx) }()
	if _, err := txExplain.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenantA.String()); err != nil {
		t.Fatal(err)
	}
	var explainJSON string
	if err := txExplain.QueryRow(ctx, `
		EXPLAIN (FORMAT JSON)
		SELECT id, seq_no
		  FROM ledger.ledger_entry
		 WHERE tenant_id = $1 AND recorded_month = 202610
	`, tenantA).Scan(&explainJSON); err != nil {
		t.Fatalf("chạy EXPLAIN: %v", err)
	}
	wantLeaf := fmt.Sprintf("ledger_entry_t_%s_m202610", strings.ReplaceAll(tenantA.String(), "-", ""))
	otherMonthLeaf := fmt.Sprintf("ledger_entry_t_%s_m202611", strings.ReplaceAll(tenantA.String(), "-", ""))
	otherTenantLeaf := fmt.Sprintf("ledger_entry_t_%s_m202610", strings.ReplaceAll(tenantB.String(), "-", ""))

	if !strings.Contains(explainJSON, wantLeaf) {
		t.Errorf("EXPLAIN phải quét phân vùng lá %s, nhưng kết quả là: %s", wantLeaf, explainJSON)
	}
	if strings.Contains(explainJSON, otherMonthLeaf) {
		t.Errorf("EXPLAIN không được quét phân vùng tháng khác (%s): %s", otherMonthLeaf, explainJSON)
	}
	if strings.Contains(explainJSON, otherTenantLeaf) {
		t.Errorf("EXPLAIN không được quét phân vùng của tenant khác (%s): %s", otherTenantLeaf, explainJSON)
	}

	// Kiểm tra RLS: đặt app.tenant_id = tenantA chỉ thấy 100 bản ghi của tenantA
	var countA int
	if err := txExplain.QueryRow(ctx, `SELECT count(*) FROM ledger.ledger_entry`).Scan(&countA); err != nil {
		t.Fatal(err)
	}
	if countA != 100 {
		t.Errorf("RLS với tenantA phải trả đúng 100 dòng, có %d", countA)
	}
	var leakB int
	if err := txExplain.QueryRow(ctx, `SELECT count(*) FROM ledger.ledger_entry WHERE tenant_id = $1`, tenantB).Scan(&leakB); err != nil {
		t.Fatal(err)
	}
	if leakB != 0 {
		t.Errorf("RLS không được lộ dòng nào của tenantB khi ngữ cảnh là tenantA, có %d", leakB)
	}
	_ = txExplain.Rollback(ctx)

	// Kiểm tra RLS khi KHÔNG đặt GUC app.tenant_id: phải trả về 0 dòng
	var countNoContext int
	if err := roPool.QueryRow(ctx, `SELECT count(*) FROM ledger.ledger_entry`).Scan(&countNoContext); err != nil {
		t.Fatal(err)
	}
	if countNoContext != 0 {
		t.Errorf("không đặt app.tenant_id phải trả về 0 dòng nhờ RLS, có %d", countNoContext)
	}
}

func execWithTenant(ctx context.Context, conn *pgx.Conn, tenantID uuid.UUID, sqlStmt string) error {
	tx, err := conn.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenantID.String()); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, sqlStmt); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
