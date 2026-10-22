package move_test

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/network"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/tenant-registry/internal/testdb"
)

// Kiểm thử tích hợp DV-03-07 trên ba cụm PostgreSQL 17 thật (Testcontainers): CSDL registry toàn cục và hai cụm
// cell (nguồn, đích) bật wal_level=logical, nối chung một mạng Docker để subscription ở đích kết nối được nguồn.

var (
	reg      *testdb.DB
	src, dst *cellPG
)

type cellPG struct {
	c        *tcpg.PostgresContainer
	alias    string
	password string
	adminURL string // từ máy chạy kiểm thử
}

// peer là conninfo cụm khác dùng để kết nối tới cụm này trong mạng Docker.
func (c *cellPG) peer() string {
	return fmt.Sprintf("host=%s port=5432 user=postgres password=%s sslmode=disable", c.alias, c.password)
}

func logical() testcontainers.CustomizeRequestOption {
	return func(req *testcontainers.GenericContainerRequest) error {
		req.Cmd = append(req.Cmd, "-c", "wal_level=logical", "-c", "max_wal_senders=20", "-c", "max_replication_slots=20")
		return nil
	}
}

func startCell(ctx context.Context, nw *testcontainers.DockerNetwork, alias string) (*cellPG, error) {
	pw := testdb.RandomSecret()
	c, err := tcpg.Run(ctx, "postgres:17", tcpg.WithDatabase("td_pool"), tcpg.WithUsername("postgres"), tcpg.WithPassword(pw),
		logical(), network.WithNetwork([]string{alias}, nw),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").WithOccurrence(2).WithStartupTimeout(90*time.Second)))
	if err != nil {
		return nil, err
	}
	dsn, err := c.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		return nil, err
	}
	return &cellPG{c: c, alias: alias, password: pw, adminURL: dsn}, nil
}

func TestMain(m *testing.M) {
	ctx := context.Background()
	code := 1
	defer func() { os.Exit(code) }()
	rc, err := tcpg.Run(ctx, "postgres:17", tcpg.WithDatabase("registry"), tcpg.WithUsername("postgres"), tcpg.WithPassword(testdb.RandomSecret()),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").WithOccurrence(2).WithStartupTimeout(90*time.Second)))
	if err != nil {
		fmt.Fprintln(os.Stderr, "registry:", err)
		return
	}
	defer func() { _ = rc.Terminate(ctx) }()
	dsn, _ := rc.ConnectionString(ctx, "sslmode=disable")
	if reg, err = testdb.Bootstrap(ctx, dsn, true); err != nil {
		fmt.Fprintln(os.Stderr, "registry bootstrap:", err)
		return
	}
	nw, err := network.New(ctx)
	if err != nil {
		fmt.Fprintln(os.Stderr, "network:", err)
		return
	}
	defer func() { _ = nw.Remove(ctx) }()
	if src, err = startCell(ctx, nw, "pg-cell-src"); err != nil {
		fmt.Fprintln(os.Stderr, "cell nguồn:", err)
		return
	}
	defer func() { _ = src.c.Terminate(ctx) }()
	if dst, err = startCell(ctx, nw, "pg-cell-dst"); err != nil {
		fmt.Fprintln(os.Stderr, "cell đích:", err)
		return
	}
	defer func() { _ = dst.c.Terminate(ctx) }()
	for _, c := range []*cellPG{src, dst} {
		if err := applySchema(ctx, c.adminURL); err != nil {
			fmt.Fprintln(os.Stderr, "schema cell:", err)
			return
		}
	}
	code = m.Run()
}

// applySchema dựng schema mẫu của CSDL dữ liệu tenant pool (thay Flyway của các module trong cell): bảng thường
// có PK không chứa tenant_id, bảng phân vùng LIST theo tenant, outbox (không được sao chép), bảng không có
// tenant_id.
func applySchema(ctx context.Context, url string) error {
	conn, err := pgx.Connect(ctx, url)
	if err != nil {
		return err
	}
	defer conn.Close(ctx)
	_, err = conn.Exec(ctx, `
		CREATE SCHEMA okr;
		CREATE TABLE okr.objective (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, title text NOT NULL,
		  progress numeric(5,2) NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now(), tags text[] NOT NULL DEFAULT '{}');
		CREATE INDEX ix_objective__tenant ON okr.objective (tenant_id);
		CREATE TABLE okr.outbox_event (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, payload text NOT NULL);
		CREATE SCHEMA task;
		CREATE TABLE task.task_progress (tenant_id uuid NOT NULL, id uuid NOT NULL, note text, amount numeric(12,2),
		  recorded_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (tenant_id, id)) PARTITION BY LIST (tenant_id);
		CREATE TABLE public.setting (k text PRIMARY KEY, v text);`)
	return err
}
