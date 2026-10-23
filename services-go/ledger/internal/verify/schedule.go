package verify

import (
	"context"
	"fmt"
	"sort"
	"time"

	"github.com/google/uuid"

	"mission/services-go/ledger/internal/seal"
)

// Scheduler chạy kiểm tra theo lịch TL-03: DAU_CHUOI mỗi 15 phút, TOAN_BO hằng tuần (mỗi tuần một phần tư
// số tenant theo vòng), LUU_TRU hằng quý. NIEM_PHONG chạy ngay sau mỗi lần niêm phong qua AfterSeal.
type Scheduler struct {
	Service *Service
	Loc     *time.Location
	// Now cho phép kiểm thử đặt thời điểm.
	Now func() time.Time
}

func (s *Scheduler) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Scheduler) loc() *time.Location {
	if s.Loc != nil {
		return s.Loc
	}
	return time.FixedZone("ICT", 7*3600)
}

func (s *Scheduler) tenants(ctx context.Context) ([]uuid.UUID, error) {
	return (&seal.Sealer{Pool: s.Service.Pool}).Tenants(ctx)
}

// WeeklyShare là các tenant tới lượt TOAN_BO trong tuần ISO của t: tenant thứ i (sắp theo mã) khi i mod 4
// bằng số tuần mod 4.
func WeeklyShare(tenants []uuid.UUID, t time.Time) []uuid.UUID {
	sorted := append([]uuid.UUID(nil), tenants...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].String() < sorted[j].String() })
	_, week := t.ISOWeek()
	var out []uuid.UUID
	for i, tn := range sorted {
		if i%4 == week%4 {
			out = append(out, tn)
		}
	}
	return out
}

// AfterSeal là móc gọi sau mỗi lần niêm phong: kiểm NIEM_PHONG cho cửa sổ vừa niêm phong, toàn cell.
func (s *Scheduler) AfterSeal(ctx context.Context, res *seal.Result) {
	if res == nil || res.Existing || len(res.Tenants) == 0 {
		return
	}
	if _, err := s.Service.RunNow(ctx, Request{Kind: KindSeal, From: res.WindowStart, To: res.WindowEnd}); err != nil {
		s.Service.log().Error("kiểm tra niêm phong sau cửa sổ thất bại", "err", err)
	}
}

// Run chạy vòng lịch tới khi ctx kết thúc.
func (s *Scheduler) Run(ctx context.Context) {
	tick := time.NewTicker(15 * time.Minute)
	defer tick.Stop()
	lastWeek, lastQuarter := "", ""
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
		now := s.now().In(s.loc())
		tenants, err := s.tenants(ctx)
		if err != nil {
			s.Service.log().Error("không đọc được danh sách tenant để kiểm tra sổ cái", "err", err)
			continue
		}
		for _, t := range tenants {
			if _, err := s.Service.RunNow(ctx, Request{TenantID: t, Kind: KindHead}); err != nil {
				s.Service.log().Error("kiểm tra đầu chuỗi thất bại", "tenant_id", t, "err", err)
			}
		}
		y, w := now.ISOWeek()
		if wk := fmt.Sprintf("%d-%02d", y, w); now.Weekday() == time.Sunday && now.Hour() == 2 && wk != lastWeek {
			lastWeek = wk
			for _, t := range WeeklyShare(tenants, now) {
				if _, err := s.Service.RunNow(ctx, Request{TenantID: t, Kind: KindFull}); err != nil {
					s.Service.log().Error("kiểm tra toàn bộ thất bại", "tenant_id", t, "err", err)
				}
			}
		}
		if q := fmt.Sprintf("%d-Q%d", now.Year(), (now.Month()-1)/3+1); now.Day() == 1 && (now.Month()-1)%3 == 0 && now.Hour() == 3 && q != lastQuarter {
			lastQuarter = q
			for _, t := range tenants {
				if _, err := s.Service.RunNow(ctx, Request{TenantID: t, Kind: KindArchive}); err != nil {
					s.Service.log().Warn("kiểm tra lưu trữ chưa chạy được", "tenant_id", t, "err", err)
				}
			}
		}
	}
}
