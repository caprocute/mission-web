// Package testdb chuẩn bị CSDL PostgreSQL 17 (container do Testcontainers dựng trong tệp _test.go)
// cho kiểm thử tích hợp của SVC-09 Ledger Service: tạo vai trò mig_ledger, áp các migration Flyway
// trong db/migration/ledger theo thứ tự phiên bản bằng chính vai trò đó, rồi bật LOGIN cho
// svc_ledger, ro_ledger, cdc_ledger với mật khẩu ngẫu nhiên. Chỉ dùng trong kiểm thử.
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

// DB là CSDL kiểm thử đã áp migration của ledger.
type DB struct {
	AdminDSN string // siêu người dùng của container
	MigDSN   string // mig_ledger
	SvcDSN   string // svc_ledger
	RoDSN    string // ro_ledger
	CdcDSN   string // cdc_ledger
}

// ModuleRoot trả thư mục gốc mô-đun services-go/ledger.
func ModuleRoot() string {
	_, f, _, _ := runtime.Caller(0)
	return filepath.Clean(filepath.Join(filepath.Dir(f), "..", ".."))
}

// RepoRoot trả thư mục gốc repo.
func RepoRoot() string {
	return filepath.Clean(filepath.Join(ModuleRoot(), "..", ".."))
}

func randomSecret() string {
	b := make([]byte, 18)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

var migName = regexp.MustCompile(`^V(\d+)__.+\.sql$`)

// MigrationFiles trả các tệp V<n>__*.sql trong db/migration/ledger theo thứ tự phiên bản.
func MigrationFiles() ([]string, error) {
	dir := filepath.Join(ModuleRoot(), "db", "migration", "ledger")
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

// ApplySQLFile chạy nguyên một tệp SQL trên kết nối đơn.
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

// Bootstrap nhận chuỗi kết nối siêu người dùng của một CSDL ledger trống, tạo mig_ledger
// và (nếu migrate=true) áp migration cùng bật đăng nhập cho svc_ledger, ro_ledger, cdc_ledger.
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
	if _, err := conn.Exec(ctx, fmt.Sprintf(`
		CREATE ROLE mig_ledger LOGIN CREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '%s';
		GRANT CREATE ON DATABASE ledger TO mig_ledger;
		REVOKE CREATE ON SCHEMA public FROM PUBLIC;
	`, migPass)); err != nil {
		return err
	}
	db.MigDSN = dsnFor(db.AdminDSN, "mig_ledger", migPass)
	if !migrate {
		return nil
	}
	if err := db.Migrate(ctx); err != nil {
		return err
	}
	return db.EnableAppLogins(ctx)
}

// Migrate áp mọi migration bằng vai trò mig_ledger.
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
	return nil
}

// EnableAppLogins bật LOGIN cho svc_ledger, ro_ledger, cdc_ledger với mật khẩu ngẫu nhiên.
func (db *DB) EnableAppLogins(ctx context.Context) error {
	conn, err := pgx.Connect(ctx, db.AdminDSN)
	if err != nil {
		return err
	}
	defer conn.Close(ctx)

	svcPass := randomSecret()
	roPass := randomSecret()
	cdcPass := randomSecret()

	if _, err := conn.Exec(ctx, fmt.Sprintf(`
		ALTER ROLE svc_ledger LOGIN PASSWORD '%s';
		ALTER ROLE ro_ledger LOGIN PASSWORD '%s';
		ALTER ROLE cdc_ledger LOGIN PASSWORD '%s';
		GRANT CONNECT ON DATABASE ledger TO svc_ledger, ro_ledger, cdc_ledger;
	`, svcPass, roPass, cdcPass)); err != nil {
		return err
	}
	db.SvcDSN = dsnFor(db.AdminDSN, "svc_ledger", svcPass)
	db.RoDSN = dsnFor(db.AdminDSN, "ro_ledger", roPass)
	db.CdcDSN = dsnFor(db.AdminDSN, "cdc_ledger", cdcPass)
	return nil
}

// Pool mở pgxpool.Pool tới dsn và tự đóng khi test kết thúc.
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
