// Package tenant là nghiệp vụ vòng đời tenant của SVC-03 (QT-01; SCR-0101, SCR-0102): máy trạng thái,
// gợi ý mô hình thuê bao (BR-01-01), chọn cell theo tải (BR-01-02, MSG-01-01, MSG-01-03), mã hồ sơ khởi tạo
// ONB-yyyy-nnnn, bước wizard, và các lệnh ghi có nhật ký trạng thái, outbox, khoá chống ghi trùng.
package tenant

import (
	"fmt"
	"math"
	"slices"
	"sort"
	"strings"
)

// Trạng thái tenant (registry.tenant.status, TL-03; bảy trạng thái TL-02a QT-01 cộng DANG_CHUYEN_CELL).
const (
	KhoiTao        = "KHOI_TAO"
	DangCapPhat    = "DANG_CAP_PHAT"
	DangCauHinh    = "DANG_CAU_HINH"
	SanSang        = "SAN_SANG"
	DangVanHanh    = "DANG_VAN_HANH"
	TamNgung       = "TAM_NGUNG"
	DangChuyenCell = "DANG_CHUYEN_CELL"
	DaChamDut      = "DA_CHAM_DUT"
)

// Statuses là mọi trạng thái hợp lệ.
var Statuses = []string{KhoiTao, DangCapPhat, DangCauHinh, SanSang, DangVanHanh, TamNgung, DangChuyenCell, DaChamDut}

// transitions là bảng chuyển trạng thái hợp lệ (TL-02a QT-01 "Trạng thái đối tượng"):
//
//	(chưa có)        → KHOI_TAO          khai báo tenant
//	KHOI_TAO         → DANG_CAP_PHAT     xác nhận cell và mô hình, saga cấp phát bắt đầu
//	DANG_CAP_PHAT    → DANG_CAU_HINH     saga thành công
//	DANG_CAP_PHAT    → KHOI_TAO          saga lỗi không khắc phục, bù trừ xong
//	DANG_CAU_HINH    → SAN_SANG          đủ điều kiện (IdP, cây tổ chức, lệch mức Cao = 0)
//	SAN_SANG         → DANG_VAN_HANH     kích hoạt
//	DANG_VAN_HANH    → TAM_NGUNG         hết hạn hợp đồng, sự cố an ninh
//	TAM_NGUNG        → DANG_VAN_HANH     gia hạn, khắc phục
//	TAM_NGUNG        → DA_CHAM_DUT       chấm dứt
//	DANG_VAN_HANH    → DANG_CHUYEN_CELL  bắt đầu chuyển cell (TL-03, ADR-29)
//	DANG_CHUYEN_CELL → DANG_VAN_HANH     chuyển xong hoặc huỷ chuyển
//	KHOI_TAO         → DA_CHAM_DUT       huỷ yêu cầu khởi tạo (suy luận: SCR-0102 "Huỷ" không cấp phát
//	                                     tài nguyên; giữ bản ghi và nhật ký thay vì xoá)
var transitions = map[string][]string{
	"":             {KhoiTao},
	KhoiTao:        {DangCapPhat, DaChamDut},
	DangCapPhat:    {DangCauHinh, KhoiTao},
	DangCauHinh:    {SanSang},
	SanSang:        {DangVanHanh},
	DangVanHanh:    {TamNgung, DangChuyenCell},
	TamNgung:       {DangVanHanh, DaChamDut},
	DangChuyenCell: {DangVanHanh},
	DaChamDut:      {},
}

// TransitionError: chuyển trạng thái không hợp lệ.
type TransitionError struct{ From, To string }

func (e *TransitionError) Error() string {
	return fmt.Sprintf("tenant: không chuyển được từ %q sang %q", e.From, e.To)
}

// CanTransition kiểm một lần chuyển trạng thái.
func CanTransition(from, to string) error {
	if !slices.Contains(Statuses, to) {
		return &TransitionError{From: from, To: to}
	}
	next, ok := transitions[from]
	if !ok || !slices.Contains(next, to) {
		return &TransitionError{From: from, To: to}
	}
	return nil
}

// IsOnboarding: tenant đang khởi tạo (MSG-01-02).
func IsOnboarding(status string) bool {
	return status == KhoiTao || status == DangCapPhat || status == DangCauHinh || status == SanSang
}

// ---------------------------------------------------------------- mô hình thuê bao

// Mô hình trong hợp đồng config.yaml (chữ thường như registry.tenant_cell.tenancy_model) và registry.tenant
// (chữ hoa).
const (
	Silo   = "silo"
	Bridge = "bridge"
	Pool   = "pool"
)

// Upper đổi mô hình chữ thường sang giá trị registry.tenant.tenancy_model.
func Upper(model string) string { return strings.ToUpper(model) }

// Lower đổi registry.tenant.tenancy_model sang giá trị của hợp đồng config.
func Lower(model string) string { return strings.ToLower(model) }

// Rules là tham số nghiệp vụ (đọc từ cấu hình, mặc định theo TL-02a).
type Rules struct {
	// PoolMax: dưới ngưỡng này gợi ý pool (TL-02a: dưới 5.000 tài khoản).
	PoolMax int
	// BridgeMax: tới ngưỡng này gợi ý bridge. TL-02a ghi 1.000.000, TL-01 "Đa khách thuê" ghi 150.000;
	// mặc định theo TL-02a (đặc tả nghiệp vụ của màn hình), ghi ở wiki trang 14.
	BridgeMax int
	// SiloRequiredAbove: BR-01-01, trên ngưỡng này bắt buộc silo (1.000.000).
	SiloRequiredAbove int
	// SiloRequiredSectors: khối bắt buộc silo theo BR-01-01 (lực lượng vũ trang: LLVT).
	SiloRequiredSectors []string
	// SiloRequiredCodes: mã tenant thuộc khối Đảng Trung ương (BR-01-01). TL-03 không có cột phân biệt
	// "Trung ương" nên đặt thành tham số (suy luận), ghi ở wiki trang 14.
	SiloRequiredCodes []string
	// CapacityThreshold: BR-01-02, tải sau khi thêm không được vượt (0,85).
	CapacityThreshold float64
}

// DefaultRules theo TL-02a QT-01.
func DefaultRules() Rules {
	return Rules{PoolMax: 5000, BridgeMax: 1_000_000, SiloRequiredAbove: 1_000_000,
		SiloRequiredSectors: []string{"LLVT"}, CapacityThreshold: 0.85}
}

// SiloRequired: BR-01-01.
func (r Rules) SiloRequired(expectedAccounts int, sector, code string) bool {
	return expectedAccounts > r.SiloRequiredAbove || slices.Contains(r.SiloRequiredSectors, sector) ||
		slices.Contains(r.SiloRequiredCodes, code)
}

// SuggestModel gợi ý mô hình theo quy mô và BR-01-01 (TL-02a QT-01 bước 2).
func (r Rules) SuggestModel(expectedAccounts int, sector, code string) string {
	switch {
	case r.SiloRequired(expectedAccounts, sector, code):
		return Silo
	case expectedAccounts < r.PoolMax:
		return Pool
	case expectedAccounts <= r.BridgeMax:
		return Bridge
	}
	return Silo
}

// CheckModel áp BR-01-01 (chặn: không chọn được bridge, pool cho tenant thuộc diện silo).
func (r Rules) CheckModel(model string, expectedAccounts int, sector, code string) error {
	if model != Silo && r.SiloRequired(expectedAccounts, sector, code) {
		return ErrSiloRequired
	}
	return nil
}

// ---------------------------------------------------------------- tải cell

// CellLoad là tải của một cell để áp BR-01-02.
type CellLoad struct {
	Code         string
	Model        string // chữ hoa như registry.cell.tenancy_model
	Status       string
	Capacity     int
	AccountCount int // tổng account_count đang phục vụ (hiển thị, MSG-01-01)
	Reserved     int // tổng max(account_count, expected_accounts) của tenant chưa chấm dứt đang gắn
}

// Percent trả tải hiện tại (0..100, làm tròn 1 chữ số; vượt 100 thì chặn ở 100 theo hợp đồng).
func (c CellLoad) Percent() float64 {
	if c.Capacity <= 0 {
		return 100
	}
	p := math.Round(float64(c.AccountCount)*1000/float64(c.Capacity)) / 10
	return math.Min(p, 100)
}

// Over: cell vượt ngưỡng (MSG-01-01).
func (r Rules) Over(c CellLoad) bool {
	return c.Capacity <= 0 || float64(c.AccountCount) > r.CapacityThreshold*float64(c.Capacity)
}

// FitsAfterAdding áp BR-01-02: sau khi thêm add tài khoản (đã trừ phần giữ chỗ cũ của chính tenant nếu đang
// gắn cell này), tải không vượt ngưỡng. Biên đúng 85% được chấp nhận ("vượt 85%" mới chặn).
func (r Rules) FitsAfterAdding(c CellLoad, add int) bool {
	if c.Capacity <= 0 {
		return false
	}
	return float64(c.Reserved+add) <= r.CapacityThreshold*float64(c.Capacity)
}

// LoadAfter trả phần trăm tải sau khi thêm.
func LoadAfter(c CellLoad, add int) float64 {
	if c.Capacity <= 0 {
		return math.Inf(1)
	}
	return float64(c.Reserved+add) * 100 / float64(c.Capacity)
}

// SuggestCells trả các cell cùng mô hình, đang hoạt động, còn dưới ngưỡng sau khi thêm, tải thấp nhất trước.
// Rỗng nghĩa là MSG-01-03.
func (r Rules) SuggestCells(cells []CellLoad, model string, add int) []CellLoad {
	var out []CellLoad
	for _, c := range cells {
		if c.Model == Upper(model) && c.Status == "HOAT_DONG" && r.FitsAfterAdding(c, add) {
			out = append(out, c)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return LoadAfter(out[i], add) < LoadAfter(out[j], add) })
	return out
}

// DatabaseName trả tên CSDL dữ liệu tenant dự kiến (TL-03 tenant_cell.database_name: td_<mã> hoặc td_pool).
func DatabaseName(model, code string) string {
	if model == Pool {
		return "td_pool"
	}
	return "td_" + code
}

// ---------------------------------------------------------------- mã hồ sơ, KEK, bước wizard

// OnboardingCode định dạng ONB-yyyy-nnnn (SCR-0102 trường 1).
func OnboardingCode(year, seq int) string { return fmt.Sprintf("ONB-%04d-%04d", year, seq) }

// Loại khoá mã hoá (config.yaml TenantKekMode).
const (
	KekRieng      = "RIENG"
	KekDungChung  = "DUNG_CHUNG_CELL"
	kekTenantPath = "transit/keys/t-"
	kekCellPath   = "transit/keys/cell-"
)

// DefaultKekMode: SCR-0102 trường 9, mặc định riêng với silo, bridge; pool dùng chung của cell.
func DefaultKekMode(model string) string {
	if model == Pool {
		return KekDungChung
	}
	return KekRieng
}

// CheckKekMode: silo bắt buộc khoá riêng (SCR-0102 trường 9).
func CheckKekMode(model, mode string) error {
	if model == Silo && mode != KekRieng {
		return ErrSiloKek
	}
	return nil
}

// KekRef trả tên KEK dự kiến trong OpenBao Transit (registry.tenant.kek_ref NOT NULL). Saga cấp phát
// (DV-03-04) tạo khoá thật theo tên này (suy luận cách đặt tên theo ví dụ transit/keys/t-hcm-c2 của TL-03).
func KekRef(mode, tenantCode, cellCode string) string {
	if mode == KekDungChung && cellCode != "" {
		return kekCellPath + strings.ToLower(cellCode)
	}
	return kekTenantPath + tenantCode
}

// Progress là mức hoàn thành của từng bước wizard.
type Progress struct {
	CellChosen     bool // bước 2: cell, mô hình
	DomainSet      bool // bước 2: tên miền
	KekSet         bool // bước 2: khoá mã hoá
	PacksComplete  bool // bước 3: Policy Pack đủ cho mọi luồng
	UiPackSet      bool // bước 3: UI Pack
	IdpCount       int  // bước 4
	StaffSourceCnt int  // bước 5
}

// Step trả bước wizard hiện tại (1..6) hoặc 0 nếu tenant không còn khởi tạo (onboardingStep = null).
// Bước 1 xong khi đã tạo; trước cấp phát wizard chỉ đi tới bước 3; DANG_CAP_PHAT hiển thị bước 4 (tiến độ
// saga, theo Hình 6.7); sau cấp phát đi tiếp bước 4, 5, rồi 6 (suy luận từ SCR-0102 và Hình 6.7).
func Step(status string, p Progress) int {
	switch status {
	case KhoiTao:
		if !(p.CellChosen && p.DomainSet && p.KekSet) {
			return 2
		}
		return 3
	case DangCapPhat:
		return 4
	case DangCauHinh:
		if p.IdpCount == 0 {
			return 4
		}
		if p.StaffSourceCnt == 0 {
			return 5
		}
		return 6
	case SanSang:
		return 6
	}
	return 0
}
