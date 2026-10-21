package tenant

import (
	"context"
	"errors"
	"slices"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"mission/services-go/tenant-registry/internal/store"
)

// ErrAirgap: registry.tenant_cell.tenancy_model (TL-03) chỉ nhận silo, bridge, pool nên chưa gắn được tenant
// AIRGAP vào cell (điểm lệch ghi ở wiki trang 14).
var ErrAirgap = errors.New("tenant: chưa hỗ trợ gắn cell cho mô hình AIRGAP")

// RegistryCreateInput là RegistryTenantCreate (platform.yaml).
type RegistryCreateInput struct {
	Code             string    `json:"code"`
	Name             string    `json:"name"`
	Sector           string    `json:"sector"`
	TenancyModel     string    `json:"tenancyModel"`
	CellID           uuid.UUID `json:"cellId"`
	Flows            []string  `json:"flows"`
	MaxAccounts      int       `json:"maxAccounts"`
	ExpectedAccounts *int      `json:"expectedAccounts"`
	ContractRef      *string   `json:"contractRef"`
	ContractValidTo  *Date     `json:"contractValidTo"`
	OnboardingCode   *string   `json:"onboardingCode"`
	Domains          []struct {
		Domain string `json:"domain"`
		Kind   string `json:"kind"`
	} `json:"domains"`
}

// CreateRegistry: createRegistryTenant. Tạo tenant KHOI_TAO, gắn cell đích và tên miền, ghi nhật ký và outbox.
// Saga tiếp nhận tự động (Keycloak Organization, KEK, CSDL, Policy/UI Pack) do DV-03-04 đảm nhận.
func (s *Service) CreateRegistry(ctx context.Context, tx pgx.Tx, a Actor, in RegistryCreateInput) (*RegistryTenant, error) {
	if !a.PlatformAdmin && !a.Service {
		return nil, ErrForbidden
	}
	in.Code, in.Name = strings.TrimSpace(in.Code), strings.TrimSpace(in.Name)
	v := &validator{}
	if !codeRe.MatchString(in.Code) || len(in.Code) > 32 {
		v.add("code", "Mã tenant chỉ gồm chữ thường, chữ số, gạch dưới, tối đa 32 ký tự")
	}
	if n := len([]rune(in.Name)); n == 0 || n > 255 {
		v.add("name", "Tên tenant bắt buộc, tối đa 255 ký tự")
	}
	if !slices.Contains(sectors, in.Sector) {
		v.add("sector", "Nhóm khách hàng không hợp lệ")
	}
	if !slices.Contains([]string{"SILO", "BRIDGE", "POOL", "AIRGAP"}, in.TenancyModel) {
		v.add("tenancyModel", "Mô hình thuê bao không hợp lệ")
	}
	if in.CellID == uuid.Nil {
		v.add("cellId", "Thiếu cell đích")
	}
	checkFlows(v, "flows", in.Flows)
	if in.MaxAccounts < 1 {
		v.add("maxAccounts", "Giá trị phải lớn hơn 0")
	}
	if in.ExpectedAccounts != nil && *in.ExpectedAccounts < 0 {
		v.add("expectedAccounts", "Giá trị không được âm")
	}
	if in.ContractRef != nil && len([]rune(*in.ContractRef)) > 100 {
		v.add("contractRef", "Tối đa 100 ký tự")
	}
	if in.OnboardingCode != nil && (len(*in.OnboardingCode) == 0 || len(*in.OnboardingCode) > 32) {
		v.add("onboardingCode", "Tối đa 32 ký tự")
	}
	primary := 0
	for i, d := range in.Domains {
		d.Domain = strings.ToLower(strings.TrimSpace(d.Domain))
		in.Domains[i] = d
		if len(d.Domain) > 253 || !domainRe.MatchString(d.Domain) {
			v.add("domains", "Tên miền không hợp lệ")
		}
		if !slices.Contains([]string{"PRIMARY", "ALIAS", "EMBED"}, d.Kind) {
			v.add("domains", "Loại tên miền không hợp lệ")
		}
		if d.Kind == "PRIMARY" {
			primary++
		}
	}
	if primary > 1 {
		v.add("domains", "Chỉ một tên miền chính")
	}
	if err := v.err(); err != nil {
		return nil, err
	}
	if in.TenancyModel == "AIRGAP" {
		return nil, ErrAirgap
	}
	model := Lower(in.TenancyModel)
	exp := in.MaxAccounts
	if in.ExpectedAccounts != nil {
		exp = *in.ExpectedAccounts
	}
	if err := s.Rules.CheckModel(model, exp, in.Sector, in.Code); err != nil {
		return nil, err
	}
	cell, err := store.GetCell(ctx, tx, in.CellID)
	if errors.Is(err, store.ErrNotFound) {
		return nil, &ValidationError{Fields: []FieldError{{Field: "cellId", Message: "Không có cell này"}}}
	}
	if err != nil {
		return nil, err
	}
	if cell.Status != "HOAT_DONG" {
		return nil, &CellUnavailableError{Cell: cell.Code}
	}
	if cell.TenancyModel != in.TenancyModel {
		return nil, &ValidationError{Fields: []FieldError{{Field: "cellId", Message: "Cell không phục vụ mô hình thuê bao đã chọn"}}}
	}
	loads, err := store.ListCells(ctx, tx, store.CellFilter{})
	if err != nil {
		return nil, err
	}
	for _, c := range loads {
		if c.ID == cell.ID {
			l := CellLoad{Code: c.Code, Model: c.TenancyModel, Status: c.Status, Capacity: c.CapacityAccounts, AccountCount: c.AccountCount, Reserved: c.Reserved}
			if !s.Rules.FitsAfterAdding(l, exp) {
				return nil, &CapacityError{Cell: c.Code, Model: model, LoadAfter: LoadAfter(l, exp)}
			}
		}
	}
	if _, err := store.GetTenantByCode(ctx, tx, in.Code); err == nil {
		return nil, &DuplicateError{Field: "code", Value: in.Code}
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	for _, d := range in.Domains {
		if _, err := store.TenantIDByDomain(ctx, tx, d.Domain); err == nil {
			return nil, &DuplicateError{Field: "domain", Value: d.Domain}
		} else if !errors.Is(err, store.ErrNotFound) {
			return nil, err
		}
	}
	code := in.OnboardingCode
	if code == nil {
		year := s.Now().Year()
		seq, err := store.NextOnboardingSeq(ctx, tx, year)
		if err != nil {
			return nil, err
		}
		c := OnboardingCode(year, seq)
		code = &c
	}
	t := &store.Tenant{ID: store.NewID(), Code: in.Code, Name: in.Name, Sector: in.Sector, TenancyModel: in.TenancyModel, Status: KhoiTao,
		ContractRef: in.ContractRef, MaxAccounts: in.MaxAccounts, CreatedBy: a.ID, OnboardingCode: code, Flows: in.Flows,
		ExpectedAccounts: in.ExpectedAccounts, KekRef: KekRef(DefaultKekMode(model), in.Code, cell.Code)}
	if in.ContractValidTo != nil {
		d := in.ContractValidTo.Time
		t.ContractValidTo = &d
	}
	if err := store.InsertTenant(ctx, tx, t); err != nil {
		return nil, mapDup(err, in.Code)
	}
	if err := s.logStatus(ctx, tx, a, t.ID, "", KhoiTao, "Tiếp nhận tenant vào danh bạ, mã hồ sơ "+*code); err != nil {
		return nil, err
	}
	if err := store.InsertTenantCell(ctx, tx, &store.TenantCell{TenantID: t.ID, CellID: cell.ID, DatabaseName: DatabaseName(model, t.Code),
		ValidFrom: s.Now(), TenancyModel: model, DataRegion: cell.PrimaryDC}, a.ID); err != nil {
		return nil, err
	}
	for _, d := range in.Domains {
		if err := store.InsertDomain(ctx, tx, &store.Domain{TenantID: t.ID, Domain: d.Domain, Kind: d.Kind}, a.ID); err != nil {
			return nil, mapDup(err, d.Domain)
		}
	}
	if err := s.publish(ctx, tx, a, t, "TENANT_CREATED", nil); err != nil {
		return nil, err
	}
	return s.loadRegistry(ctx, tx, t.ID)
}

// GetRegistry: getRegistryTenant.
func (s *Service) GetRegistry(ctx context.Context, q store.Querier, a Actor, id uuid.UUID) (*RegistryTenant, error) {
	if !a.PlatformAdmin {
		return nil, ErrForbidden
	}
	return s.loadRegistry(ctx, q, id)
}
