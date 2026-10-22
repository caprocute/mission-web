// Package bulk tách đợt thông báo hàng loạt cuối kỳ thành lô (DV-12-04, TL-01 "San phẳng đỉnh bằng hàng đợi"). Mỗi
// vòng, bộ mở đợt nhận đợt cũ nhất chưa xong của mỗi tenant và tách đúng một lô Chunk người nhận cho mỗi đợt, nên
// năng lực được chia đều giữa các tenant (round-robin): đợt 1.000 người của tenant nhỏ xong ngay vòng đầu dù tenant
// lớn có đợt 100.000 người. Mỗi lô ghi thông báo trong ứng dụng và xếp hàng kênh ngoài mang batch_id trong một giao
// dịch tenant, cùng cập nhật tiến độ đợt; tách lại sau sự cố không sinh trùng nhờ mã thông báo tất định.
package bulk

import (
	"context"
	"encoding/json"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/notification/internal/consume"
	"mission/services-go/notification/internal/store"
	vdb "vn.viettel.okrkpi/services-go/vtplatform/db"
)

// Expander tách đợt.
type Expander struct {
	Pool   *pgxpool.Pool
	Writer *store.Writer
	Chunk  int
	Poll   time.Duration
	Log    *slog.Logger
	// Created nhận thông báo vừa tạo để đẩy qua SSE (tuỳ chọn).
	Created func(ctx context.Context, tenantID string, items []consume.Created)
}

func (x *Expander) defaults() {
	if x.Chunk <= 0 {
		x.Chunk = 1000
	}
	if x.Poll <= 0 {
		x.Poll = time.Second
	}
	if x.Log == nil {
		x.Log = slog.Default()
	}
}

// Run chạy tới khi ctx dừng.
func (x *Expander) Run(ctx context.Context) {
	x.defaults()
	for ctx.Err() == nil {
		n, err := x.Round(ctx)
		if err != nil && ctx.Err() == nil {
			x.Log.Error("tách đợt hàng loạt thất bại", "err", err.Error())
		}
		if n == 0 {
			select {
			case <-ctx.Done():
			case <-time.After(x.Poll):
			}
		}
	}
}

// Round tách một lô cho đợt cũ nhất của mỗi tenant; trả số lô đã tách.
func (x *Expander) Round(ctx context.Context) (int, error) {
	x.defaults()
	rows, err := x.Pool.Query(ctx, `SELECT tenant_id::text, id::text FROM notify.fn_claim_bulk_jobs(1000, '2 minutes')`)
	if err != nil {
		return 0, err
	}
	type jk struct{ tenant, id string }
	jobs, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (jk, error) {
		var k jk
		return k, r.Scan(&k.tenant, &k.id)
	})
	if err != nil {
		return 0, err
	}
	for _, j := range jobs {
		if err := x.chunk(ctx, j.tenant, j.id); err != nil {
			return 0, err
		}
	}
	return len(jobs), nil
}

func (x *Expander) chunk(ctx context.Context, tenant, id string) error {
	var made []consume.Created
	err := pgx.BeginFunc(ctx, x.Pool, func(tx pgx.Tx) error {
		made = made[:0]
		if _, err := tx.Exec(ctx, vdb.SetLocalTenantSQL, tenant); err != nil {
			return err
		}
		var j store.BulkJob
		var raw []byte
		var done, total int
		err := tx.QueryRow(ctx, `
			SELECT template_code, params, deep_link, priority, created_at, done, total,
			       recipients[done + 1 : done + $3]::text[]
			  FROM notify.bulk_job WHERE tenant_id = $1 AND id = $2 AND finished_at IS NULL FOR UPDATE`,
			tenant, id, x.Chunk).Scan(&j.TemplateCode, &raw, &j.DeepLink, &j.Priority, &j.CreatedAt, &done, &total,
			&j.Recipients)
		if err != nil {
			return err
		}
		if err := json.Unmarshal(raw, &j.Params); err != nil {
			return err
		}
		wtx := store.WrapTx(tx)
		for _, r := range j.Recipients {
			ins, err := x.Writer.Insert(ctx, wtx, store.NewNotification{TenantID: tenant, RecipientID: r,
				TemplateCode: j.TemplateCode, Params: j.Params, DeepLink: j.DeepLink, Priority: j.Priority,
				SourceEventID: id, CreatedAt: j.CreatedAt, BatchID: &id})
			if err != nil {
				return err
			}
			if ins {
				made = append(made, consume.Created{RecipientID: r, NotificationID: store.NotificationID(id, r).String()})
			}
		}
		done += len(j.Recipients)
		_, err = tx.Exec(ctx, `UPDATE notify.bulk_job SET done = $3, locked_until = NULL,
			finished_at = CASE WHEN $3 >= total THEN now() END WHERE tenant_id = $1 AND id = $2`, tenant, id, done)
		if err == nil && done >= total {
			x.Log.Info("đã tách xong đợt hàng loạt", "tenant", tenant, "batch", id, "total", total)
		}
		return err
	})
	if err != nil {
		return err
	}
	if len(made) > 0 && x.Created != nil {
		x.Created(ctx, tenant, made)
	}
	return nil
}
