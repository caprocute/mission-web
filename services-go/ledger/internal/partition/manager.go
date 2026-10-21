// Package partition cung cấp bộ quản lý phân vùng hai tầng (LIST tenant_id -> RANGE recorded_month)
// cho bảng ledger.ledger_entry theo TL-03 §2.8 và §4.
package partition

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// Querier trừu tượng hoá pgxpool.Pool / pgx.Conn / pgx.Tx.
type Querier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// RecordedMonth quy đổi thời điểm về định dạng YYYYMM theo múi giờ UTC.
func RecordedMonth(t time.Time) int {
	u := t.UTC()
	return u.Year()*100 + int(u.Month())
}

// NextRecordedMonth trả về tháng kế tiếp dạng YYYYMM.
func NextRecordedMonth(yyyymm int) (int, error) {
	year := yyyymm / 100
	month := yyyymm % 100
	if year < 2000 || year > 2999 || month < 1 || month > 12 {
		return 0, fmt.Errorf("recorded_month không hợp lệ: %d", yyyymm)
	}
	if month == 12 {
		return (year+1)*100 + 1, nil
	}
	return year*100 + (month + 1), nil
}

// EnsureTenantPartitions đảm bảo phân vùng LIST(tenant_id) và các phân vùng lá RANGE(recorded_month)
// từ fromMonth đến toMonth (YYYYMM) đã tồn tại, bật FORCE RLS và thu hồi quyền UPDATE/DELETE/TRUNCATE.
// Hàm idempotent: trả về số phân vùng mới được tạo (0 nếu đã có đủ).
func EnsureTenantPartitions(ctx context.Context, q Querier, tenantID uuid.UUID, fromMonth, toMonth int) (int, error) {
	var created int
	err := q.QueryRow(
		ctx,
		`SELECT ledger.fn_ensure_tenant_partitions($1, $2, $3)`,
		tenantID,
		fromMonth,
		toMonth,
	).Scan(&created)
	if err != nil {
		return 0, fmt.Errorf("ensure tenant partitions (tenant=%s, %d..%d): %w", tenantID, fromMonth, toMonth, err)
	}
	return created, nil
}

// EnsureTenantCurrentAndNextMonth đảm bảo phân vùng của tenant cho tháng hiện tại và tháng kế tiếp.
func EnsureTenantCurrentAndNextMonth(ctx context.Context, q Querier, tenantID uuid.UUID, now time.Time) (int, error) {
	cur := RecordedMonth(now)
	next, err := NextRecordedMonth(cur)
	if err != nil {
		return 0, err
	}
	return EnsureTenantPartitions(ctx, q, tenantID, cur, next)
}

// EnsureUpcomingMonthPartitions quét mọi phân vùng tenant hiện có trên ledger.ledger_entry
// và đảm bảo phân vùng tháng hiện tại + tháng kế tiếp đã được tạo trước.
func EnsureUpcomingMonthPartitions(ctx context.Context, q Querier, now time.Time) (int, error) {
	cur := RecordedMonth(now)
	next, err := NextRecordedMonth(cur)
	if err != nil {
		return 0, err
	}
	var created int
	err = q.QueryRow(
		ctx,
		`SELECT ledger.fn_ensure_upcoming_month_partitions($1, $2)`,
		cur,
		next,
	).Scan(&created)
	if err != nil {
		return 0, fmt.Errorf("ensure upcoming month partitions (%d..%d): %w", cur, next, err)
	}
	return created, nil
}

// RunPeriodic chạy vòng lặp nền tạo trước phân vùng tháng kế tiếp cho mọi tenant.
func RunPeriodic(ctx context.Context, q Querier, interval time.Duration, log *slog.Logger) {
	runOnce := func() {
		created, err := EnsureUpcomingMonthPartitions(ctx, q, time.Now().UTC())
		if err != nil {
			if ctx.Err() == nil && log != nil {
				log.Error("không tạo trước được phân vùng tháng kế tiếp", "err", err.Error())
			}
			return
		}
		if created > 0 && log != nil {
			log.Info("đã tạo trước phân vùng tháng cho ledger_entry", "created_partitions", created)
		}
	}

	runOnce()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			runOnce()
		}
	}
}
