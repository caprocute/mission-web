package store_test

import (
	"context"
	"time"

	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/ledger/internal/testdb"
)

type pg struct {
	*testdb.DB
	c *tcpg.PostgresContainer
}

// startPG khởi động postgres:17 và bootstrap CSDL ledger.
func startPG(ctx context.Context, migrate bool) (*pg, error) {
	c, err := tcpg.Run(
		ctx,
		"postgres:17",
		tcpg.WithDatabase("ledger"),
		tcpg.WithUsername("postgres"),
		tcpg.WithPassword(testdb.RandomSecret()),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(90*time.Second),
		),
	)
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
	_ = p.c.Terminate(ctx)
}
