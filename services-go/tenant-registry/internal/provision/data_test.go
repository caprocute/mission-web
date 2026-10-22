package provision_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"mission/services-go/tenant-registry/internal/provision"
	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
	"mission/services-go/tenant-registry/internal/testdb"
)

// Kiểm thử bước CSDL và khoá (DV-03-04): PostgreSQL 17 thật làm cụm của cell (tài khoản quản trị hẹp chỉ có
// CREATEDB), OpenBao 2.2 thật cho Transit; Job di trú thay bằng bộ di trú giả tạo một bảng (YC-PT-13).

var (
	cellAdminOnce sync.Once
	cellAdminURL  string
	cellAdminErr  error
)

// cellAdmin tạo một lần vai trò tr_cell_admin (LOGIN CREATEDB, không SUPERUSER, không CREATEROLE) và CSDL td_pool
// mang app.tenancy_model = 'pool' như DatabaseBootstrap --tenancy=pool.
func cellAdmin(t *testing.T) string {
	t.Helper()
	cellAdminOnce.Do(func() {
		ctx := context.Background()
		c, err := pgx.Connect(ctx, db.AdminDSN)
		if err != nil {
			cellAdminErr = err
			return
		}
		defer c.Close(ctx)
		pass := testdb.RandomSecret()
		for _, q := range []string{
			`CREATE ROLE tr_cell_admin LOGIN CREATEDB NOSUPERUSER NOCREATEROLE NOREPLICATION PASSWORD ` + quote(pass),
			`CREATE DATABASE td_pool`,
			`ALTER DATABASE td_pool SET app.tenancy_model = 'pool'`,
		} {
			if _, err := c.Exec(ctx, q); err != nil {
				cellAdminErr = err
				return
			}
		}
		u, _ := url.Parse(db.AdminDSN)
		u.User = url.UserPassword("tr_cell_admin", pass)
		u.Path = "/postgres"
		cellAdminURL = u.String()
	})
	if cellAdminErr != nil {
		t.Fatal(cellAdminErr)
	}
	return cellAdminURL
}

func quote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

// fakeMigrator thay Job vt-db-migration: đặt app.tenancy_model bằng tài khoản quản trị cụm (như DatabaseBootstrap
// --tenancy), rồi tạo schema config, một bảng và lịch sử Flyway trong CSDL mới.
type fakeMigrator struct {
	url   string
	calls atomic.Int32
	fail  atomic.Bool
}

func (f *fakeMigrator) Migrate(ctx context.Context, cell, dbName, tenancy string) error {
	f.calls.Add(1)
	if f.fail.Load() {
		return errors.New("Job di trú lỗi")
	}
	su, err := pgx.Connect(ctx, db.AdminDSN)
	if err != nil {
		return err
	}
	_, err = su.Exec(ctx, `ALTER DATABASE `+pgx.Identifier{dbName}.Sanitize()+` SET app.tenancy_model = `+quote(tenancy))
	su.Close(ctx)
	if err != nil {
		return err
	}
	cfg, err := pgx.ParseConfig(f.url)
	if err != nil {
		return err
	}
	cfg.Database = dbName
	c, err := pgx.ConnectConfig(ctx, cfg)
	if err != nil {
		return err
	}
	defer c.Close(ctx)
	_, err = c.Exec(ctx, `CREATE SCHEMA IF NOT EXISTS config;
		CREATE TABLE IF NOT EXISTS config.flyway_schema_history (installed_rank int PRIMARY KEY);
		INSERT INTO config.flyway_schema_history VALUES (1) ON CONFLICT DO NOTHING;
		CREATE TABLE IF NOT EXISTS config.holiday_calendar (id uuid PRIMARY KEY)`)
	return err
}

func (e *env) withData(t *testing.T) *fakeMigrator {
	t.Helper()
	u := cellAdmin(t)
	m := &fakeMigrator{url: u}
	cells := map[string]provision.CellDBAdmin{}
	rows, err := e.pool.Query(context.Background(), `SELECT code FROM registry.cell`)
	if err != nil {
		t.Fatal(err)
	}
	codes, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range codes {
		cells[c] = provision.CellDBAdmin{AdminURL: u}
	}
	e.saga.Databases = &provision.PGCellDatabases{Cells: cells}
	e.saga.Migrator = m
	e.saga.Keys = &provision.Transit{BaseURL: bao.baseURL, Token: func() (string, error) { return bao.token, nil }}
	e.saga.PreSteps = provision.DataSteps()
	return m
}

func dbExists(t *testing.T, name string) (bool, string) {
	t.Helper()
	ctx := context.Background()
	c, err := pgx.Connect(ctx, db.AdminDSN)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(ctx)
	var ok bool
	var model *string
	_ = c.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = $1)`, name).Scan(&ok)
	_ = c.QueryRow(ctx, `SELECT split_part(s, '=', 2) FROM pg_db_role_setting rs JOIN pg_database d ON d.oid = rs.setdatabase,
		unnest(rs.setconfig) s WHERE d.datname = $1 AND s LIKE 'app.tenancy_model=%'`, name).Scan(&model)
	if model == nil {
		return ok, ""
	}
	return ok, *model
}

func keyVersion(t *testing.T, name string) int {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, bao.baseURL+"/v1/transit/keys/"+name, nil)
	req.Header.Set("X-Vault-Token", bao.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return 0
	}
	var k struct {
		Data struct {
			LatestVersion int    `json:"latest_version"`
			Type          string `json:"type"`
			Exportable    bool   `json:"exportable"`
		} `json:"data"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&k)
	if k.Data.Exportable {
		t.Fatalf("khoá %s không được xuất ra ngoài", name)
	}
	return k.Data.LatestVersion
}

// noSecrets: trạng thái saga chỉ mang id và tên, không có chuỗi kết nối, mật khẩu hay token.
func noSecrets(t *testing.T, e *env, jid any) {
	t.Helper()
	var params string
	if err := e.pool.QueryRow(context.Background(), `SELECT params::text FROM registry.job WHERE id = $1`, jid).Scan(&params); err != nil {
		t.Fatal(err)
	}
	u, _ := url.Parse(cellAdminURL)
	pass, _ := u.User.Password()
	for _, bad := range []string{"postgres://", pass, bao.token, "adminUrl"} {
		if strings.Contains(params, bad) {
			t.Fatalf("trạng thái saga lộ bí mật hoặc chuỗi kết nối")
		}
	}
}

func TestDataStepsBridge(t *testing.T) {
	e := newEnv(t)
	m := e.withData(t)
	ctx := context.Background()
	code := uniq("db")
	tid, jid := e.seed(t, code)
	if err := e.saga.Execute(ctx, jid); err != nil {
		t.Fatal(err)
	}
	if s := e.tenant(t, tid).Status; s != tenant.DangCauHinh {
		j, st := e.job(t, jid)
		t.Fatalf("phải tới DANG_CAU_HINH, đang %s; %s %+v", s, j.Status, st)
	}
	_, st := e.job(t, jid)
	if st.Steps[0].Name != provision.StepDatabase || st.Steps[1].Name != provision.StepKeys {
		t.Fatalf("hai bước phần 1 phải chạy trước: %+v", st.Steps[:2])
	}
	name := "td_" + code
	if ok, model := dbExists(t, name); !ok || model != "bridge" {
		t.Fatalf("CSDL %s: %v %q", name, ok, model)
	}
	if m.calls.Load() != 1 {
		t.Fatalf("phải chạy di trú một lần: %d", m.calls.Load())
	}
	b, err := store.LatestTenantCell(ctx, e.pool, tid)
	if err != nil || b.DatabaseName != name || time.Since(b.ValidFrom) > time.Minute {
		t.Fatalf("tenant_cell: %+v %v", b, err)
	}
	x := e.tenant(t, tid)
	if x.KekRef != "transit/keys/t-"+code {
		t.Fatalf("kek_ref: %s", x.KekRef)
	}
	if keyVersion(t, "t-"+code) != 1 || keyVersion(t, provision.BlindIndexKey(code)) != 1 {
		t.Fatal("chưa tạo KEK hoặc khoá chỉ mục mù")
	}
	noSecrets(t, e, jid)

	// Chạy lại bước (tiến trình khởi động lại giữa chừng) không tạo thêm gì và không lỗi.
	r := &provision.PGCellDatabases{Cells: map[string]provision.CellDBAdmin{"X": {AdminURL: cellAdminURL}}}
	if created, err := r.EnsureDatabase(ctx, "X", name, "bridge"); err != nil || created {
		t.Fatalf("EnsureDatabase lặp: %v %v", created, err)
	}
	tr := &provision.Transit{BaseURL: bao.baseURL, Token: func() (string, error) { return bao.token, nil }}
	if created, err := tr.EnsureKey(ctx, "t-"+code, provision.KeyTypeKEK); err != nil || created {
		t.Fatalf("EnsureKey lặp: %v %v", created, err)
	}
	// CSDL đã có dữ liệu thì bù trừ không xoá.
	cfg, _ := pgx.ParseConfig(cellAdminURL)
	cfg.Database = name
	c, err := pgx.ConnectConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	_, err = c.Exec(ctx, `INSERT INTO config.holiday_calendar VALUES (gen_random_uuid())`)
	c.Close(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := r.DropIfEmpty(ctx, "X", name); !errors.Is(err, provision.ErrDatabaseNotEmpty) {
		t.Fatalf("CSDL có dữ liệu không được xoá: %v", err)
	}
}

// TestDataStepsCompensate: lỗi ở bước cuối → bù trừ xoá CSDL rỗng và huỷ khoá chưa dùng, tenant về KHOI_TAO.
func TestDataStepsCompensate(t *testing.T) {
	e := newEnv(t)
	e.withData(t)
	ctx := context.Background()
	e.saga.DNS = &flakyDNS{DNS: e.saga.DNS, failAt: 2}
	code := uniq("dc")
	tid, jid := e.seed(t, code)
	if err := e.saga.Execute(ctx, jid); err != nil {
		t.Fatal(err)
	}
	if s := e.tenant(t, tid).Status; s != tenant.KhoiTao {
		t.Fatalf("phải về KHOI_TAO, đang %s", s)
	}
	_, st := e.job(t, jid)
	if st.Phase != tenant.PhaseCompensated {
		t.Fatalf("bù trừ chưa xong: %+v", st)
	}
	if ok, _ := dbExists(t, "td_"+code); ok {
		t.Fatal("CSDL rỗng phải bị xoá khi bù trừ")
	}
	if keyVersion(t, "t-"+code) != 0 || keyVersion(t, provision.BlindIndexKey(code)) != 0 {
		t.Fatal("khoá chưa dùng phải bị huỷ khi bù trừ")
	}
	noSecrets(t, e, jid)
}

// TestDataStepsMigrationFails: Job di trú lỗi cả ba lần → bù trừ xoá CSDL vừa tạo, chưa có khoá nào.
func TestDataStepsMigrationFails(t *testing.T) {
	e := newEnv(t)
	m := e.withData(t)
	m.fail.Store(true)
	ctx := context.Background()
	code := uniq("dm")
	tid, jid := e.seed(t, code)
	if err := e.saga.Execute(ctx, jid); err != nil {
		t.Fatal(err)
	}
	if s := e.tenant(t, tid).Status; s != tenant.KhoiTao {
		t.Fatalf("phải về KHOI_TAO, đang %s", s)
	}
	j, st := e.job(t, jid)
	if st.FailedStep != provision.StepDatabase || m.calls.Load() != 3 {
		t.Fatalf("bước lỗi %s, số lần di trú %d", st.FailedStep, m.calls.Load())
	}
	if !strings.HasPrefix(*j.Message, "Cấp phát lỗi ở bước khởi tạo cơ sở dữ liệu:") {
		t.Fatalf("MSG-01-07: %s", *j.Message)
	}
	if ok, _ := dbExists(t, "td_"+code); ok {
		t.Fatal("CSDL phải bị xoá khi bù trừ")
	}
	if keyVersion(t, "t-"+code) != 0 {
		t.Fatal("không được tạo khoá khi bước CSDL lỗi")
	}
}

// TestDataStepsPool: pool chỉ kiểm td_pool, dùng KEK chung của cell (không huỷ khi bù trừ), đo thời gian saga.
func TestDataStepsPool(t *testing.T) {
	e := newEnv(t)
	m := e.withData(t)
	ctx := context.Background()
	code := uniq("dp")
	tid, jid := e.seedModel(t, code, "pool")
	start := time.Now()
	if err := e.saga.Execute(ctx, jid); err != nil {
		t.Fatal(err)
	}
	took := time.Since(start)
	t.Logf("saga cấp phát pool: %s", took)
	if took > 5*time.Minute {
		t.Fatalf("saga pool quá 5 phút: %s", took)
	}
	if s := e.tenant(t, tid).Status; s != tenant.DangCauHinh {
		_, st := e.job(t, jid)
		t.Fatalf("phải tới DANG_CAU_HINH, đang %s %+v", s, st)
	}
	if m.calls.Load() != 0 {
		t.Fatal("pool không chạy di trú riêng")
	}
	b, _ := store.LatestTenantCell(ctx, e.pool, tid)
	if b.DatabaseName != "td_pool" {
		t.Fatalf("tenant_cell: %+v", b)
	}
	x := e.tenant(t, tid)
	if !strings.HasPrefix(x.KekRef, "transit/keys/cell-") || keyVersion(t, provision.KeyName(x.KekRef)) != 1 {
		t.Fatalf("KEK chung của cell: %s", x.KekRef)
	}
	if keyVersion(t, provision.BlindIndexKey(code)) != 1 {
		t.Fatal("pool vẫn có khoá chỉ mục mù riêng")
	}

	// Lỗi ở bước sau: KEK chung còn nguyên, khoá chỉ mục mù riêng bị huỷ, td_pool còn nguyên.
	code2 := uniq("dq")
	e.saga.DNS = &flakyDNS{DNS: e.saga.DNS, failAt: 2}
	tid2, jid2 := e.seedModel(t, code2, "pool")
	if err := e.saga.Execute(ctx, jid2); err != nil {
		t.Fatal(err)
	}
	if s := e.tenant(t, tid2).Status; s != tenant.KhoiTao {
		t.Fatalf("phải về KHOI_TAO, đang %s", s)
	}
	if keyVersion(t, provision.KeyName(x.KekRef)) != 1 || keyVersion(t, provision.BlindIndexKey(code2)) != 0 {
		t.Fatal("bù trừ pool sai khoá")
	}
	if ok, _ := dbExists(t, "td_pool"); !ok {
		t.Fatal("không được xoá td_pool")
	}
}

// TestMigrationJob: Job di trú trên API Kubernetes giả: tạo, chờ Complete; lỗi thì xoá Job để lần thử sau tạo lại.
func TestMigrationJob(t *testing.T) {
	var mu sync.Mutex
	var created []map[string]any
	deleted := 0
	failNext := false
	polls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if r.Header.Get("Authorization") != "Bearer k8s-token" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/apis/batch/v1/namespaces/mission-cell/jobs":
			var m map[string]any
			_ = json.NewDecoder(r.Body).Decode(&m)
			created = append(created, m)
			polls = 0
			w.WriteHeader(http.StatusCreated)
		case r.Method == http.MethodGet && r.URL.Path == "/apis/batch/v1/namespaces/mission-cell/jobs/td-migrate-hcm":
			polls++
			switch {
			case polls < 2:
				_, _ = w.Write([]byte(`{"status":{"active":1}}`))
			case failNext:
				_, _ = w.Write([]byte(`{"status":{"failed":1,"conditions":[{"type":"Failed","status":"True"}]}}`))
			default:
				_, _ = w.Write([]byte(`{"status":{"succeeded":1,"conditions":[{"type":"Complete","status":"True"}]}}`))
			}
		case r.Method == http.MethodDelete:
			deleted++
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	j := &provision.MigrationJob{APIServer: srv.URL, Namespace: "mission-cell", Image: "registry.local/vt-db-migration:1",
		SecretPrefix: "td-migrate-", JDBCBase: "jdbc:postgresql://pg-biz-rw.{cell}.svc:5432",
		Token: func() (string, error) { return "k8s-token", nil }, PollEvery: time.Millisecond, Wait: 5 * time.Second}
	ctx := context.Background()
	if err := j.Migrate(ctx, "CELL-HCM", "td_hcm", "bridge"); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	raw, _ := json.Marshal(created[0])
	mu.Unlock()
	s := string(raw)
	for _, want := range []string{`"name":"td-migrate-hcm"`, `jdbc:postgresql://pg-biz-rw.cell-hcm.svc:5432/td_hcm`, `"--layout=td"`,
		`"--tenancy=bridge"`, `"name":"td-migrate-cell-hcm"`, `"readOnlyRootFilesystem":true`, `"backoffLimit":0`} {
		if !strings.Contains(s, want) {
			t.Fatalf("manifest thiếu %s: %s", want, s)
		}
	}
	mu.Lock()
	failNext = true
	mu.Unlock()
	if err := j.Migrate(ctx, "CELL-HCM", "td_hcm", "bridge"); err == nil {
		t.Fatal("Job lỗi phải trả lỗi")
	}
	mu.Lock()
	defer mu.Unlock()
	if deleted != 1 {
		t.Fatalf("Job lỗi phải bị xoá: %d", deleted)
	}
	if n := provision.JobName("td_" + strings.Repeat("a", 70)); len(n) > 63 {
		t.Fatalf("tên Job quá dài: %d", len(n))
	}
}
