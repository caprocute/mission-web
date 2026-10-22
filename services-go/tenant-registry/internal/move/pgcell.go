package move

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"path"
	"strings"
	"sync"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Loc là một CSDL dữ liệu tenant trên cụm PostgreSQL của một cell (registry.tenant_cell.database_name).
type Loc struct {
	Cell string
	DB   string
}

// Table là một bảng có cột tenant_id cần sao chép.
type Table struct {
	Schema      string   `json:"schema"`
	Name        string   `json:"name"`
	Partitioned bool     `json:"partitioned,omitempty"`
	ByTenant    bool     `json:"byTenant,omitempty"` // phân vùng LIST (tenant_id)
	Leaves      []string `json:"leaves,omitempty"`   // bảng lá (tên đủ, đã trích dẫn)
}

// Qualified trả tên đủ đã trích dẫn.
func (t Table) Qualified() string { return pgx.Identifier{t.Schema, t.Name}.Sanitize() }

// Key là schema.table.
func (t Table) Key() string { return t.Schema + "." + t.Name }

// Checksum là số dòng và tổng băm của các dòng một tenant trong một bảng.
type Checksum struct {
	Rows int64  `json:"rows"`
	Sum  string `json:"sum"`
}

// CellDBConfig là thông tin kết nối cụm PostgreSQL của một cell (bí mật, nạp từ OpenBao qua External Secrets).
type CellDBConfig struct {
	// AdminURL: chuỗi kết nối vai trò quản trị sao chép của cụm (cần quyền REPLICATION, tạo publication,
	// subscription, trigger); tên CSDL trong URL được thay bằng CSDL dữ liệu tenant.
	AdminURL string `json:"adminUrl"`
	// PeerConnInfo: conninfo libpq để cụm khác kết nối tới cụm này làm nguồn sao chép (dbname được thêm vào).
	PeerConnInfo string `json:"peerConnInfo"`
}

// PGCells hiện thực các thao tác sao chép logic trên hai cụm PostgreSQL ≥ 15 (publication có bộ lọc dòng
// WHERE (tenant_id = '<id>'), subscription ở cụm đích), so khớp số dòng và tổng băm, rào chặn ghi theo tenant.
type PGCells struct {
	Cells map[string]CellDBConfig
	// Exclude: bảng không sao chép (mẫu schema.table, "*" thay schema); mặc định *.outbox_event, *.inbox_event
	// (outbox của cell đích không được phát lại sự kiện cũ).
	Exclude []string

	mu    sync.Mutex
	pools map[string]*pgxpool.Pool
}

// FenceSchema chứa hàm rào chặn ghi theo tenant trên cụm nguồn.
const FenceSchema = "registry_move"

func (p *PGCells) excluded(t Table) bool {
	ex := p.Exclude
	if ex == nil {
		ex = []string{"*.outbox_event", "*.inbox_event"}
	}
	for _, e := range ex {
		s, n, _ := strings.Cut(e, ".")
		if (s == "*" || s == t.Schema) && (n == "*" || n == t.Name) {
			return true
		}
	}
	return false
}

func (p *PGCells) pool(ctx context.Context, l Loc) (*pgxpool.Pool, error) {
	c, ok := p.Cells[l.Cell]
	if !ok || c.AdminURL == "" {
		return nil, fmt.Errorf("move: chưa cấu hình CSDL của cell %s", l.Cell)
	}
	u, err := url.Parse(c.AdminURL)
	if err != nil {
		return nil, fmt.Errorf("move: địa chỉ CSDL của cell %s không hợp lệ", l.Cell)
	}
	u.Path = "/" + path.Clean(l.DB)
	dsn := u.String()
	p.mu.Lock()
	defer p.mu.Unlock()
	if pl, ok := p.pools[dsn]; ok {
		return pl, nil
	}
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, err
	}
	// Biểu diễn văn bản của dòng giống nhau ở hai cụm khi tính tổng băm.
	cfg.ConnConfig.RuntimeParams["TimeZone"] = "UTC"
	cfg.ConnConfig.RuntimeParams["DateStyle"] = "ISO, YMD"
	cfg.ConnConfig.RuntimeParams["IntervalStyle"] = "postgres"
	cfg.ConnConfig.RuntimeParams["extra_float_digits"] = "3"
	cfg.ConnConfig.RuntimeParams["application_name"] = "tenant-registry-move"
	cfg.MaxConns = 4
	pl, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, err
	}
	if p.pools == nil {
		p.pools = map[string]*pgxpool.Pool{}
	}
	p.pools[dsn] = pl
	return pl, nil
}

// Close đóng các pool.
func (p *PGCells) Close() {
	p.mu.Lock()
	defer p.mu.Unlock()
	for k, pl := range p.pools {
		pl.Close()
		delete(p.pools, k)
	}
}

// Tables liệt kê bảng gốc (không phải phân vùng con) có cột tenant_id ở CSDL nguồn.
func (p *PGCells) Tables(ctx context.Context, src Loc) ([]Table, error) {
	db, err := p.pool(ctx, src)
	if err != nil {
		return nil, err
	}
	rows, err := db.Query(ctx, `SELECT n.nspname, c.relname, c.relkind = 'p',
		  c.relkind = 'p' AND pg_get_partkeydef(c.oid) ~* '^LIST \(tenant_id\)$'
		FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
		JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
		WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition
		  AND n.nspname NOT IN ('pg_catalog', 'information_schema', $1) AND n.nspname NOT LIKE 'pg\_%'
		ORDER BY 1, 2`, FenceSchema)
	if err != nil {
		return nil, err
	}
	all, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (Table, error) {
		var t Table
		return t, r.Scan(&t.Schema, &t.Name, &t.Partitioned, &t.ByTenant)
	})
	if err != nil {
		return nil, err
	}
	var out []Table
	for _, t := range all {
		if p.excluded(t) {
			continue
		}
		if t.Partitioned {
			lr, err := db.Query(ctx, `SELECT relid::regclass::text FROM pg_partition_tree($1::regclass) WHERE isleaf ORDER BY 1`, t.Qualified())
			if err != nil {
				return nil, err
			}
			if t.Leaves, err = pgx.CollectRows(lr, pgx.RowTo[string]); err != nil {
				return nil, err
			}
		} else {
			t.Leaves = []string{t.Qualified()}
		}
		out = append(out, t)
	}
	return out, nil
}

func columns(ctx context.Context, db *pgxpool.Pool, qualified string) (string, error) {
	var cols *string
	err := db.QueryRow(ctx, `SELECT string_agg(attname || ':' || format_type(atttypid, atttypmod), ',' ORDER BY attnum)
		FROM pg_attribute WHERE attrelid = to_regclass($1) AND attnum > 0 AND NOT attisdropped`, qualified).Scan(&cols)
	if err != nil || cols == nil {
		return "", err
	}
	return *cols, nil
}

// tenantPartition trả (tên đủ, biểu thức FOR VALUES) của phân vùng chứa tenant dưới bảng cha.
func tenantPartition(ctx context.Context, db *pgxpool.Pool, parent string, tenantID uuid.UUID) (string, string, error) {
	rows, err := db.Query(ctx, `SELECT c.oid::regclass::text, pg_get_expr(c.relpartbound, c.oid) FROM pg_inherits i
		JOIN pg_class c ON c.oid = i.inhrelid WHERE i.inhparent = $1::regclass`, parent)
	if err != nil {
		return "", "", err
	}
	defer rows.Close()
	for rows.Next() {
		var name, bound string
		if err := rows.Scan(&name, &bound); err != nil {
			return "", "", err
		}
		if strings.Contains(bound, "'"+tenantID.String()+"'") {
			return name, bound, nil
		}
	}
	return "", "", rows.Err()
}

// PrepareTarget kiểm CSDL đích có đủ bảng cùng cấu trúc (schema do Flyway của cell đích tạo), tạo phân vùng
// của tenant nếu bảng phân vùng theo tenant, và xoá dòng sót của tenant từ lần chạy trước. Trả các phân vùng
// đã tạo (để quay lui).
func (p *PGCells) PrepareTarget(ctx context.Context, src, dst Loc, tenantID uuid.UUID, tables []Table) ([]string, error) {
	sdb, err := p.pool(ctx, src)
	if err != nil {
		return nil, err
	}
	ddb, err := p.pool(ctx, dst)
	if err != nil {
		return nil, err
	}
	var created []string
	for _, t := range tables {
		sc, err := columns(ctx, sdb, t.Qualified())
		if err != nil {
			return created, err
		}
		dc, err := columns(ctx, ddb, t.Qualified())
		if err != nil {
			return created, err
		}
		if dc == "" {
			return created, &SchemaError{Table: t.Key(), Reason: "chưa có ở CSDL đích"}
		}
		if sc != dc {
			return created, &SchemaError{Table: t.Key(), Reason: "cấu trúc cột khác CSDL nguồn"}
		}
		if t.ByTenant {
			name, bound, err := tenantPartition(ctx, sdb, t.Qualified(), tenantID)
			if err != nil {
				return created, err
			}
			if name == "" {
				continue // tenant chưa có phân vùng ở nguồn: không có dữ liệu
			}
			have, _, err := tenantPartition(ctx, ddb, t.Qualified(), tenantID)
			if err != nil {
				return created, err
			}
			if have == "" {
				// Tên phân vùng lấy nguyên từ nguồn (đã trích dẫn), biên giữ nguyên.
				if _, err := ddb.Exec(ctx, "CREATE TABLE "+name+" PARTITION OF "+t.Qualified()+" "+bound); err != nil {
					return created, err
				}
				created = append(created, name)
			}
		}
		if _, err := ddb.Exec(ctx, "DELETE FROM "+t.Qualified()+" WHERE tenant_id = $1", tenantID); err != nil {
			return created, err
		}
	}
	return created, nil
}

// EnsureReplicaIdentity: cột lọc của publication phải nằm trong replica identity, nếu không thì UPDATE/DELETE
// của mọi tenant trên bảng bị PostgreSQL từ chối. Bảng lá chưa bao tenant_id được đặt REPLICA IDENTITY FULL trong
// thời gian chuyển; trả danh sách "bảng|chế độ cũ|chỉ mục" để khôi phục.
func (p *PGCells) EnsureReplicaIdentity(ctx context.Context, src Loc, tables []Table) ([]string, error) {
	db, err := p.pool(ctx, src)
	if err != nil {
		return nil, err
	}
	var changed []string
	for _, t := range tables {
		for _, leaf := range t.Leaves {
			var mode string
			var covered bool
			var idx *string
			err := db.QueryRow(ctx, `SELECT c.relreplident::text,
				  coalesce((SELECT bool_or(a.attname = 'tenant_id') FROM pg_index i
				    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
				    WHERE i.indrelid = c.oid AND ((c.relreplident = 'd' AND i.indisprimary) OR (c.relreplident = 'i' AND i.indisreplident))), false),
				  (SELECT i.indexrelid::regclass::text FROM pg_index i WHERE i.indrelid = c.oid AND i.indisreplident)
				FROM pg_class c WHERE c.oid = $1::regclass`, leaf).Scan(&mode, &covered, &idx)
			if err != nil {
				return changed, err
			}
			if mode == "f" || covered {
				continue
			}
			if _, err := db.Exec(ctx, "ALTER TABLE "+leaf+" REPLICA IDENTITY FULL"); err != nil {
				return changed, err
			}
			ix := ""
			if idx != nil {
				ix = *idx
			}
			changed = append(changed, leaf+"|"+mode+"|"+ix)
		}
	}
	return changed, nil
}

// RestoreReplicaIdentity khôi phục replica identity đã đổi.
func (p *PGCells) RestoreReplicaIdentity(ctx context.Context, src Loc, changed []string) error {
	db, err := p.pool(ctx, src)
	if err != nil {
		return err
	}
	for _, c := range changed {
		parts := strings.SplitN(c, "|", 3)
		if len(parts) != 3 {
			continue
		}
		clause := "DEFAULT"
		switch parts[1] {
		case "n":
			clause = "NOTHING"
		case "i":
			if parts[2] != "" {
				i := parts[2]
				if dot := strings.LastIndexByte(i, '.'); dot >= 0 {
					i = i[dot+1:]
				}
				clause = "USING INDEX " + i
			}
		}
		if _, err := db.Exec(ctx, "ALTER TABLE "+parts[0]+" REPLICA IDENTITY "+clause); err != nil {
			return err
		}
	}
	return nil
}

func exists(ctx context.Context, db *pgxpool.Pool, sql string, args ...any) (bool, error) {
	var ok bool
	err := db.QueryRow(ctx, "SELECT EXISTS ("+sql+")", args...).Scan(&ok)
	return ok, err
}

// Publish tạo publication có bộ lọc dòng theo tenant trên cụm nguồn (publish_via_partition_root để bảng
// phân vùng sao chép qua bảng cha).
func (p *PGCells) Publish(ctx context.Context, src Loc, name string, tenantID uuid.UUID, tables []Table) error {
	db, err := p.pool(ctx, src)
	if err != nil {
		return err
	}
	if ok, err := exists(ctx, db, `SELECT 1 FROM pg_publication WHERE pubname = $1`, name); err != nil || ok {
		return err
	}
	parts := make([]string, 0, len(tables))
	for _, t := range tables {
		parts = append(parts, fmt.Sprintf("%s WHERE (tenant_id = '%s'::uuid)", t.Qualified(), tenantID))
	}
	if len(parts) == 0 {
		return errors.New("move: không có bảng nào để sao chép")
	}
	_, err = db.Exec(ctx, "CREATE PUBLICATION "+pgx.Identifier{name}.Sanitize()+" FOR TABLE "+strings.Join(parts, ", ")+
		" WITH (publish_via_partition_root = true)")
	return err
}

// Subscribe tạo subscription trên cụm đích (sao chép dữ liệu ban đầu rồi theo WAL).
func (p *PGCells) Subscribe(ctx context.Context, src, dst Loc, name string) error {
	db, err := p.pool(ctx, dst)
	if err != nil {
		return err
	}
	if ok, err := exists(ctx, db, `SELECT 1 FROM pg_subscription WHERE subname = $1`, name); err != nil || ok {
		return err
	}
	peer := p.Cells[src.Cell].PeerConnInfo
	if peer == "" {
		return fmt.Errorf("move: chưa cấu hình kết nối sao chép tới cell %s", src.Cell)
	}
	conn := strings.TrimSpace(peer) + " dbname=" + src.DB
	_, err = db.Exec(ctx, "CREATE SUBSCRIPTION "+pgx.Identifier{name}.Sanitize()+" CONNECTION "+quoteLiteral(conn)+
		" PUBLICATION "+pgx.Identifier{name}.Sanitize()+" WITH (copy_data = true, slot_name = "+quoteLiteral(name)+")")
	return err
}

func quoteLiteral(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

// Progress là tiến độ sao chép.
type Progress struct {
	TablesTotal, TablesReady int
	LagSeconds               float64
	LagBytes                 int64
	Streaming                bool
}

// CatchUp đọc tiến độ: số bảng đã đồng bộ ban đầu (pg_subscription_rel ở đích) và độ trễ của walsender
// (pg_stat_replication ở nguồn).
func (p *PGCells) CatchUp(ctx context.Context, src, dst Loc, name string) (Progress, error) {
	var pr Progress
	ddb, err := p.pool(ctx, dst)
	if err != nil {
		return pr, err
	}
	sdb, err := p.pool(ctx, src)
	if err != nil {
		return pr, err
	}
	err = ddb.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE sr.srsubstate = 'r') FROM pg_subscription_rel sr
		JOIN pg_subscription s ON s.oid = sr.srsubid WHERE s.subname = $1`, name).Scan(&pr.TablesTotal, &pr.TablesReady)
	if err != nil {
		return pr, err
	}
	var lag *float64
	var bytes *int64
	err = sdb.QueryRow(ctx, `SELECT extract(epoch FROM replay_lag)::float8, pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)::bigint
		FROM pg_stat_replication WHERE application_name = $1 LIMIT 1`, name).Scan(&lag, &bytes)
	if errors.Is(err, pgx.ErrNoRows) {
		return pr, nil
	}
	if err != nil {
		return pr, err
	}
	pr.Streaming = true
	if lag != nil {
		pr.LagSeconds = *lag
	}
	if bytes != nil {
		pr.LagBytes = *bytes
	}
	return pr, nil
}

// CurrentLSN trả LSN ghi hiện tại của cụm nguồn.
func (p *PGCells) CurrentLSN(ctx context.Context, src Loc) (string, error) {
	db, err := p.pool(ctx, src)
	if err != nil {
		return "", err
	}
	var lsn string
	return lsn, db.QueryRow(ctx, `SELECT pg_current_wal_lsn()::text`).Scan(&lsn)
}

// Flushed: cụm đích đã xác nhận ghi tới LSN.
func (p *PGCells) Flushed(ctx context.Context, src Loc, name, lsn string) (bool, error) {
	db, err := p.pool(ctx, src)
	if err != nil {
		return false, err
	}
	var ok *bool
	err = db.QueryRow(ctx, `SELECT confirmed_flush_lsn >= $2::pg_lsn FROM pg_replication_slots WHERE slot_name = $1`, name, lsn).Scan(&ok)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	return ok != nil && *ok, err
}

// Fence đặt rào chặn ghi dữ liệu của tenant trên cụm nguồn (trigger BEFORE ROW trả lỗi 25006
// read_only_sql_transaction); tenant khác trong cùng bảng không bị ảnh hưởng.
func (p *PGCells) Fence(ctx context.Context, src Loc, name string, tenantID uuid.UUID, tables []Table) error {
	db, err := p.pool(ctx, src)
	if err != nil {
		return err
	}
	if _, err := db.Exec(ctx, `CREATE SCHEMA IF NOT EXISTS `+FenceSchema+`;
		CREATE OR REPLACE FUNCTION `+FenceSchema+`.reject_write() RETURNS trigger LANGUAGE plpgsql AS $f$
		BEGIN
		  IF (TG_OP <> 'DELETE' AND NEW.tenant_id::text = TG_ARGV[0]) OR (TG_OP <> 'INSERT' AND OLD.tenant_id::text = TG_ARGV[0]) THEN
		    RAISE EXCEPTION 'Đơn vị đang chuyển hệ thống phục vụ, tạm thời chỉ đọc' USING ERRCODE = 'read_only_sql_transaction';
		  END IF;
		  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
		  RETURN NEW;
		END $f$`); err != nil {
		return err
	}
	trg := pgx.Identifier{name}.Sanitize()
	for _, t := range tables {
		ok, err := exists(ctx, db, `SELECT 1 FROM pg_trigger WHERE tgrelid = $1::regclass AND tgname = $2 AND tgparentid = 0`, t.Qualified(), name)
		if err != nil {
			return err
		}
		if ok {
			continue
		}
		if _, err := db.Exec(ctx, "CREATE TRIGGER "+trg+" BEFORE INSERT OR UPDATE OR DELETE ON "+t.Qualified()+
			" FOR EACH ROW EXECUTE FUNCTION "+FenceSchema+".reject_write("+quoteLiteral(tenantID.String())+")"); err != nil {
			return err
		}
	}
	return nil
}

// Unfence gỡ rào chặn ghi.
func (p *PGCells) Unfence(ctx context.Context, src Loc, name string, tables []Table) error {
	db, err := p.pool(ctx, src)
	if err != nil {
		return err
	}
	for _, t := range tables {
		if ok, err := tableExists(ctx, db, t); err != nil || !ok {
			if err != nil {
				return err
			}
			continue
		}
		if _, err := db.Exec(ctx, "DROP TRIGGER IF EXISTS "+pgx.Identifier{name}.Sanitize()+" ON "+t.Qualified()); err != nil {
			return err
		}
	}
	return nil
}

func tableExists(ctx context.Context, db *pgxpool.Pool, t Table) (bool, error) {
	return exists(ctx, db, `SELECT 1 WHERE to_regclass($1) IS NOT NULL`, t.Qualified())
}

// Checksums tính số dòng và tổng băm (tổng 60 bit đầu của md5 từng dòng, không phụ thuộc thứ tự) của tenant
// trong từng bảng.
func (p *PGCells) Checksums(ctx context.Context, l Loc, tenantID uuid.UUID, tables []Table) (map[string]Checksum, error) {
	db, err := p.pool(ctx, l)
	if err != nil {
		return nil, err
	}
	out := map[string]Checksum{}
	for _, t := range tables {
		var c Checksum
		err := db.QueryRow(ctx, `SELECT count(*), coalesce(sum(('x' || substr(md5(x::text), 1, 15))::bit(60)::bigint::numeric), 0)::text
			FROM `+t.Qualified()+` x WHERE x.tenant_id = $1`, tenantID).Scan(&c.Rows, &c.Sum)
		if err != nil {
			return nil, err
		}
		out[t.Key()] = c
	}
	return out, nil
}

// DropSubscription xoá subscription ở đích (kèm slot ở nguồn khi còn kết nối được).
func (p *PGCells) DropSubscription(ctx context.Context, dst Loc, name string) error {
	db, err := p.pool(ctx, dst)
	if err != nil {
		return err
	}
	_, err = db.Exec(ctx, "DROP SUBSCRIPTION IF EXISTS "+pgx.Identifier{name}.Sanitize())
	return err
}

// DropPublication xoá publication và slot còn sót ở nguồn.
func (p *PGCells) DropPublication(ctx context.Context, src Loc, name string) error {
	db, err := p.pool(ctx, src)
	if err != nil {
		return err
	}
	if _, err := db.Exec(ctx, "DROP PUBLICATION IF EXISTS "+pgx.Identifier{name}.Sanitize()); err != nil {
		return err
	}
	_, err = db.Exec(ctx, `SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = $1 AND NOT active`, name)
	return err
}

// DeleteTenantRows xoá dữ liệu của tenant (quay lui ở đích; dọn nguồn sau thời hạn giữ).
func (p *PGCells) DeleteTenantRows(ctx context.Context, l Loc, tenantID uuid.UUID, tables []Table) (int64, error) {
	db, err := p.pool(ctx, l)
	if err != nil {
		return 0, err
	}
	var n int64
	for _, t := range tables {
		if ok, err := tableExists(ctx, db, t); err != nil || !ok {
			if err != nil {
				return n, err
			}
			continue
		}
		tag, err := db.Exec(ctx, "DELETE FROM "+t.Qualified()+" WHERE tenant_id = $1", tenantID)
		if err != nil {
			return n, err
		}
		n += tag.RowsAffected()
	}
	return n, nil
}

// PurgeSource gỡ rào chặn và xoá dữ liệu tenant ở cụm nguồn trong cùng một giao dịch (không có khe hở cho ghi lạc).
func (p *PGCells) PurgeSource(ctx context.Context, src Loc, name string, tenantID uuid.UUID, tables []Table) (int64, error) {
	db, err := p.pool(ctx, src)
	if err != nil {
		return 0, err
	}
	var n int64
	err = pgx.BeginFunc(ctx, db, func(tx pgx.Tx) error {
		for _, t := range tables {
			if _, err := tx.Exec(ctx, "DROP TRIGGER IF EXISTS "+pgx.Identifier{name}.Sanitize()+" ON "+t.Qualified()); err != nil {
				return err
			}
			tag, err := tx.Exec(ctx, "DELETE FROM "+t.Qualified()+" WHERE tenant_id = $1", tenantID)
			if err != nil {
				return err
			}
			n += tag.RowsAffected()
		}
		return nil
	})
	return n, err
}

// DropTables xoá các phân vùng đã tạo ở đích (quay lui).
func (p *PGCells) DropTables(ctx context.Context, l Loc, names []string) error {
	db, err := p.pool(ctx, l)
	if err != nil {
		return err
	}
	for _, n := range names {
		if _, err := db.Exec(ctx, "DROP TABLE IF EXISTS "+n); err != nil {
			return err
		}
	}
	return nil
}

// SchemaError: CSDL đích thiếu bảng hoặc khác cấu trúc.
type SchemaError struct{ Table, Reason string }

func (e *SchemaError) Error() string { return "Bảng " + e.Table + " " + e.Reason }
