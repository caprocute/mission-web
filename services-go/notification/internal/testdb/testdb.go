// Package testdb chuẩn bị CSDL PostgreSQL 17 cho kiểm thử tích hợp SVC-12: tạo vai trò mig_notify, áp
// migration db/migration/notify theo thứ tự bằng chính vai trò đó, nạp mẫu chung data/seed/notify, rồi bật
// LOGIN cho svc_notify với mật khẩu ngẫu nhiên. Chỉ dùng trong kiểm thử.
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

	"github.com/jackc/pgx/v5"

	"mission/services-go/notification/internal/store"
)

// DB là CSDL kiểm thử đã áp migration.
type DB struct {
	AdminDSN string
	MigDSN   string
	SvcDSN   string
}

// ModuleRoot trả thư mục services-go/notification.
func ModuleRoot() string {
	_, f, _, _ := runtime.Caller(0)
	return filepath.Clean(filepath.Join(filepath.Dir(f), "..", ".."))
}

// RepoRoot trả thư mục gốc repo.
func RepoRoot() string { return filepath.Clean(filepath.Join(ModuleRoot(), "..", "..")) }

// SeedPath trả đường dẫn tệp mẫu chung.
func SeedPath() string {
	return filepath.Join(RepoRoot(), "data", "seed", "notify", "mau-thong-bao-chung.json")
}

func secret() string {
	b := make([]byte, 18)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

var migName = regexp.MustCompile(`^V(\d+)__.+\.sql$`)

// MigrationFiles trả các tệp V<n>__*.sql theo thứ tự phiên bản.
func MigrationFiles() ([]string, error) {
	dir := filepath.Join(ModuleRoot(), "db", "migration", "notify")
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	type mf struct {
		v int
		p string
	}
	var files []mf
	for _, e := range entries {
		if m := migName.FindStringSubmatch(e.Name()); m != nil {
			v, _ := strconv.Atoi(m[1])
			files = append(files, mf{v, filepath.Join(dir, e.Name())})
		}
	}
	sort.Slice(files, func(i, j int) bool { return files[i].v < files[j].v })
	out := make([]string, len(files))
	for i, f := range files {
		out[i] = f.p
	}
	return out, nil
}

func dsnFor(base, user, pass string) string {
	u, _ := url.Parse(base)
	u.User = url.UserPassword(user, pass)
	return u.String()
}

// Bootstrap tạo mig_notify, áp migration, nạp mẫu chung và bật đăng nhập cho svc_notify trên CSDL dbName.
func Bootstrap(ctx context.Context, adminDSN, dbName string) (*DB, error) {
	db := &DB{AdminDSN: adminDSN}
	admin, err := pgx.Connect(ctx, adminDSN)
	if err != nil {
		return nil, err
	}
	defer admin.Close(ctx)
	migPass := secret()
	if _, err := admin.Exec(ctx, fmt.Sprintf(`
		CREATE ROLE mig_notify LOGIN CREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '%s';
		GRANT CREATE ON DATABASE %s TO mig_notify;
		REVOKE CREATE ON SCHEMA public FROM PUBLIC;`, migPass, pgx.Identifier{dbName}.Sanitize())); err != nil {
		return nil, err
	}
	db.MigDSN = dsnFor(adminDSN, "mig_notify", migPass)

	mig, err := pgx.Connect(ctx, db.MigDSN)
	if err != nil {
		return nil, err
	}
	defer mig.Close(ctx)
	files, err := MigrationFiles()
	if err != nil {
		return nil, err
	}
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil {
			return nil, err
		}
		if _, err := mig.Exec(ctx, string(b)); err != nil {
			return nil, fmt.Errorf("%s: %w", filepath.Base(f), err)
		}
	}
	raw, err := os.ReadFile(SeedPath())
	if err != nil {
		return nil, err
	}
	seed, err := store.ParseSeed(raw)
	if err != nil {
		return nil, err
	}
	if err := store.UpsertGlobalTemplates(ctx, mig, seed); err != nil {
		return nil, err
	}

	svcPass := secret()
	if _, err := admin.Exec(ctx, fmt.Sprintf(`
		ALTER ROLE svc_notify LOGIN PASSWORD '%s';
		GRANT CONNECT ON DATABASE %s TO svc_notify;`, svcPass, pgx.Identifier{dbName}.Sanitize())); err != nil {
		return nil, err
	}
	db.SvcDSN = dsnFor(adminDSN, "svc_notify", svcPass)
	return db, nil
}
