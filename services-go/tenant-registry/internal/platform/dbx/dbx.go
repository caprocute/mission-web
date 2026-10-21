// Package dbx bọc giao dịch pgx. Schema registry không dùng RLS (TL-03: registry định nghĩa tenant) nên không
// đặt app.tenant_id; khi NEN-017 có vtplatform/db.InTx thì thay hàm này.
package dbx

import (
	"context"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// InTx chạy fn trong một giao dịch READ COMMITTED; fn trả lỗi thì rollback.
func InTx(ctx context.Context, pool *pgxpool.Pool, fn func(pgx.Tx) error) error {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
