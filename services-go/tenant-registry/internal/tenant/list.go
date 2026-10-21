package tenant

import (
	"context"
	"encoding/base64"
	"fmt"
	"strings"

	"github.com/google/uuid"

	"mission/services-go/tenant-registry/internal/store"
)

// ListInput là tham số listConfigTenants và exportConfigTenants.
type ListInput struct {
	Cell, Model, Status, Q string
	Cursor                 string
	Limit                  int
}

// ErrCursor: con trỏ không hợp lệ.
var ErrCursor = &ValidationError{Fields: []FieldError{{Field: "cursor", Message: "Con trỏ phân trang không hợp lệ"}}}

func encodeCursor(id uuid.UUID) string { return base64.RawURLEncoding.EncodeToString(id[:]) }

func decodeCursor(c string) (*uuid.UUID, error) {
	b, err := base64.RawURLEncoding.DecodeString(c)
	if err != nil || len(b) != 16 {
		return nil, ErrCursor
	}
	id, _ := uuid.FromBytes(b)
	return &id, nil
}

func (in ListInput) filter() (store.TenantFilter, error) {
	v := &validator{}
	if len(in.Cell) > 16 {
		v.add("cell", "Mã cell tối đa 16 ký tự")
	}
	if in.Model != "" && in.Model != Silo && in.Model != Bridge && in.Model != Pool {
		v.add("model", "Mô hình thuê bao không hợp lệ")
	}
	if in.Status != "" {
		ok := false
		for _, s := range Statuses {
			ok = ok || s == in.Status
		}
		if !ok {
			v.add("status", "Trạng thái không hợp lệ")
		}
	}
	if len([]rune(in.Q)) > 100 {
		v.add("q", "Từ khoá tối đa 100 ký tự")
	}
	if err := v.err(); err != nil {
		return store.TenantFilter{}, err
	}
	f := store.TenantFilter{Cell: in.Cell, Model: in.Model, Status: in.Status, Q: strings.TrimSpace(in.Q)}
	if in.Cursor != "" {
		id, err := decodeCursor(in.Cursor)
		if err != nil {
			return f, err
		}
		f.BeforeID = id
	}
	return f, nil
}

func rowView(r store.TenantRow) TenantRow {
	p := Progress{CellChosen: r.HasCell, DomainSet: r.HasDomain, KekSet: r.HasKek, IdpCount: r.IdpCount, StaffSourceCnt: r.StaffSrcCount}
	return TenantRow{ID: r.ID, Code: r.Code, Name: r.Name, Domain: r.Domain, CellCode: r.CellCode, TenancyModel: r.Model,
		AccountCount: r.AccountCount, Status: r.Status, OnboardingStep: stepPtr(r.Status, p)}
}

// List: listConfigTenants. Trang đầu (không có con trỏ) kèm summary của SCR-0101.
func (s *Service) List(ctx context.Context, q store.Querier, a Actor, in ListInput) (*Page, error) {
	if !a.PlatformAdmin {
		return nil, ErrForbidden
	}
	f, err := in.filter()
	if err != nil {
		return nil, err
	}
	limit := in.Limit
	f.Limit = limit + 1
	rows, err := store.ListTenantRows(ctx, q, f)
	if err != nil {
		return nil, err
	}
	page := &Page{Items: []TenantRow{}}
	if len(rows) > limit {
		rows = rows[:limit]
		c := encodeCursor(rows[len(rows)-1].ID)
		page.NextCursor = &c
	}
	for _, r := range rows {
		page.Items = append(page.Items, rowView(r))
	}
	f.BeforeID = nil
	if page.Total, err = store.CountTenantRows(ctx, q, f); err != nil {
		return nil, err
	}
	if in.Cursor == "" {
		if page.Summary, err = s.Summary(ctx, q); err != nil {
			return nil, err
		}
	}
	return page, nil
}

// Summary tính bốn chỉ số, tải cell (MSG-01-01), tenant đang khởi tạo (MSG-01-02) và việc cần xử lý.
func (s *Service) Summary(ctx context.Context, q store.Querier) (*SummaryView, error) {
	sm, err := store.TenantSummary(ctx, q)
	if err != nil {
		return nil, err
	}
	out := &SummaryView{ActiveTenants: sm.Active, TenantLimit: s.TenantLimit, TotalAccounts: sm.Accounts, AccountLimit: s.AccountLimit,
		OnboardingTenants: sm.Onboarding, CellCount: sm.Cells, DataCenterCount: sm.DataCenters,
		CellLoads: []CellLoadView{}, Onboarding: []OnboardingItem{}, PendingActions: []PendingAction{}}
	cells, err := store.ListCells(ctx, q, store.CellFilter{})
	if err != nil {
		return nil, err
	}
	for _, c := range cells {
		if c.Status == "NGUNG" {
			continue
		}
		l := CellLoad{Code: c.Code, Model: c.TenancyModel, Status: c.Status, Capacity: c.CapacityAccounts, AccountCount: c.AccountCount, Reserved: c.Reserved}
		cv := CellLoadView{CellCode: c.Code, AccountCount: c.AccountCount, CapacityAccounts: max(c.CapacityAccounts, 1),
			LoadPercent: l.Percent(), OverThreshold: s.Rules.Over(l)}
		out.CellLoads = append(out.CellLoads, cv)
		if cv.OverThreshold {
			out.PendingActions = append(out.PendingActions, PendingAction{Kind: "CHO",
				Message: fmt.Sprintf("Cell %s đã sử dụng %s%% dung lượng thiết kế. Đề nghị lập kế hoạch mở cell mới.", c.Code, viPercent(cv.LoadPercent))})
		}
	}
	for _, st := range []string{KhoiTao, DangCapPhat, DangCauHinh, SanSang} {
		rows, err := store.ListTenantRows(ctx, q, store.TenantFilter{Status: st, Limit: 200})
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			v := rowView(r)
			if v.OnboardingStep != nil {
				out.Onboarding = append(out.Onboarding, OnboardingItem{TenantID: r.ID, Name: r.Name, Step: *v.OnboardingStep})
			}
		}
	}
	return out, nil
}

// viPercent viết số phần trăm kiểu Việt Nam (dấu phẩy thập phân, bỏ ",0").
func viPercent(p float64) string {
	s := strings.TrimSuffix(fmt.Sprintf("%.1f", p), ".0")
	return strings.ReplaceAll(s, ".", ",")
}

// ExportRows trả mọi dòng khớp bộ lọc cho tác vụ xuất.
func (s *Service) ExportRows(ctx context.Context, q store.Querier, in ListInput) ([]TenantRow, error) {
	in.Cursor = ""
	f, err := in.filter()
	if err != nil {
		return nil, err
	}
	rows, err := store.ListTenantRows(ctx, q, f)
	if err != nil {
		return nil, err
	}
	out := make([]TenantRow, 0, len(rows))
	for _, r := range rows {
		out = append(out, rowView(r))
	}
	return out, nil
}

// CheckListInput kiểm bộ lọc (dùng trước khi nhận tác vụ xuất).
func CheckListInput(in ListInput) error { _, err := in.filter(); return err }
