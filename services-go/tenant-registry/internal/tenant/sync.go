package tenant

import (
	"context"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/platform/idem"
	"mission/services-go/tenant-registry/internal/store"
)

// CellAccountSource đọc số tài khoản theo tenant do một cell báo về. Giao thức báo cáo giữa cell và vùng
// toàn cục chưa có trong TL (suy luận: mỗi cell công bố số đếm theo tenant; hiện thực cụ thể chờ luồng cell).
type CellAccountSource interface {
	AccountCounts(ctx context.Context, cell store.Cell) (map[uuid.UUID]int, error)
}

// SyncResult là kết quả một lượt đồng bộ.
type SyncResult struct {
	Cells, Updated int
	Failed         []string // mã cell không đọc được
	Skipped        bool     // bản sao khác đang giữ khoá
}

// syncLockKey là khoá tư vấn bảo đảm chỉ một bản sao chạy đồng bộ tại một thời điểm.
const syncLockKey = 0x5f03_0001

// SyncAccounts cập nhật registry.tenant.account_count từ các cell (không tăng row_version: số đếm vận hành,
// không phải sửa nghiệp vụ; tránh làm lệch If-Match của người đang soạn).
func (s *Service) SyncAccounts(ctx context.Context, pool *pgxpool.Pool, src CellAccountSource) (SyncResult, error) {
	var res SyncResult
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return res, err
	}
	defer conn.Release()
	var got bool
	if err := conn.QueryRow(ctx, `SELECT pg_try_advisory_lock($1)`, syncLockKey).Scan(&got); err != nil {
		return res, err
	}
	if !got {
		res.Skipped = true
		return res, nil
	}
	defer func() { _, _ = conn.Exec(context.WithoutCancel(ctx), `SELECT pg_advisory_unlock($1)`, syncLockKey) }()
	cells, err := store.ListCells(ctx, conn, store.CellFilter{})
	if err != nil {
		return res, err
	}
	for _, c := range cells {
		if c.Status == "NGUNG" {
			continue
		}
		counts, err := src.AccountCounts(ctx, c)
		if err != nil {
			res.Failed = append(res.Failed, c.Code)
			continue
		}
		res.Cells++
		for id, n := range counts {
			if n < 0 {
				continue
			}
			// Chỉ nhận số của tenant đang gắn đúng cell báo về.
			tc, err := store.CurrentTenantCell(ctx, conn, id, s.Now())
			if err != nil || tc.CellID != c.ID {
				continue
			}
			changed, err := store.SetAccountCount(ctx, conn, id, n)
			if err != nil {
				return res, err
			}
			if changed {
				res.Updated++
			}
		}
	}
	return res, nil
}

// RunPeriodic chạy đồng bộ account_count theo chu kỳ (mặc định mỗi giờ) và dọn khoá chống ghi trùng quá 48 giờ.
func (s *Service) RunPeriodic(ctx context.Context, pool *pgxpool.Pool, src CellAccountSource, every time.Duration, log *slog.Logger) {
	if every <= 0 {
		every = time.Hour
	}
	tick := time.NewTicker(every)
	defer tick.Stop()
	for {
		if src != nil {
			r, err := s.SyncAccounts(ctx, pool, src)
			if err != nil {
				log.WarnContext(ctx, "đồng bộ số tài khoản lỗi", "err", err.Error())
			} else {
				log.InfoContext(ctx, "đồng bộ số tài khoản", "cells", r.Cells, "updated", r.Updated, "failed", len(r.Failed), "skipped", r.Skipped)
			}
		}
		if n, err := idem.Purge(ctx, pool); err == nil && n > 0 {
			log.InfoContext(ctx, "đã dọn khoá chống ghi trùng quá hạn", "rows", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
