package api_test

import (
	"context"
	"net/url"
	"os"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/tenant-registry/internal/testdb"
)

type pg struct {
	*testdb.DB
	c *tcpg.PostgresContainer
}

// startPG khởi động postgres:17 và bootstrap CSDL registry. Không có Docker thì đặt REGISTRY_TEST_PG_DSN là DSN
// siêu người dùng của một cụm PostgreSQL trống dùng riêng cho lần chạy (vai trò của migration là toàn cụm).
func startPG(ctx context.Context, migrate bool) (*pg, error) {
	if admin := os.Getenv("REGISTRY_TEST_PG_DSN"); admin != "" {
		conn, err := pgx.Connect(ctx, admin)
		if err != nil {
			return nil, err
		}
		_, err = conn.Exec(ctx, "CREATE DATABASE registry")
		conn.Close(ctx)
		if err != nil {
			return nil, err
		}
		u, err := url.Parse(admin)
		if err != nil {
			return nil, err
		}
		u.Path = "/registry"
		db, err := testdb.Bootstrap(ctx, u.String(), migrate)
		if err != nil {
			return nil, err
		}
		return &pg{DB: db}, nil
	}
	c, err := tcpg.Run(ctx, "postgres:17",
		tcpg.WithDatabase("registry"), tcpg.WithUsername("postgres"), tcpg.WithPassword(testdb.RandomSecret()),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(90*time.Second)))
	if err != nil {
		return nil, err
	}
	dsn, err := c.ConnectionString(ctx, "sslmode=disable")
	if err == nil {
		var db *testdb.DB
		if db, err = testdb.Bootstrap(ctx, dsn, migrate); err == nil {
			return &pg{DB: db, c: c}, nil
		}
	}
	_ = c.Terminate(ctx)
	return nil, err
}

func (p *pg) Terminate(ctx context.Context) {
	if p.c != nil {
		_ = p.c.Terminate(ctx)
	}
}
