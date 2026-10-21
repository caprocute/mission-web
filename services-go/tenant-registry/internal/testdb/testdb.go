// Package testdb chuẩn bị CSDL PostgreSQL 17 (container do Testcontainers dựng trong tệp _test.go) cho kiểm
// thử tích hợp: tạo vai trò mig_registry
// (thay bước bootstrap NEN-031), áp các migration Flyway của db/migration/registry theo thứ tự phiên bản
// bằng chính vai trò đó, nạp seed dev, rồi bật LOGIN cho svc_registry, ro_registry với mật khẩu sinh ngẫu
// nhiên lúc chạy. Chỉ dùng trong kiểm thử.
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// DB là CSDL kiểm thử đã áp migration.
type DB struct {
	AdminDSN string // siêu người dùng của container
	MigDSN   string // mig_registry
	SvcDSN   string // svc_registry
	RoDSN    string // ro_registry
}

// ModuleRoot trả thư mục gốc mô-đun services-go/tenant-registry.
func ModuleRoot() string {
	_, f, _, _ := runtime.Caller(0)
	return filepath.Clean(filepath.Join(filepath.Dir(f), "..", ".."))
}

// RepoRoot trả thư mục gốc repo.
func RepoRoot() string { return filepath.Clean(filepath.Join(ModuleRoot(), "..", "..")) }

func randomSecret() string {
	b := make([]byte, 18)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

var migName = regexp.MustCompile(`^V(\d+)__.+\.sql$`)

// MigrationFiles trả các tệp V<n>__*.sql theo thứ tự phiên bản.
func MigrationFiles() ([]string, error) {
	dir := filepath.Join(ModuleRoot(), "db", "migration", "registry")
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	type mf struct {
		v    int
		path string
	}
	var files []mf
	for _, e := range entries {
		m := migName.FindStringSubmatch(e.Name())
		if m == nil {
			continue
		}
		v, _ := strconv.Atoi(m[1])
		files = append(files, mf{v, filepath.Join(dir, e.Name())})
	}
	sort.Slice(files, func(i, j int) bool { return files[i].v < files[j].v })
	out := make([]string, len(files))
	for i, f := range files {
		out[i] = f.path
	}
	return out, nil
}

// ApplySQLFile chạy nguyên một tệp SQL (giao thức đơn giản, nhiều câu lệnh).
func ApplySQLFile(ctx context.Context, conn *pgx.Conn, path string) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if _, err := conn.Exec(ctx, string(b)); err != nil {
		return fmt.Errorf("%s: %w", filepath.Base(path), err)
	}
	return nil
}

func dsnFor(base, user, pass string) string {
	u, _ := url.Parse(base)
	u.User = url.UserPassword(user, pass)
	return u.String()
}

// Bootstrap nhận chuỗi kết nối siêu người dùng của một CSDL registry trống (container do tệp _test.go của
// từng gói khởi động, để gói này không phụ thuộc Testcontainers và Docker SDK ngoài mã kiểm thử), tạo
// mig_registry và (nếu migrate) áp migration, seed, bật đăng nhập cho svc_/ro_registry.
func Bootstrap(ctx context.Context, adminDSN string, migrate bool) (*DB, error) {
	db := &DB{AdminDSN: adminDSN}
	if err := db.bootstrap(ctx, migrate); err != nil {
		return nil, err
	}
	return db, nil
}

func (db *DB) bootstrap(ctx context.Context, migrate bool) error {
	conn, err := pgx.Connect(ctx, db.AdminDSN)
	if err != nil {
		return err
	}
	defer conn.Close(ctx)
	migPass := randomSecret()
	// Thay bước bootstrap NEN-031: mig_registry sở hữu đối tượng, có CREATEROLE để V2 tạo svc_/ro_.
	if _, err := conn.Exec(ctx, fmt.Sprintf(`CREATE ROLE mig_registry LOGIN CREATEROLE NOINHERIT PASSWORD '%s';
		GRANT CREATE ON DATABASE registry TO mig_registry;
		REVOKE CREATE ON SCHEMA public FROM PUBLIC;`, migPass)); err != nil {
		return err
	}
	db.MigDSN = dsnFor(db.AdminDSN, "mig_registry", migPass)
	if !migrate {
		return nil
	}
	if err := db.Migrate(ctx); err != nil {
		return err
	}
	return db.EnableAppLogins(ctx)
}

// Migrate áp mọi migration bằng mig_registry rồi nạp seed dev.
func (db *DB) Migrate(ctx context.Context) error {
	mig, err := pgx.Connect(ctx, db.MigDSN)
	if err != nil {
		return err
	}
	defer mig.Close(ctx)
	files, err := MigrationFiles()
	if err != nil {
		return err
	}
	for _, f := range files {
		if err := ApplySQLFile(ctx, mig, f); err != nil {
			return err
		}
	}
	return ApplySQLFile(ctx, mig, filepath.Join(ModuleRoot(), "db", "seed", "dev", "R__seed_cells_dev.sql"))
}

// EnableAppLogins bật LOGIN cho svc_registry, ro_registry với mật khẩu ngẫu nhiên.
func (db *DB) EnableAppLogins(ctx context.Context) error {
	conn, err := pgx.Connect(ctx, db.AdminDSN)
	if err != nil {
		return err
	}
	defer conn.Close(ctx)
	svcPass, roPass := randomSecret(), randomSecret()
	if _, err := conn.Exec(ctx, fmt.Sprintf(`ALTER ROLE svc_registry LOGIN PASSWORD '%s'; ALTER ROLE ro_registry LOGIN PASSWORD '%s';
		GRANT CONNECT ON DATABASE registry TO svc_registry, ro_registry;`, svcPass, roPass)); err != nil {
		return err
	}
	db.SvcDSN = dsnFor(db.AdminDSN, "svc_registry", svcPass)
	db.RoDSN = dsnFor(db.AdminDSN, "ro_registry", roPass)
	return nil
}

// Pool mở pool tới dsn.
func Pool(ctx context.Context, t testing.TB, dsn string) *pgxpool.Pool {
	t.Helper()
	p, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.Close)
	return p
}

// RandomSecret sinh mật khẩu kiểm thử ngẫu nhiên.
func RandomSecret() string { return randomSecret() }
