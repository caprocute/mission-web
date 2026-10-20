package resolve

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
)

type fakeLoader struct {
	calls int
	err   error
	res   map[string]*Resolution
}

func (f *fakeLoader) ByHost(_ context.Context, host string) (*Resolution, error) {
	return f.get("h:" + host)
}
func (f *fakeLoader) ByTenant(_ context.Context, t string) (*Resolution, error) {
	return f.get("t:" + t)
}
func (f *fakeLoader) get(k string) (*Resolution, error) {
	f.calls++
	if f.err != nil {
		return nil, f.err
	}
	if r, ok := f.res[k]; ok {
		return r, nil
	}
	return nil, ErrNotFound
}

func TestNormalizeHost(t *testing.T) {
	for in, want := range map[string]string{
		"KPI.DukCQTW.dcs.vn":     "kpi.dukcqtw.dcs.vn",
		"kpi.dukcqtw.dcs.vn:443": "kpi.dukcqtw.dcs.vn",
		" kpi.dukcqtw.dcs.vn. ":  "kpi.dukcqtw.dcs.vn",
	} {
		if got := NormalizeHost(in); got != want {
			t.Errorf("NormalizeHost(%q) = %q, muốn %q", in, got, want)
		}
	}
}

func TestCacheFreshStaleAndInvalidate(t *testing.T) {
	id := uuid.New()
	r := &Resolution{TenantID: id, TenantCode: "dukcqtw", CellCode: "CELL-HN"}
	fl := &fakeLoader{res: map[string]*Resolution{"h:kpi.dukcqtw.dcs.vn": r, "t:dukcqtw": r, "t:" + id.String(): r}}
	now := time.Unix(1_800_000_000, 0)
	s := New(fl, Options{TTL: 30 * time.Second, StaleMax: 300 * time.Second, Now: func() time.Time { return now }})
	ctx := context.Background()

	if got, err := s.ByHost(ctx, "KPI.dukcqtw.dcs.vn:443"); err != nil || got != r {
		t.Fatalf("phân giải theo host: %v", err)
	}
	if _, err := s.ByHost(ctx, "kpi.dukcqtw.dcs.vn"); err != nil || fl.calls != 1 {
		t.Fatalf("trúng đệm không được gọi CSDL, calls=%d", fl.calls)
	}
	if _, err := s.ByTenant(ctx, "DUKCQTW"); err != nil || fl.calls != 2 {
		t.Fatalf("theo mã: %v calls=%d", err, fl.calls)
	}
	if _, err := s.ByTenant(ctx, id.String()); err != nil {
		t.Fatalf("theo tenant_id: %v", err)
	}
	// Hết tươi: đọc lại.
	now = now.Add(31 * time.Second)
	if _, err := s.ByHost(ctx, "kpi.dukcqtw.dcs.vn"); err != nil || fl.calls != 4 {
		t.Fatalf("hết TTL phải đọc lại, calls=%d", fl.calls)
	}
	// CSDL lỗi: phục vụ mục cũ trong hạn chịu lỗi.
	fl.err = errors.New("connection refused")
	now = now.Add(60 * time.Second)
	if got, err := s.ByHost(ctx, "kpi.dukcqtw.dcs.vn"); err != nil || got != r {
		t.Fatalf("CSDL lỗi có đệm phải phục vụ đệm: %v", err)
	}
	// Quá hạn chịu lỗi: 503.
	now = now.Add(301 * time.Second)
	if _, err := s.ByHost(ctx, "kpi.dukcqtw.dcs.vn"); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("muốn ErrUnavailable, có %v", err)
	}
	// Không có đệm và CSDL lỗi: 503.
	if _, err := s.ByHost(ctx, "khac.okr.viettel.vn"); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("muốn ErrUnavailable, có %v", err)
	}
	// Đệm âm và vô hiệu.
	fl.err = nil
	if _, err := s.ByHost(ctx, "moi.okr.viettel.vn"); !errors.Is(err, ErrNotFound) {
		t.Fatal("tên miền chưa có phải ErrNotFound")
	}
	calls := fl.calls
	if _, err := s.ByHost(ctx, "moi.okr.viettel.vn"); !errors.Is(err, ErrNotFound) || fl.calls != calls {
		t.Fatal("đệm âm trong TTL không được gọi CSDL")
	}
	fl.res["h:moi.okr.viettel.vn"] = r
	s.Invalidate(id)
	if got, err := s.ByHost(ctx, "moi.okr.viettel.vn"); err != nil || got != r {
		t.Fatalf("sau Invalidate phải thấy tên miền mới: %v", err)
	}
	s.Flush()
	calls = fl.calls
	_, _ = s.ByHost(ctx, "moi.okr.viettel.vn")
	if fl.calls != calls+1 {
		t.Fatal("Flush phải xoá đệm")
	}
}
