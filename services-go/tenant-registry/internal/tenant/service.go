package tenant

import (
	"context"
	"encoding/json"
	"errors"
	"net/mail"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"mission/services-go/tenant-registry/internal/platform/crypto"
	"mission/services-go/tenant-registry/internal/platform/outbox"
	"mission/services-go/tenant-registry/internal/resolve"
	"mission/services-go/tenant-registry/internal/store"
)

// Service giữ tham số nghiệp vụ và phụ thuộc của các lệnh vòng đời tenant. Mọi lệnh ghi nhận một pgx.Tx do
// tầng API mở (cùng giao dịch với khoá chống ghi trùng), ghi nhật ký trạng thái khi đổi trạng thái, ghi
// outbox và NOTIFY làm mới bộ đệm phân giải trong cùng giao dịch.
type Service struct {
	Rules   Rules
	Crypto  crypto.Encryptor // mã hoá ứng dụng dữ liệu C2 (đầu mối quản trị)
	Secrets SecretStore      // kho bí mật OpenBao cho clientSecret / khoá riêng của IdP
	Prober  *IdpProber       // bộ kiểm kết nối OIDC, SAML, LDAPS chống SSRF
	Now     func() time.Time
	// DomainSuffix: tên miền mặc định <mã>.<hậu tố> (SCR-0102 trường 7, ví dụ thainguyen.okr.viettel.vn).
	DomainSuffix string
	// TenantLimit, AccountLimit: giới hạn thiết kế hiển thị ở SCR-0101 (NFR-01).
	TenantLimit  int
	AccountLimit int64
}

// NewService dựng Service với giá trị mặc định.
func NewService(r Rules, enc crypto.Encryptor) *Service {
	return &Service{
		Rules:        r,
		Crypto:       enc,
		Secrets:      NewMemorySecretStore(),
		Prober:       NewIdpProber(),
		Now:          time.Now,
		DomainSuffix: "okr.viettel.vn",
		TenantLimit:  5000,
		AccountLimit: 6_500_000,
	}
}

// Actor là người gọi đã xác thực, rút gọn cho tầng nghiệp vụ.
type Actor struct {
	ID            uuid.UUID
	PlatformAdmin bool
	TenantAdmin   bool
	Service       bool
	Tenant        string // claim tenant (mã hoặc uuid)
	TraceParent   string
}

// owns: quản trị tenant chỉ thao tác tenant của mình (phạm vi Tn).
func (a Actor) owns(t *store.Tenant) bool {
	return a.TenantAdmin && a.Tenant != "" && (a.Tenant == t.Code || a.Tenant == t.ID.String())
}

// CanRead: getConfigTenant (N cho platform_admin, Tn cho tenant_admin).
func (a Actor) CanRead(t *store.Tenant) bool { return a.PlatformAdmin || a.owns(t) }

var (
	codeRe   = regexp.MustCompile(`^[a-z0-9_]+$`)
	domainRe = regexp.MustCompile(`^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$`)
	sectors  = []string{"DANG", "CHINH_QUYEN", "SU_NGHIEP", "TRUONG_HOC", "BENH_VIEN", "LLVT", "DOANH_NGHIEP"}
	flowsAll = []string{"DANG", "NHA_NUOC"}
	models   = []string{Silo, Bridge, Pool}
	staffSrc = []string{"CSDL_CBCCVC", "CSDL_DANG_VIEN", "SCIM"}
)

// ---------------------------------------------------------------- tạo (bước 1)

// CreateInput là ConfigTenantCreate.
type CreateInput struct {
	Code             string        `json:"code"`
	Name             string        `json:"name"`
	Sector           string        `json:"sector"`
	ContractRef      string        `json:"contractRef"`
	ContractValidTo  *Date         `json:"contractValidTo"`
	Flows            []string      `json:"flows"`
	ExpectedAccounts *int          `json:"expectedAccounts"`
	ExpectedUnits    *int          `json:"expectedUnits"`
	AdminContact     *AdminContact `json:"adminContact"`
}

func checkFlows(v *validator, field string, flows []string) {
	if len(flows) == 0 {
		v.add(field, "Chọn ít nhất một luồng đánh giá")
		return
	}
	seen := map[string]bool{}
	for _, f := range flows {
		if !slices.Contains(flowsAll, f) || seen[f] {
			v.add(field, "Luồng đánh giá không hợp lệ hoặc bị lặp")
			return
		}
		seen[f] = true
	}
}

func checkName(v *validator, name string) {
	if n := len([]rune(strings.TrimSpace(name))); n == 0 || n > 200 {
		v.add("name", "Tên tenant bắt buộc, tối đa 200 ký tự")
	}
}

func checkContractRef(v *validator, ref string) {
	if n := len([]rune(strings.TrimSpace(ref))); n == 0 || n > 50 {
		v.add("contractRef", "Số hợp đồng bắt buộc, tối đa 50 ký tự")
	}
}

func checkAdmin(v *validator, a *AdminContact) {
	if a == nil {
		v.add("adminContact", "Thiếu đầu mối quản trị tenant")
		return
	}
	if n := len([]rune(strings.TrimSpace(a.FullName))); n == 0 || n > 200 {
		v.add("adminContact.fullName", "Họ tên đầu mối bắt buộc, tối đa 200 ký tự")
	}
	if _, err := mail.ParseAddress(a.Email); err != nil || len(a.Email) > 254 || strings.ContainsAny(a.Email, " <>") {
		v.add("adminContact.email", "Thư điện tử không hợp lệ")
	}
	if len(a.Phone) > 20 {
		v.add("adminContact.phone", "Số điện thoại tối đa 20 ký tự")
	}
}

func positive(v *validator, field string, n *int) {
	if n != nil && *n < 1 {
		v.add(field, "Giá trị phải lớn hơn 0")
	}
}

// Create: createConfigTenant (QT-01 bước 1). Tenant ở KHOI_TAO, mã hồ sơ ONB-yyyy-nnnn, mô hình gợi ý theo
// quy mô và BR-01-01, tên miền mặc định <mã>.<hậu tố> (MSG-01-04 khi trùng).
func (s *Service) Create(ctx context.Context, tx pgx.Tx, a Actor, in CreateInput) (*ConfigTenant, error) {
	if !a.PlatformAdmin {
		return nil, ErrForbidden
	}
	in.Code, in.Name = strings.TrimSpace(in.Code), strings.TrimSpace(in.Name)
	v := &validator{}
	if !codeRe.MatchString(in.Code) || len(in.Code) > 32 {
		v.add("code", "Mã tenant chỉ gồm chữ thường, chữ số, gạch dưới, tối đa 32 ký tự")
	}
	checkName(v, in.Name)
	if !slices.Contains(sectors, in.Sector) {
		v.add("sector", "Nhóm khách hàng không hợp lệ")
	}
	checkContractRef(v, in.ContractRef)
	checkFlows(v, "flows", in.Flows)
	if in.ExpectedAccounts == nil {
		v.add("expectedAccounts", "Thiếu số tài khoản dự kiến")
	}
	positive(v, "expectedAccounts", in.ExpectedAccounts)
	positive(v, "expectedUnits", in.ExpectedUnits)
	checkAdmin(v, in.AdminContact)
	if err := v.err(); err != nil {
		return nil, err
	}
	if _, err := store.GetTenantByCode(ctx, tx, in.Code); err == nil {
		return nil, &DuplicateError{Field: "code", Value: in.Code}
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	if taken, err := store.TenantNameTaken(ctx, tx, in.Name, uuid.Nil); err != nil {
		return nil, err
	} else if taken {
		return nil, &DuplicateError{Field: "name", Value: in.Name}
	}
	// Nhãn tên miền không nhận gạch dưới: thay bằng gạch nối (suy luận).
	domain := strings.ReplaceAll(in.Code, "_", "-") + "." + s.DomainSuffix
	if _, err := store.TenantIDByDomain(ctx, tx, domain); err == nil {
		return nil, &DuplicateError{Field: "domain", Value: domain}
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	year := s.Now().Year()
	seq, err := store.NextOnboardingSeq(ctx, tx, year)
	if err != nil {
		return nil, err
	}
	code := OnboardingCode(year, seq)
	model := s.Rules.SuggestModel(*in.ExpectedAccounts, in.Sector, in.Code)
	kek := DefaultKekMode(model)
	ref := strings.TrimSpace(in.ContractRef)
	t := &store.Tenant{ID: store.NewID(), Code: in.Code, Name: in.Name, Sector: in.Sector, TenancyModel: Upper(model), Status: KhoiTao,
		ContractRef: &ref, MaxAccounts: *in.ExpectedAccounts, CreatedBy: a.ID, OnboardingCode: &code, Flows: in.Flows,
		ExpectedAccounts: in.ExpectedAccounts, KekRef: KekRef(kek, in.Code, "")}
	if in.ContractValidTo != nil {
		d := in.ContractValidTo.Time
		t.ContractValidTo = &d
	}
	if err := store.InsertTenant(ctx, tx, t); err != nil {
		return nil, mapDup(err, in.Code)
	}
	if err := s.logStatus(ctx, tx, a, t.ID, "", KhoiTao, "Khai báo tenant mới, mã hồ sơ "+code); err != nil {
		return nil, err
	}
	if err := store.InsertDomain(ctx, tx, &store.Domain{TenantID: t.ID, Domain: domain, Kind: "PRIMARY"}, a.ID); err != nil {
		return nil, mapDup(err, domain)
	}
	o := &store.Onboarding{TenantID: t.ID, ExpectedUnits: in.ExpectedUnits, KekMode: kek}
	if err := s.sealContact(o, t.ID, in.AdminContact); err != nil {
		return nil, err
	}
	if err := store.InsertOnboarding(ctx, tx, o, a.ID); err != nil {
		return nil, err
	}
	if err := s.publish(ctx, tx, a, t, "TENANT_CREATED", nil); err != nil {
		return nil, err
	}
	v2, _, err := s.loadConfig(ctx, tx, t.ID, true)
	return v2, err
}

func (s *Service) sealContact(o *store.Onboarding, tenantID uuid.UUID, c *AdminContact) error {
	if c == nil {
		o.AdminContactEnc, o.DekID = nil, nil
		return nil
	}
	if s.Crypto == nil {
		return errors.New("tenant: chưa cấu hình khoá mã hoá dữ liệu C2")
	}
	plain, err := json.Marshal(c)
	if err != nil {
		return err
	}
	ct, dek, err := s.Crypto.Encrypt(plain, tenantID[:])
	if err != nil {
		return err
	}
	o.AdminContactEnc, o.DekID = ct, &dek
	return nil
}

// mapDup chuyển vi phạm ràng buộc duy nhất của CSDL (cuộc đua giữa hai yêu cầu) thành DuplicateError.
func mapDup(err error, value string) error {
	var d *store.DuplicateError
	if !errors.As(err, &d) {
		return err
	}
	switch d.Constraint {
	case "uq_tenant_domain__domain":
		return &DuplicateError{Field: "domain", Value: value}
	case "uq_tenant__onboarding_code":
		return &DuplicateError{Field: "onboardingCode", Value: value}
	}
	return &DuplicateError{Field: "code", Value: value}
}

// ---------------------------------------------------------------- lưu nháp (JSON Merge Patch)

// Trường của ConfigTenantPatch chia theo bước wizard và vai trò (ma trận SCR-0102.buoc-1-3, buoc-4-5).
var (
	step13Fields = []string{"name", "contractRef", "contractValidTo", "flows", "expectedAccounts", "expectedUnits", "tenancyModel",
		"cellCode", "domain", "dataRegion", "kekMode", "policyBindings", "uiPackVersionId", "adminContact"}
	step45Fields = []string{"idps", "staffSources"}
)

// PatchFields trả danh sách trường được phép của ConfigTenantPatch.
func PatchFields() []string { return append(slices.Clone(step13Fields), step45Fields...) }

// step45States: trạng thái cho phép sửa IdP và nguồn nhân sự (suy luận: sau khi saga tạo Organization, trước
// khi chấm dứt; không sửa khi saga đang chạy).
var step45States = []string{KhoiTao, DangCauHinh, SanSang, DangVanHanh, TamNgung}

func isNull(r json.RawMessage) bool { return strings.TrimSpace(string(r)) == "null" }

func decodeField(v *validator, patch map[string]json.RawMessage, field string, dst any) bool {
	r, ok := patch[field]
	if !ok || isNull(r) {
		return false
	}
	if err := json.Unmarshal(r, dst); err != nil {
		v.add(field, "Kiểu dữ liệu không đúng")
		return false
	}
	return true
}

// Patch: updateConfigTenant. expected là phiên bản trong If-Match.
func (s *Service) Patch(ctx context.Context, tx pgx.Tx, a Actor, id uuid.UUID, expected int, patch map[string]json.RawMessage) (*ConfigTenant, error) {
	t, err := store.GetTenantForUpdate(ctx, tx, id)
	if errors.Is(err, store.ErrNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if !a.CanRead(t) {
		return nil, ErrForbidden // ngoài phạm vi Tn
	}
	var has13, has45 bool
	for k := range patch {
		switch {
		case slices.Contains(step13Fields, k):
			has13 = true
		case slices.Contains(step45Fields, k):
			has45 = true
		default:
			return nil, &ValidationError{Fields: []FieldError{{Field: k, Message: "Trường không được hỗ trợ"}}}
		}
	}
	if (has13 && !a.PlatformAdmin) || (has45 && !a.owns(t)) {
		return nil, ErrForbidden
	}
	if t.RowVersion != expected {
		return nil, &VersionError{Sent: expected, Current: t.RowVersion}
	}
	if has13 && t.Status != KhoiTao {
		return nil, &StateError{Status: t.Status}
	}
	if has45 && !slices.Contains(step45States, t.Status) {
		return nil, &StateError{Status: t.Status}
	}
	o, err := store.GetOnboarding(ctx, tx, id)
	if errors.Is(err, store.ErrNotFound) {
		o = &store.Onboarding{TenantID: id, KekMode: DefaultKekMode(Lower(t.TenancyModel))}
		if err := store.InsertOnboarding(ctx, tx, o, a.ID); err != nil {
			return nil, err
		}
	} else if err != nil {
		return nil, err
	}
	if has13 {
		if err := s.patchSteps13(ctx, tx, a, t, o, patch); err != nil {
			return nil, err
		}
	}
	if has45 {
		if err := s.patchSteps45(ctx, tx, a, t, o, patch); err != nil {
			return nil, err
		}
	}
	if err := store.UpdateOnboarding(ctx, tx, o, a.ID); err != nil {
		return nil, err
	}
	t.UpdatedBy = a.ID
	if err := store.UpdateTenant(ctx, tx, t, expected); err != nil {
		return nil, s.versionErr(err)
	}
	if err := s.publish(ctx, tx, a, t, "TENANT_DRAFT_UPDATED", nil); err != nil {
		return nil, err
	}
	v, _, err := s.loadConfig(ctx, tx, id, true)
	return v, err
}

func (s *Service) versionErr(err error) error {
	var vc *store.VersionConflictError
	if errors.As(err, &vc) {
		return &VersionError{Sent: vc.Sent, Current: vc.Current}
	}
	return err
}

func (s *Service) patchSteps13(ctx context.Context, tx pgx.Tx, a Actor, t *store.Tenant, o *store.Onboarding, patch map[string]json.RawMessage) error {
	v := &validator{}
	// Phần giữ chỗ hiện tại của tenant trên cell (trước khi áp patch), để trừ khi tính lại tải.
	oldReserve := t.AccountCount
	if t.ExpectedAccounts != nil {
		oldReserve = max(oldReserve, *t.ExpectedAccounts)
	}
	for _, f := range []string{"name", "contractRef", "flows", "expectedAccounts", "tenancyModel", "kekMode", "domain"} {
		if r, ok := patch[f]; ok && isNull(r) {
			v.add(f, "Trường bắt buộc, không được xoá")
		}
	}
	var name, contractRef string
	if decodeField(v, patch, "name", &name) {
		checkName(v, name)
		t.Name = strings.TrimSpace(name)
	}
	if decodeField(v, patch, "contractRef", &contractRef) {
		checkContractRef(v, contractRef)
		ref := strings.TrimSpace(contractRef)
		t.ContractRef = &ref
	}
	if r, ok := patch["contractValidTo"]; ok {
		var d Date
		if isNull(r) {
			t.ContractValidTo = nil
		} else if decodeField(v, patch, "contractValidTo", &d) {
			t.ContractValidTo = &d.Time
		}
	}
	var flows []string
	flowsChanged := decodeField(v, patch, "flows", &flows)
	if flowsChanged {
		checkFlows(v, "flows", flows)
		t.Flows = flows
	}
	var expAcc int
	expChanged := decodeField(v, patch, "expectedAccounts", &expAcc)
	if expChanged {
		positive(v, "expectedAccounts", &expAcc)
		// Số tài khoản tối đa đi theo số dự kiến (suy luận: SCR-0102 chưa có trường riêng cho max_accounts).
		t.ExpectedAccounts, t.MaxAccounts = &expAcc, max(expAcc, 1)
	}
	if r, ok := patch["expectedUnits"]; ok {
		var n int
		if isNull(r) {
			o.ExpectedUnits = nil
		} else if decodeField(v, patch, "expectedUnits", &n) {
			positive(v, "expectedUnits", &n)
			o.ExpectedUnits = &n
		}
	}
	var model string
	modelChanged := decodeField(v, patch, "tenancyModel", &model)
	if modelChanged && !slices.Contains(models, model) {
		v.add("tenancyModel", "Mô hình thuê bao không hợp lệ")
	}
	var kek string
	kekChanged := decodeField(v, patch, "kekMode", &kek)
	if kekChanged && kek != KekRieng && kek != KekDungChung {
		v.add("kekMode", "Loại khoá không hợp lệ")
	}
	var domain string
	domainChanged := decodeField(v, patch, "domain", &domain)
	domain = strings.ToLower(strings.TrimSpace(domain))
	if domainChanged && (len(domain) > 253 || !domainRe.MatchString(domain)) {
		v.add("domain", "Tên miền không hợp lệ")
	}
	var cellCode string
	_, cellKey := patch["cellCode"]
	cellGiven := decodeField(v, patch, "cellCode", &cellCode)
	var region string
	regionGiven := decodeField(v, patch, "dataRegion", &region)
	if regionGiven && (region == "" || len(region) > 32) {
		v.add("dataRegion", "Vùng dữ liệu không hợp lệ")
	}
	var bindings []PolicyBinding
	bindingsChanged := decodeField(v, patch, "policyBindings", &bindings)
	if r, ok := patch["policyBindings"]; ok && isNull(r) {
		bindings, bindingsChanged = []PolicyBinding{}, true
	}
	if r, ok := patch["uiPackVersionId"]; ok {
		var u uuid.UUID
		if isNull(r) {
			o.UiPackVersionID = nil
		} else if decodeField(v, patch, "uiPackVersionId", &u) {
			o.UiPackVersionID = &u
		}
	}
	if r, ok := patch["adminContact"]; ok {
		if isNull(r) {
			v.add("adminContact", "Trường bắt buộc, không được xoá")
		} else {
			// Merge Patch lồng: trường vắng giữ giá trị cũ.
			cur := s.openContact(o, t.ID)
			if cur == nil {
				cur = &AdminContact{}
			}
			if err := json.Unmarshal(r, cur); err != nil {
				v.add("adminContact", "Kiểu dữ liệu không đúng")
			} else {
				checkAdmin(v, cur)
				if err := s.sealContact(o, t.ID, cur); err != nil {
					return err
				}
			}
		}
	}
	if err := v.err(); err != nil {
		return err
	}
	if _, ok := patch["name"]; ok {
		if taken, err := store.TenantNameTaken(ctx, tx, t.Name, t.ID); err != nil {
			return err
		} else if taken {
			return &DuplicateError{Field: "name", Value: t.Name}
		}
	}

	// BR-01-01 trên mô hình hiệu lực.
	curModel := Lower(t.TenancyModel)
	if !modelChanged {
		model = curModel
	}
	exp := 0
	if t.ExpectedAccounts != nil {
		exp = *t.ExpectedAccounts
	}
	if err := s.Rules.CheckModel(model, exp, t.Sector, t.Code); err != nil {
		return err
	}
	if modelChanged && model != curModel && !kekChanged {
		kek, kekChanged = DefaultKekMode(model), true
	}
	if kekChanged {
		o.KekMode = kek
	}
	if err := CheckKekMode(model, o.KekMode); err != nil {
		return err
	}
	t.TenancyModel = Upper(model)

	// Cell và vùng dữ liệu (BR-01-02).
	binding, err := store.LatestTenantCell(ctx, tx, t.ID)
	if errors.Is(err, store.ErrNotFound) {
		binding = nil
	} else if err != nil {
		return err
	}
	if cellKey || modelChanged || (binding != nil && (expChanged || regionGiven)) {
		if err := s.placeCell(ctx, tx, a, t, binding, oldReserve, model, cellCode, cellGiven, !cellKey, region, regionGiven); err != nil {
			return err
		}
	} else if regionGiven {
		o.DataRegion = &region
	}
	cellNow := ""
	if b, err := store.LatestTenantCell(ctx, tx, t.ID); err == nil {
		cellNow = b.CellCode
		o.DataRegion = &b.DataRegion
	}
	t.KekRef = KekRef(o.KekMode, t.Code, cellNow)

	if domainChanged {
		if other, err := store.TenantIDByDomain(ctx, tx, domain); err == nil && other != t.ID {
			return &DuplicateError{Field: "domain", Value: domain}
		} else if err != nil && !errors.Is(err, store.ErrNotFound) {
			return err
		}
		if err := store.DeleteDomains(ctx, tx, t.ID, "PRIMARY"); err != nil {
			return err
		}
		if err := store.InsertDomain(ctx, tx, &store.Domain{TenantID: t.ID, Domain: domain, Kind: "PRIMARY"}, a.ID); err != nil {
			return mapDup(err, domain)
		}
	}

	// Policy Pack theo luồng (BR-01-04). BR-01-03 (gói DA_PHAT_HANH, 100% test case vàng) chờ MOD-05.
	if !bindingsChanged {
		_ = json.Unmarshal(o.PolicyBindings, &bindings)
		if flowsChanged { // bỏ gói của luồng không còn dùng
			bindings = slices.DeleteFunc(bindings, func(b PolicyBinding) bool { return !slices.Contains(t.Flows, b.Flow) })
		}
	}
	if bindingsChanged || flowsChanged {
		seen := map[string]bool{}
		for _, b := range bindings {
			if !slices.Contains(t.Flows, b.Flow) || seen[b.Flow] || b.PackVersionID == uuid.Nil {
				return &ValidationError{Fields: []FieldError{{Field: "policyBindings", Message: "Mỗi luồng của tenant gắn đúng một gói chính sách"}}}
			}
			seen[b.Flow] = true
		}
		if bindingsChanged && len(bindings) > 0 {
			for _, f := range t.Flows {
				if !seen[f] {
					return &PackMissingError{Flow: f}
				}
			}
		}
		if bindings == nil {
			bindings = []PolicyBinding{}
		}
		b, _ := json.Marshal(bindings)
		o.PolicyBindings = b
	}
	return nil
}

// placeCell chọn hoặc kiểm cell cho bản nháp và ghi tenant_cell nháp (hiệu lực từ lúc chọn để phần giữ chỗ
// được tính vào tải cell, tránh hai bản nháp cùng lấy phần dung lượng còn lại).
// keep: người dùng không gửi cellCode, giữ cell đang chọn nếu còn hợp mô hình và còn chỗ.
func (s *Service) placeCell(ctx context.Context, tx pgx.Tx, a Actor, t *store.Tenant, binding *store.TenantCell, oldReserve int, model, cellCode string,
	cellGiven, keep bool, region string, regionGiven bool) error {
	cells, err := store.ListCells(ctx, tx, store.CellFilter{})
	if err != nil {
		return err
	}
	add := t.MaxAccounts
	if t.ExpectedAccounts != nil {
		add = max(*t.ExpectedAccounts, t.AccountCount)
	}
	loads := make([]CellLoad, 0, len(cells))
	byCode := map[string]store.Cell{}
	for _, c := range cells {
		l := CellLoad{Code: c.Code, Model: c.TenancyModel, Status: c.Status, Capacity: c.CapacityAccounts, AccountCount: c.AccountCount, Reserved: c.Reserved}
		if binding != nil && binding.CellID == c.ID && binding.ValidTo.After(s.Now()) {
			// Bỏ phần giữ chỗ cũ của chính tenant (đã tính trong Reserved).
			l.Reserved = max(0, l.Reserved-oldReserve)
		}
		loads = append(loads, l)
		byCode[c.Code] = c
	}
	if !cellGiven && keep && binding != nil && Upper(model) == byCode[binding.CellCode].TenancyModel {
		cellCode, cellGiven = binding.CellCode, true // giữ cell đã chọn nếu còn hợp mô hình
	}
	var chosen CellLoad
	if cellGiven {
		c, ok := byCode[cellCode]
		if !ok {
			return &ValidationError{Fields: []FieldError{{Field: "cellCode", Message: "Không có cell này"}}}
		}
		if c.TenancyModel != Upper(model) {
			return &ValidationError{Fields: []FieldError{{Field: "cellCode", Message: "Cell không phục vụ mô hình thuê bao đã chọn"}}}
		}
		if c.Status != "HOAT_DONG" {
			return &CellUnavailableError{Cell: c.Code}
		}
		for _, l := range loads {
			if l.Code == cellCode {
				chosen = l
			}
		}
		if !s.Rules.FitsAfterAdding(chosen, add) {
			return &CapacityError{Cell: cellCode, Model: model, LoadAfter: LoadAfter(chosen, add)}
		}
	} else {
		fits := s.Rules.SuggestCells(loads, model, add)
		if len(fits) == 0 {
			return &CapacityError{Model: model}
		}
		chosen = fits[0]
	}
	c := byCode[chosen.Code]
	if !regionGiven {
		region = c.PrimaryDC
		if binding != nil && binding.CellID == c.ID {
			region = binding.DataRegion
		}
	}
	dbName := DatabaseName(model, t.Code)
	if binding != nil && binding.ValidTo.After(s.Now()) {
		binding.CellID, binding.DatabaseName, binding.TenancyModel, binding.DataRegion = c.ID, dbName, model, region
		return store.UpdateDraftTenantCell(ctx, tx, binding, a.ID)
	}
	return store.InsertTenantCell(ctx, tx, &store.TenantCell{TenantID: t.ID, CellID: c.ID, DatabaseName: dbName, ValidFrom: s.Now(),
		TenancyModel: model, DataRegion: region}, a.ID)
}

func (s *Service) openContact(o *store.Onboarding, tenantID uuid.UUID) *AdminContact {
	if o.AdminContactEnc == nil || o.DekID == nil || s.Crypto == nil {
		return nil
	}
	plain, err := s.Crypto.Decrypt(o.AdminContactEnc, tenantID[:], *o.DekID)
	if err != nil {
		return nil
	}
	var c AdminContact
	if json.Unmarshal(plain, &c) != nil {
		return nil
	}
	return &c
}

func (s *Service) patchSteps45(ctx context.Context, tx pgx.Tx, a Actor, t *store.Tenant, o *store.Onboarding, patch map[string]json.RawMessage) error {
	v := &validator{}
	if r, ok := patch["idps"]; ok {
		var idps []IdpProbeInput
		if !isNull(r) {
			if err := json.Unmarshal(r, &idps); err != nil {
				return &ValidationError{Fields: []FieldError{{Field: "idps", Message: "Kiểu dữ liệu không đúng"}}}
			}
		}
		seen := map[string]bool{}
		seenDomains := map[string]bool{}
		keep := []string{}
		for i, d := range idps {
			d.IdpAlias = strings.TrimSpace(d.IdpAlias)
			if d.IdpAlias == "" || len(d.IdpAlias) > 64 || seen[d.IdpAlias] {
				v.add("idps", "Bí danh IdP bắt buộc, không trùng, tối đa 64 ký tự")
			}
			if d.Protocol != "OIDC" && d.Protocol != "SAML" {
				v.add("idps", "Giao thức chỉ nhận OIDC hoặc SAML")
			}
			if d.Issuer == "" || len(d.Issuer) > 255 {
				v.add("idps", "Địa chỉ phát hành bắt buộc, tối đa 255 ký tự")
			} else if _, err := ValidateExternalURL(d.Issuer, "https"); err != nil {
				v.add("idps", err.Error())
			}
			if d.MetadataURL != "" {
				if _, err := ValidateExternalURL(d.MetadataURL, "https"); err != nil {
					v.add("idps", err.Error())
				}
			}
			if d.IsVneid {
				if len(d.AttributeMapping) == 0 {
					d.AttributeMapping = map[string]string{
						"cccd":      "citizen_id",
						"ho_ten":    "full_name",
						"ngay_sinh": "birth_date",
					}
				} else if strings.TrimSpace(d.AttributeMapping["cccd"]) == "" {
					v.add("idps", "IdP VNeID bắt buộc ánh xạ trường định danh cá nhân (cccd)")
				}
			}
			normDomains := make([]string, 0, len(d.EmailDomains))
			for _, ed := range d.EmailDomains {
				ed = strings.ToLower(strings.TrimSpace(ed))
				if ed == "" || len(ed) > 253 || !domainRe.MatchString(ed) {
					v.add("idps", "Tên miền thư điện tử không đúng định dạng")
					continue
				}
				if seenDomains[ed] {
					v.add("idps", "Tên miền thư điện tử bị trùng trong danh sách IdP")
					continue
				}
				seenDomains[ed] = true
				normDomains = append(normDomains, ed)
			}
			d.EmailDomains = normDomains
			seen[d.IdpAlias] = true
			keep = append(keep, d.IdpAlias)
			idps[i] = d
		}
		if err := v.err(); err != nil {
			return err
		}
		for _, d := range idps {
			for _, ed := range d.EmailDomains {
				taken, err := store.EmailDomainTaken(ctx, tx, ed, t.ID)
				if err != nil {
					return err
				}
				if taken {
					return &ValidationError{Fields: []FieldError{{Field: "idps", Message: "Tên miền thư điện tử " + ed + " đã được đơn vị khác đăng ký cho đăng nhập một lần"}}}
				}
			}
		}
		cur, err := store.ListIdps(ctx, tx, t.ID, false)
		if err != nil {
			return err
		}
		metaMap, err := store.LoadIdpMeta(ctx, tx, t.ID)
		if err != nil {
			return err
		}
		// Xoá bí mật trong OpenBao của các IdP bị gỡ.
		for _, old := range cur {
			if !seen[old.IdpAlias] {
				if m, ok := metaMap[old.IdpAlias]; ok && m.SecretRef != "" && s.Secrets != nil {
					_ = s.Secrets.DeleteIdpSecret(ctx, m.SecretRef)
				}
				delete(metaMap, old.IdpAlias)
			}
		}
		if err := store.DeleteIdps(ctx, tx, t.ID, keep); err != nil {
			return err
		}
		for _, d := range idps {
			ed := d.EmailDomains
			if ed == nil {
				ed = []string{}
			}
			m := metaMap[d.IdpAlias]
			i := slices.IndexFunc(cur, func(x store.Idp) bool { return x.IdpAlias == d.IdpAlias })
			if i >= 0 {
				x := cur[i]
				connChanged := x.Protocol != d.Protocol || x.Issuer != d.Issuer ||
					(d.ClientID != "" && d.ClientID != m.ClientID) ||
					(d.MetadataURL != "" && d.MetadataURL != m.MetadataURL) ||
					d.ClientSecret != ""
				x.Protocol, x.Issuer, x.IsVneid, x.EmailDomains = d.Protocol, d.Issuer, d.IsVneid, ed
				if err := store.UpdateIdp(ctx, tx, &x, a.ID); err != nil {
					return err
				}
				if connChanged {
					m.LastTestOk = nil
				}
			} else {
				if err := store.InsertIdp(ctx, tx, &store.Idp{TenantID: t.ID, IdpAlias: d.IdpAlias, Protocol: d.Protocol, Issuer: d.Issuer,
					IsVneid: d.IsVneid, EmailDomains: ed}, a.ID); err != nil {
					return err
				}
			}
			if d.ClientID != "" {
				m.ClientID = d.ClientID
			}
			if d.MetadataURL != "" {
				m.MetadataURL = d.MetadataURL
			}
			if d.MetadataXML != "" {
				m.MetadataXML = d.MetadataXML
			}
			if len(d.AttributeMapping) > 0 {
				m.AttributeMapping = d.AttributeMapping
			}
			if (d.ClientSecret != "" || d.BindPassword != "") && s.Secrets != nil {
				ref, err := s.Secrets.PutIdpSecret(ctx, t.ID, d.IdpAlias, map[string]string{
					"clientSecret": d.ClientSecret,
					"bindPassword": d.BindPassword,
				})
				if err != nil {
					return err
				}
				m.SecretRef = ref
			} else if m.SecretRef == "" {
				m.SecretRef = IdpSecretRef(t.ID, d.IdpAlias)
			}
			metaMap[d.IdpAlias] = m
		}
		if err := store.SaveIdpMeta(ctx, tx, t.ID, metaMap, a.ID); err != nil {
			return err
		}
	}
	if r, ok := patch["staffSources"]; ok {
		var ss []StaffSource
		if !isNull(r) {
			if err := json.Unmarshal(r, &ss); err != nil {
				return &ValidationError{Fields: []FieldError{{Field: "staffSources", Message: "Kiểu dữ liệu không đúng"}}}
			}
		}
		for _, x := range ss {
			if !slices.Contains(staffSrc, x.Type) {
				return &ValidationError{Fields: []FieldError{{Field: "staffSources", Message: "Nguồn nhân sự không hợp lệ"}}}
			}
		}
		if ss == nil {
			ss = []StaffSource{}
		}
		b, _ := json.Marshal(ss)
		o.StaffSources = b
	}
	return nil
}

// ---------------------------------------------------------------- chuyển trạng thái, huỷ

// Transition đổi trạng thái theo máy trạng thái, ghi tenant_status_log và outbox trong tx (dùng chung cho saga
// cấp phát, kích hoạt, tạm ngừng, chuyển cell ở các task sau). expected là row_version mong đợi.
func (s *Service) Transition(ctx context.Context, tx pgx.Tx, a Actor, t *store.Tenant, expected int, to, reason string) error {
	from := t.Status
	if err := CanTransition(from, to); err != nil {
		return err
	}
	t.Status, t.UpdatedBy = to, a.ID
	if err := store.UpdateTenant(ctx, tx, t, expected); err != nil {
		t.Status = from
		return s.versionErr(err)
	}
	if err := s.logStatus(ctx, tx, a, t.ID, from, to, reason); err != nil {
		return err
	}
	return s.publish(ctx, tx, a, t, "TENANT_STATUS_CHANGED", map[string]any{"fromStatus": from, "toStatus": to})
}

func (s *Service) logStatus(ctx context.Context, tx pgx.Tx, a Actor, id uuid.UUID, from, to, reason string) error {
	var f *string
	if from != "" {
		f = &from
	}
	return store.InsertStatusLog(ctx, tx, &store.StatusLog{TenantID: id, FromStatus: f, ToStatus: to, Reason: reason, ActorID: a.ID})
}

// publish ghi sự kiện C1 (không chứa dữ liệu cá nhân) và NOTIFY làm mới bộ đệm phân giải.
func (s *Service) publish(ctx context.Context, tx pgx.Tx, a Actor, t *store.Tenant, kind string, extra map[string]any) error {
	payload := map[string]any{"tenantId": t.ID, "code": t.Code, "status": t.Status, "tenancyModel": t.TenancyModel, "rowVersion": t.RowVersion}
	for k, v := range extra {
		payload[k] = v
	}
	if _, err := outbox.Append(ctx, tx, outbox.Envelope{TenantID: t.ID, AggregateType: "tenant", AggregateID: t.ID,
		AggregateVersion: int64(t.RowVersion), EventType: kind, Topic: outbox.TopicTenantChanged, Payload: payload,
		TraceParent: a.TraceParent, ActorID: a.ID, Classification: "C1"}); err != nil {
		return err
	}
	return resolve.Notify(ctx, tx, t.ID)
}

// Cancel: cancelConfigTenant, chỉ khi KHOI_TAO; chuyển DA_CHAM_DUT (suy luận), giải phóng tên miền và phần giữ
// chỗ trên cell, giữ bản ghi và nhật ký.
func (s *Service) Cancel(ctx context.Context, tx pgx.Tx, a Actor, id uuid.UUID, expected int) error {
	if !a.PlatformAdmin {
		return ErrForbidden
	}
	t, err := store.GetTenantForUpdate(ctx, tx, id)
	if errors.Is(err, store.ErrNotFound) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if t.RowVersion != expected {
		return &VersionError{Sent: expected, Current: t.RowVersion}
	}
	if t.Status != KhoiTao {
		return &StateError{Status: t.Status}
	}
	if err := store.DeleteDomains(ctx, tx, id, ""); err != nil {
		return err
	}
	if err := store.DeleteIdps(ctx, tx, id, nil); err != nil {
		return err
	}
	if b, err := store.LatestTenantCell(ctx, tx, id); err == nil {
		at := s.Now()
		if !at.After(b.ValidFrom) {
			at = b.ValidFrom.Add(time.Microsecond)
		}
		if b.ValidTo.After(at) {
			if err := store.CloseTenantCell(ctx, tx, b.ID, at, a.ID); err != nil {
				return err
			}
		}
	} else if !errors.Is(err, store.ErrNotFound) {
		return err
	}
	return s.Transition(ctx, tx, a, t, expected, DaChamDut, "Huỷ yêu cầu khởi tạo")
}

// Get: getConfigTenant.
func (s *Service) Get(ctx context.Context, q store.Querier, a Actor, id uuid.UUID) (*ConfigTenant, error) {
	t, err := store.GetTenant(ctx, q, id)
	if errors.Is(err, store.ErrNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if !a.CanRead(t) {
		return nil, ErrForbidden
	}
	v, _, err := s.loadConfig(ctx, q, id, true)
	return v, err
}
