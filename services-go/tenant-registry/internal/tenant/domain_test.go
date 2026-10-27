package tenant

import (
	"archive/zip"
	"bytes"
	"errors"
	"io"
	"strings"
	"testing"
)

// Bảng chuyển trạng thái đầy đủ: mọi cặp (từ, tới) trên 8 trạng thái cộng "chưa có", so với danh sách hợp lệ
// của TL-02a QT-01 (cộng DANG_CHUYEN_CELL của TL-03 và huỷ KHOI_TAO → DA_CHAM_DUT).
func TestTransitionTable(t *testing.T) {
	valid := map[[2]string]bool{
		{"", KhoiTao}:                 true,
		{KhoiTao, DangCapPhat}:        true,
		{KhoiTao, DaChamDut}:          true,
		{DangCapPhat, DangCauHinh}:    true,
		{DangCapPhat, KhoiTao}:        true,
		{DangCauHinh, SanSang}:        true,
		{SanSang, DangVanHanh}:        true,
		{DangVanHanh, TamNgung}:       true,
		{DangVanHanh, DangChuyenCell}: true,
		{TamNgung, DangVanHanh}:       true,
		{TamNgung, DaChamDut}:         true,
		{DangChuyenCell, DangVanHanh}: true,
	}
	from := append([]string{""}, Statuses...)
	n, ok := 0, 0
	for _, f := range from {
		for _, to := range append(Statuses, "", "KHONG_CO") {
			err := CanTransition(f, to)
			n++
			if valid[[2]string{f, to}] {
				ok++
				if err != nil {
					t.Errorf("%q → %q phải hợp lệ: %v", f, to, err)
				}
				continue
			}
			var te *TransitionError
			if !errors.As(err, &te) {
				t.Errorf("%q → %q phải bị chặn, nhận %v", f, to, err)
			} else if te.Error() == "" {
				t.Error("thông điệp rỗng")
			}
		}
	}
	if ok != len(valid) {
		t.Fatalf("số chuyển hợp lệ đã kiểm %d, mong %d", ok, len(valid))
	}
	if CanTransition("LA", KhoiTao) == nil {
		t.Error("trạng thái nguồn lạ phải bị chặn")
	}
	t.Logf("đã kiểm %d cặp chuyển trạng thái, %d hợp lệ", n, ok)
}

func TestIsOnboarding(t *testing.T) {
	for _, s := range Statuses {
		want := s == KhoiTao || s == DangCapPhat || s == DangCauHinh || s == SanSang
		if IsOnboarding(s) != want {
			t.Errorf("IsOnboarding(%s)", s)
		}
	}
}

// BR-01-01 và gợi ý mô hình.
func TestSuggestModelAndBR0101(t *testing.T) {
	r := DefaultRules()
	r.SiloRequiredCodes = []string{"dukcqtw"}
	cases := []struct {
		acc          int
		sector, code string
		want         string
		silo         bool
	}{
		{4999, "CHINH_QUYEN", "xa_a", Pool, false},
		{5000, "CHINH_QUYEN", "xa_a", Bridge, false},
		{98420, "CHINH_QUYEN", "thainguyen", Bridge, false},
		{1_000_000, "CHINH_QUYEN", "hanoi", Bridge, false},
		{1_000_001, "CHINH_QUYEN", "hanoi", Silo, true},
		{100, "LLVT", "qd1", Silo, true},
		{52000, "DANG", "dukcqtw", Silo, true},
		{52000, "DANG", "dang_tinh", Bridge, false},
	}
	for _, c := range cases {
		if got := r.SuggestModel(c.acc, c.sector, c.code); got != c.want {
			t.Errorf("SuggestModel(%d,%s,%s) = %s, mong %s", c.acc, c.sector, c.code, got, c.want)
		}
		for _, m := range []string{Bridge, Pool} {
			err := r.CheckModel(m, c.acc, c.sector, c.code)
			if c.silo != errors.Is(err, ErrSiloRequired) {
				t.Errorf("CheckModel(%s,%d,%s,%s) = %v", m, c.acc, c.sector, c.code, err)
			}
		}
		if r.CheckModel(Silo, c.acc, c.sector, c.code) != nil {
			t.Error("silo luôn được chọn")
		}
	}
	// Ngưỡng là tham số (TL-01 ghi bridge tới 150.000).
	r.BridgeMax = 150_000
	if r.SuggestModel(200_000, "CHINH_QUYEN", "x") != Silo {
		t.Error("BridgeMax tham số không có hiệu lực")
	}
}

// BR-01-02 tại biên 85%: đúng 85% được nhận, vượt 1 tài khoản bị chặn.
func TestCapacityBoundary(t *testing.T) {
	r := DefaultRules()
	c := CellLoad{Code: "CELL-G2", Model: "BRIDGE", Status: "HOAT_DONG", Capacity: 1_000_000, AccountCount: 600_000, Reserved: 750_000}
	if !r.FitsAfterAdding(c, 100_000) {
		t.Error("đúng 85% phải được nhận")
	}
	if r.FitsAfterAdding(c, 100_001) {
		t.Error("vượt 85% phải bị chặn")
	}
	if got := LoadAfter(c, 100_000); got != 85 {
		t.Errorf("LoadAfter = %v", got)
	}
	zero := CellLoad{Capacity: 0}
	if r.FitsAfterAdding(zero, 1) || !r.Over(zero) || zero.Percent() != 100 || LoadAfter(zero, 1) < 1e300 {
		t.Error("cell sức chứa 0 phải bị chặn")
	}
	// MSG-01-01: vượt 85% tải hiện tại.
	if r.Over(CellLoad{Capacity: 100, AccountCount: 85}) || !r.Over(CellLoad{Capacity: 100, AccountCount: 86}) {
		t.Error("Over sai biên")
	}
	if p := (CellLoad{Capacity: 3, AccountCount: 2}).Percent(); p != 66.7 {
		t.Errorf("Percent = %v", p)
	}
	if p := (CellLoad{Capacity: 1, AccountCount: 5}).Percent(); p != 100 {
		t.Errorf("Percent chặn 100, nhận %v", p)
	}
}

func TestSuggestCells(t *testing.T) {
	r := DefaultRules()
	cells := []CellLoad{
		{Code: "CELL-G1", Model: "BRIDGE", Status: "HOAT_DONG", Capacity: 1000, Reserved: 700},
		{Code: "CELL-G2", Model: "BRIDGE", Status: "HOAT_DONG", Capacity: 1000, Reserved: 100},
		{Code: "CELL-G3", Model: "BRIDGE", Status: "BAO_TRI", Capacity: 1000, Reserved: 0},
		{Code: "CELL-SH", Model: "POOL", Status: "HOAT_DONG", Capacity: 1000, Reserved: 0},
		{Code: "CELL-G4", Model: "BRIDGE", Status: "HOAT_DONG", Capacity: 1000, Reserved: 800},
	}
	got := r.SuggestCells(cells, Bridge, 100)
	if len(got) != 2 || got[0].Code != "CELL-G2" || got[1].Code != "CELL-G1" {
		t.Fatalf("SuggestCells = %+v", got)
	}
	if len(r.SuggestCells(cells, Bridge, 800)) != 0 {
		t.Error("không còn cell phải trả rỗng (MSG-01-03)")
	}
	if len(r.SuggestCells(cells, Silo, 1)) != 0 {
		t.Error("không có cell silo")
	}
}

func TestNamingAndKek(t *testing.T) {
	if OnboardingCode(2026, 42) != "ONB-2026-0042" {
		t.Error(OnboardingCode(2026, 42))
	}
	if DatabaseName(Pool, "x") != "td_pool" || DatabaseName(Bridge, "thainguyen") != "td_thainguyen" {
		t.Error("DatabaseName")
	}
	if DefaultKekMode(Pool) != KekDungChung || DefaultKekMode(Silo) != KekRieng || DefaultKekMode(Bridge) != KekRieng {
		t.Error("DefaultKekMode")
	}
	if !errors.Is(CheckKekMode(Silo, KekDungChung), ErrSiloKek) || CheckKekMode(Silo, KekRieng) != nil || CheckKekMode(Pool, KekDungChung) != nil {
		t.Error("CheckKekMode")
	}
	if KekRef(KekRieng, "hcm", "CELL-HCM") != "transit/keys/t-hcm" || KekRef(KekDungChung, "x", "CELL-SH") != "transit/keys/cell-cell-sh" ||
		KekRef(KekDungChung, "x", "") != "transit/keys/t-x" {
		t.Error("KekRef")
	}
	if Upper(Bridge) != "BRIDGE" || Lower("SILO") != Silo {
		t.Error("Upper/Lower")
	}
}

func TestStep(t *testing.T) {
	full := Progress{CellChosen: true, DomainSet: true, KekSet: true}
	cases := []struct {
		st   string
		p    Progress
		want int
	}{
		{KhoiTao, Progress{}, 2},
		{KhoiTao, full, 3},
		{DangCapPhat, full, 4},
		{DangCauHinh, full, 4},
		// ADR-34: nguồn nhân sự tuỳ chọn, chưa chọn nguồn vẫn sang bước 6 (đồng bộ ngoài mặc định tắt)
		{DangCauHinh, Progress{IdpCount: 1}, 6},
		{DangCauHinh, Progress{IdpCount: 1, StaffSourceCnt: 1}, 6},
		{SanSang, full, 6},
		{DangVanHanh, full, 0},
		{DaChamDut, full, 0},
	}
	for _, c := range cases {
		if got := Step(c.st, c.p); got != c.want {
			t.Errorf("Step(%s, %+v) = %d, mong %d", c.st, c.p, got, c.want)
		}
	}
	if stepPtr(DangVanHanh, full) != nil || *stepPtr(KhoiTao, full) != 3 {
		t.Error("stepPtr")
	}
}

func TestErrorsText(t *testing.T) {
	for _, e := range []error{&CapacityError{Model: Bridge}, &CapacityError{Cell: "CELL-G1", LoadAfter: 90}, &DuplicateError{Field: "domain", Value: "a.vn"},
		&StateError{Status: SanSang}, &VersionError{Sent: 1, Current: 2}, &ValidationError{Fields: []FieldError{{Field: "x"}}},
		&CellUnavailableError{Cell: "CELL-G3"}, &PackMissingError{Flow: "DANG"}} {
		if e.Error() == "" {
			t.Errorf("%T không có thông điệp", e)
		}
	}
	v := &validator{}
	if v.err() != nil {
		t.Error("validator rỗng phải trả nil")
	}
}

func TestExportFiles(t *testing.T) {
	step := 2
	rows := []TenantRow{
		{Code: "thainguyen", Name: "Tỉnh Thái Nguyên", Domain: "thainguyen.okr.viettel.vn", CellCode: "CELL-G2", TenancyModel: Bridge, AccountCount: 98420, Status: KhoiTao, OnboardingStep: &step},
		{Code: "x", Name: "=HYPERLINK(\"a\") & <b>", Status: DangVanHanh},
	}
	csv := string(ExportCSV(rows))
	if !strings.HasPrefix(csv, "\ufeffMã tenant,") || !strings.Contains(csv, "Tỉnh Thái Nguyên") || !strings.Contains(csv, "Khởi tạo") {
		t.Fatalf("CSV sai: %q", csv)
	}
	if strings.Contains(csv, ",=HYPERLINK") || !strings.Contains(csv, "'=HYPERLINK") {
		t.Error("CSV phải chặn chèn công thức")
	}
	if strings.Contains(csv, "KHOI_TAO") {
		t.Error("tệp xuất không được chứa mã trạng thái thô")
	}
	b, err := ExportXLSX(rows)
	if err != nil {
		t.Fatal(err)
	}
	zr, err := zip.NewReader(bytes.NewReader(b), int64(len(b)))
	if err != nil {
		t.Fatal(err)
	}
	var sheet string
	for _, f := range zr.File {
		if f.Name == "xl/worksheets/sheet1.xml" {
			rc, _ := f.Open()
			x, _ := io.ReadAll(rc)
			sheet = string(x)
		}
	}
	if !strings.Contains(sheet, "Tỉnh Thái Nguyên") || !strings.Contains(sheet, "<v>98420</v>") || !strings.Contains(sheet, "&lt;b&gt;") {
		t.Fatalf("XLSX sai: %s", sheet)
	}
	for _, s := range append(Statuses, "LA") {
		if StatusLabel(s) == "" {
			t.Error("nhãn rỗng")
		}
	}
	if viPercent(85.0) != "85" || viPercent(86.5) != "86,5" {
		t.Error(viPercent(86.5))
	}
	if xmlEscape("a\x01\"b") != "a&quot;b" {
		t.Error(xmlEscape("a\x01\"b"))
	}
}

func TestExportRequestCheck(t *testing.T) {
	r := ExportRequest{}
	if r.Check() != nil || r.Format != "XLSX" {
		t.Error("mặc định XLSX")
	}
	for _, bad := range []ExportRequest{{Format: "PDF"}, {Model: "airgap"}, {Status: "LA"}, {Cell: strings.Repeat("x", 17)}, {Q: strings.Repeat("x", 101)}} {
		if bad.Check() == nil {
			t.Errorf("phải từ chối %+v", bad)
		}
	}
	if _, err := decodeCursor("***"); err == nil {
		t.Error("con trỏ lỗi")
	}
}

func TestDateJSON(t *testing.T) {
	var d Date
	if err := d.UnmarshalJSON([]byte(`"2029-12-31"`)); err != nil {
		t.Fatal(err)
	}
	b, _ := d.MarshalJSON()
	if string(b) != `"2029-12-31"` {
		t.Error(string(b))
	}
	if d.UnmarshalJSON([]byte(`"31/12/2029"`)) == nil || d.UnmarshalJSON([]byte(`1`)) == nil {
		t.Error("phải từ chối ngày sai")
	}
	if datePtr(nil) != nil {
		t.Error("datePtr(nil)")
	}
}
