package seal

import (
	"context"
	"errors"
	"time"
)

// Schedule là lịch cửa sổ niêm phong theo TL-01: Normal (1 giờ) ngày thường, Peak (5 phút) trong PeakDays
// ngày cuối mỗi kỳ đánh giá. PeriodEnds là các mốc cuối kỳ, đọc từ tham số cấu hình vì lịch kỳ thuộc
// config-service và chưa có bản sao trong ledger (suy luận).
type Schedule struct {
	Normal     time.Duration
	Peak       time.Duration
	PeakDays   int
	PeriodEnds []time.Time
}

// DefaultSchedule là 1 giờ thường, 5 phút trong 3 ngày cuối kỳ.
func DefaultSchedule(periodEnds []time.Time) Schedule {
	return Schedule{Normal: time.Hour, Peak: 5 * time.Minute, PeakDays: 3, PeriodEnds: periodEnds}
}

// IntervalAt là độ dài cửa sổ chứa thời điểm t.
func (s Schedule) IntervalAt(t time.Time) time.Duration {
	normal := s.Normal
	if normal <= 0 {
		normal = time.Hour
	}
	if s.Peak <= 0 || s.PeakDays <= 0 {
		return normal
	}
	for _, end := range s.PeriodEnds {
		if !t.After(end) && t.After(end.AddDate(0, 0, -s.PeakDays)) {
			return s.Peak
		}
	}
	return normal
}

// WindowEnd là mốc cuối cửa sổ gần nhất đã qua tại now (căn theo UTC; múi +7 lệch giờ chẵn nên vẫn
// trùng mốc giờ địa phương).
func (s Schedule) WindowEnd(now time.Time) time.Time {
	return now.UTC().Truncate(s.IntervalAt(now))
}

// Run niêm phong định kỳ theo lịch: chờ tới mốc cửa sổ kế tiếp cộng grace (để giao dịch ghi đang dở
// kịp commit), rồi gọi SealWindow. TSA lỗi chỉ ghi nhật ký; cửa sổ được gộp vào lần sau.
func (s *Sealer) Run(ctx context.Context, sched Schedule, grace time.Duration) error {
	s.Schedule = &sched
	for {
		now := s.now()
		next := sched.WindowEnd(now).Add(sched.IntervalAt(now))
		timer := time.NewTimer(next.Add(grace).Sub(now))
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil
		case <-timer.C:
		}
		start := time.Now()
		// Nhiều replica cùng chạy: ràng buộc uq_cell_seal__window chặn bản trùng và replica thua nhận lại
		// niêm phong đã có; không dùng khoá advisory mức phiên vì PgBouncer chạy chế độ giao dịch.
		res, err := s.SealWindow(ctx, next, KindPeriodic)
		switch {
		case errors.Is(err, ErrTSAUnavailable):
		case err != nil:
			s.log().Error("niêm phong sổ cái thất bại", "window_end", next, "err", err)
		case res != nil && !res.Existing:
			s.log().Info("đã niêm phong sổ cái", "window_end", next, "tenants", len(res.Tenants),
				"late", res.Late, "took_ms", time.Since(start).Milliseconds())
			if s.AfterSeal != nil {
				s.AfterSeal(ctx, res)
			}
		}
	}
}
