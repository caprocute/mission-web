package provision

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// CellDBAdmin là kết nối quản trị hẹp tới cụm PostgreSQL của một cell (bí mật, External Secrets gắn tệp JSON).
type CellDBAdmin struct {
	// AdminURL: chuỗi kết nối tài khoản có CREATEDB (không SUPERUSER, không CREATEROLE); CSDL trong URL là CSDL
	// quản trị (ví dụ postgres).
	AdminURL string `json:"adminUrl"`
}

// PGCellDatabases hiện thực CellDatabases bằng pgx. Mỗi lời gọi mở một kết nối ngắn (thao tác hiếm, không giữ pool).
type PGCellDatabases struct {
	Cells map[string]CellDBAdmin
}

func (p *PGCellDatabases) connect(ctx context.Context, cell, db string) (*pgx.Conn, error) {
	c, ok := p.Cells[cell]
	if !ok || c.AdminURL == "" {
		return nil, fmt.Errorf("provision: chưa có kết nối quản trị CSDL của cell %s", cell)
	}
	cfg, err := pgx.ParseConfig(c.AdminURL)
	if err != nil {
		return nil, errors.New("provision: chuỗi kết nối quản trị CSDL không hợp lệ") // không lộ chuỗi kết nối
	}
	if db != "" {
		cfg.Database = db
	}
	return pgx.ConnectConfig(ctx, cfg)
}

func exists(ctx context.Context, c *pgx.Conn, db string) (bool, error) {
	var ok bool
	err := c.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = $1)`, db).Scan(&ok)
	return ok, err
}

// EnsureDatabase tạo CSDL (tên đã kiểm bằng ValidDatabaseName).
func (p *PGCellDatabases) EnsureDatabase(ctx context.Context, cell, db, _ string) (bool, error) {
	if !ValidDatabaseName(db) {
		return false, errors.New("provision: tên CSDL không hợp lệ")
	}
	c, err := p.connect(ctx, cell, "")
	if err != nil {
		return false, err
	}
	defer c.Close(context.WithoutCancel(ctx))
	ok, err := exists(ctx, c, db)
	if err != nil || ok {
		return false, err
	}
	id := pgx.Identifier{db}.Sanitize()
	if _, err := c.Exec(ctx, `CREATE DATABASE `+id+` ENCODING 'UTF8' TEMPLATE template0`); err != nil {
		var pe interface{ SQLState() string }
		if errors.As(err, &pe) && pe.SQLState() == "42P04" { // tiến trình khác vừa tạo
			return false, nil
		}
		return false, err
	}
	// app.tenancy_model mức CSDL do DatabaseBootstrap --tenancy (tài khoản quản trị cụm) đặt trong Job di trú:
	// tài khoản hẹp này không có quyền SET tham số tự định nghĩa.
	return true, nil
}

// DropIfEmpty xoá CSDL khi mọi bảng thường (ngoài flyway_schema_history) đều không có dòng.
func (p *PGCellDatabases) DropIfEmpty(ctx context.Context, cell, db string) error {
	if !ValidDatabaseName(db) || db == "td_pool" {
		return errors.New("provision: không xoá CSDL này")
	}
	c, err := p.connect(ctx, cell, "")
	if err != nil {
		return err
	}
	defer c.Close(context.WithoutCancel(ctx))
	ok, err := exists(ctx, c, db)
	if err != nil || !ok {
		return err
	}
	empty, err := p.empty(ctx, cell, db)
	if err != nil {
		return err
	}
	if !empty {
		return ErrDatabaseNotEmpty
	}
	_, err = c.Exec(ctx, `DROP DATABASE IF EXISTS `+pgx.Identifier{db}.Sanitize()+` WITH (FORCE)`)
	return err
}

func (p *PGCellDatabases) empty(ctx context.Context, cell, db string) (bool, error) {
	c, err := p.connect(ctx, cell, db)
	if err != nil {
		return false, err
	}
	defer c.Close(context.WithoutCancel(ctx))
	rows, err := c.Query(ctx, `SELECT n.nspname, cl.relname FROM pg_class cl JOIN pg_namespace n ON n.oid = cl.relnamespace
		WHERE cl.relkind = 'r' AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'partman')
		AND n.nspname NOT LIKE 'pg_toast%' AND cl.relname <> 'flyway_schema_history'`)
	if err != nil {
		return false, err
	}
	tables, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (pgx.Identifier, error) {
		var s, n string
		err := r.Scan(&s, &n)
		return pgx.Identifier{s, n}, err
	})
	if err != nil {
		return false, err
	}
	for _, t := range tables {
		var has bool
		// Tài khoản quản trị không vượt RLS; bảng có RLS FORCE đọc rỗng nên kiểm bằng thống kê nếu không đọc được.
		if err := c.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM `+t.Sanitize()+`)`).Scan(&has); err != nil {
			var pe interface{ SQLState() string }
			if errors.As(err, &pe) && pe.SQLState() == "42501" {
				if err := c.QueryRow(ctx, `SELECT coalesce(n_live_tup, 0) > 0 FROM pg_stat_user_tables
					WHERE schemaname = $1 AND relname = $2`, t[0], t[1]).Scan(&has); err != nil && !errors.Is(err, pgx.ErrNoRows) {
					return false, err
				}
			} else {
				return false, err
			}
		}
		if has {
			return false, nil
		}
	}
	return true, nil
}

// SharedReady kiểm CSDL pool đã có và đã đặt app.tenancy_model = 'pool' (DatabaseBootstrap --tenancy=pool).
func (p *PGCellDatabases) SharedReady(ctx context.Context, cell, db string) error {
	c, err := p.connect(ctx, cell, "")
	if err != nil {
		return err
	}
	defer c.Close(context.WithoutCancel(ctx))
	var model string
	err = c.QueryRow(ctx, `SELECT split_part(s.cfg, '=', 2) FROM pg_db_role_setting rs JOIN pg_database d ON d.oid = rs.setdatabase,
		unnest(rs.setconfig) AS s(cfg) WHERE d.datname = $1 AND rs.setrole = 0 AND s.cfg LIKE 'app.tenancy_model=%'`, db).Scan(&model)
	if errors.Is(err, pgx.ErrNoRows) {
		return fmt.Errorf("provision: CSDL %s chưa có hoặc chưa đặt app.tenancy_model", db)
	}
	if err != nil {
		return err
	}
	if model != "pool" {
		return fmt.Errorf("provision: CSDL %s mang app.tenancy_model = %s", db, model)
	}
	return nil
}

// ---------------------------------------------------------------- OpenBao Transit

// Transit hiện thực Keys qua API Transit của OpenBao (/v1/<mount>/keys/<tên>).
type Transit struct {
	BaseURL string
	Mount   string // mặc định transit
	// Token trả token OpenBao (đọc lại mỗi lần: token Kubernetes auth có hạn).
	Token func() (string, error)
	HTTP  *http.Client
}

const sysBao = "openbao"

func (t *Transit) req(ctx context.Context, method, name, suffix string, body any, ok ...int) (*result, error) {
	tok := ""
	if t.Token != nil {
		v, err := t.Token()
		if err != nil {
			return nil, err
		}
		tok = v
	}
	m := t.Mount
	if m == "" {
		m = "transit"
	}
	u := strings.TrimRight(t.BaseURL, "/") + "/v1/" + m + "/keys/" + url.PathEscape(name) + suffix
	return do(ctx, t.HTTP, call{system: sysBao, method: method, url: u, body: body, ok: ok,
		headers: map[string]string{"X-Vault-Token": tok}})
}

func (t *Transit) latest(ctx context.Context, name string) (int, bool, error) {
	r, err := t.req(ctx, http.MethodGet, name, "", nil, http.StatusNotFound)
	if err != nil {
		return 0, false, err
	}
	if r.status == http.StatusNotFound {
		return 0, false, nil
	}
	var k struct {
		Data struct {
			LatestVersion int `json:"latest_version"`
		} `json:"data"`
	}
	if err := json.Unmarshal(r.body, &k); err != nil {
		return 0, false, err
	}
	return k.Data.LatestVersion, true, nil
}

// EnsureKey tạo khoá không xuất được (exportable = false, không cho sao lưu dạng rõ).
func (t *Transit) EnsureKey(ctx context.Context, name, typ string) (bool, error) {
	if _, ok, err := t.latest(ctx, name); err != nil || ok {
		return false, err
	}
	body := map[string]any{"type": typ, "exportable": false, "allow_plaintext_backup": false}
	if typ == KeyTypeHMAC {
		body["key_size"] = 32 // HMAC-SHA-256, khoá 256 bit
	}
	_, err := t.req(ctx, http.MethodPost, name, "", body)
	return err == nil, err
}

// DeleteUnusedKey cho phép xoá rồi xoá khoá còn ở phiên bản 1; khoá không còn thì coi như đã huỷ.
func (t *Transit) DeleteUnusedKey(ctx context.Context, name string) error {
	v, ok, err := t.latest(ctx, name)
	if err != nil || !ok {
		return err
	}
	if v > 1 {
		return ErrKeyInUse
	}
	if _, err := t.req(ctx, http.MethodPost, name, "/config", map[string]any{"deletion_allowed": true}); err != nil {
		return err
	}
	_, err = t.req(ctx, http.MethodDelete, name, "", nil, http.StatusNotFound)
	return err
}

// ---------------------------------------------------------------- Job di trú trên Kubernetes

// MigrationJob hiện thực Migrator bằng một Kubernetes Job chạy ảnh vt-db-migration: DatabaseBootstrap
// --layout=td rồi MigrationRunner mọi schema (NEN-030, NEN-031). Mật khẩu quản trị cụm và mig_<schema> nằm trong
// Secret của cell (External Secrets từ OpenBao), Job chỉ tham chiếu tên Secret.
type MigrationJob struct {
	APIServer string
	Namespace string
	Image     string
	// SecretPrefix: Secret của cell tên <SecretPrefix><cell chữ thường> (biến PG_ADMIN_*, MIG_<SCHEMA>_PASSWORD).
	SecretPrefix string
	// JDBCBase: mẫu JDBC theo cell, {cell} được thay bằng mã cell chữ thường, ví dụ
	// jdbc:postgresql://pg-biz-rw.{cell}.svc:5432
	JDBCBase  string
	Token     func() (string, error)
	HTTP      *http.Client
	PollEvery time.Duration
	Wait      time.Duration // thời gian chờ Job xong trong một lần thử
}

const sysK8sJob = "kubernetes"

func (m *MigrationJob) req(ctx context.Context, method, path string, body any, ok ...int) (*result, error) {
	tok := ""
	if m.Token != nil {
		v, err := m.Token()
		if err != nil {
			return nil, err
		}
		tok = v
	}
	return do(ctx, m.HTTP, call{system: sysK8sJob, method: method, url: strings.TrimRight(m.APIServer, "/") + path, body: body, token: tok, ok: ok})
}

// JobName trả tên Job di trú của một CSDL (≤ 63 ký tự, chữ thường, gạch nối).
func JobName(db string) string {
	n := "td-migrate-" + strings.ReplaceAll(strings.TrimPrefix(db, "td_"), "_", "-")
	if len(n) > 63 {
		n = strings.TrimRight(n[:63], "-")
	}
	return n
}

// Manifest dựng Job di trú (không chứa bí mật).
func (m *MigrationJob) Manifest(cell, db, tenancy string) map[string]any {
	lc := strings.ToLower(cell)
	jdbc := strings.ReplaceAll(m.JDBCBase, "{cell}", lc) + "/" + db
	sec := m.SecretPrefix + lc
	ro, nonRoot := true, true
	uid := int64(65532)
	return map[string]any{
		"apiVersion": "batch/v1", "kind": "Job",
		"metadata": map[string]any{"name": JobName(db), "namespace": m.Namespace,
			"labels": map[string]string{"app.kubernetes.io/managed-by": "tenant-registry", "mission.vn/cell": lc}},
		"spec": map[string]any{"backoffLimit": 0, "ttlSecondsAfterFinished": 86400, "activeDeadlineSeconds": 1800,
			"template": map[string]any{
				"metadata": map[string]any{"labels": map[string]string{"app.kubernetes.io/name": "td-migrate"}},
				"spec": map[string]any{"restartPolicy": "Never", "automountServiceAccountToken": false,
					"securityContext": map[string]any{"runAsNonRoot": nonRoot, "runAsUser": uid, "seccompProfile": map[string]string{"type": "RuntimeDefault"}},
					"containers": []any{map[string]any{
						"name": "migrate", "image": m.Image,
						"args":    []string{"--db=" + jdbc, "--database=" + db, "--layout=td", "--tenancy=" + tenancy, "--schemas=all"},
						"envFrom": []any{map[string]any{"secretRef": map[string]string{"name": sec}}},
						"securityContext": map[string]any{"readOnlyRootFilesystem": ro, "allowPrivilegeEscalation": false,
							"capabilities": map[string]any{"drop": []string{"ALL"}}},
						"resources": map[string]any{"requests": map[string]string{"cpu": "250m", "memory": "512Mi"},
							"limits": map[string]string{"memory": "1Gi"}},
					}},
				}}}}
}

func (m *MigrationJob) jobPath(name string) string {
	p := "/apis/batch/v1/namespaces/" + url.PathEscape(m.Namespace) + "/jobs"
	if name != "" {
		p += "/" + url.PathEscape(name)
	}
	return p
}

// Migrate tạo Job (đã có thì theo dõi Job đó) và chờ xong. Job lỗi bị xoá để lần thử sau tạo lại.
func (m *MigrationJob) Migrate(ctx context.Context, cell, db, tenancy string) error {
	name := JobName(db)
	if _, err := m.req(ctx, http.MethodPost, m.jobPath(""), m.Manifest(cell, db, tenancy), http.StatusConflict); err != nil {
		return err
	}
	wait := m.Wait
	if wait <= 0 {
		wait = 20 * time.Minute
	}
	every := m.PollEvery
	if every <= 0 {
		every = 3 * time.Second
	}
	deadline := time.Now().Add(wait)
	for {
		r, err := m.req(ctx, http.MethodGet, m.jobPath(name), nil)
		if err != nil {
			return err
		}
		var j struct {
			Status struct {
				Succeeded  int `json:"succeeded"`
				Failed     int `json:"failed"`
				Conditions []struct {
					Type   string `json:"type"`
					Status string `json:"status"`
				} `json:"conditions"`
			} `json:"status"`
		}
		if err := json.Unmarshal(r.body, &j); err != nil {
			return err
		}
		failed := j.Status.Failed > 0
		for _, c := range j.Status.Conditions {
			if c.Status == "True" && c.Type == "Complete" {
				return nil
			}
			if c.Status == "True" && c.Type == "Failed" {
				failed = true
			}
		}
		if j.Status.Succeeded > 0 {
			return nil
		}
		if failed {
			_, _ = m.req(ctx, http.MethodDelete, m.jobPath(name)+"?propagationPolicy=Background", nil, http.StatusNotFound)
			return fmt.Errorf("provision: Job di trú %s lỗi", name)
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("provision: Job di trú %s chưa xong sau %s", name, wait)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(every):
		}
	}
}
