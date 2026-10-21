package tenant

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"

	"mission/services-go/tenant-registry/internal/store"
)

// Các kiểu dưới đây khớp lược đồ của hợp đồng: config.yaml (ConfigTenant, ConfigTenantRow, ConfigTenantPage,
// ConfigTenantSummary, CellLoad, TenantIdp, TenantStaffSource, TenantPolicyBinding, TenantAdminContact,
// TenantStatusLogEntry) và platform.yaml (RegistryTenant, TenantCellBinding, TenantDomain).

// Date là ngày lịch ISO 8601.
type Date struct{ time.Time }

// MarshalJSON ghi yyyy-mm-dd.
func (d Date) MarshalJSON() ([]byte, error) { return json.Marshal(d.Format(time.DateOnly)) }

// UnmarshalJSON đọc yyyy-mm-dd.
func (d *Date) UnmarshalJSON(b []byte) error {
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return err
	}
	t, err := time.Parse(time.DateOnly, s)
	if err != nil {
		return err
	}
	d.Time = t
	return nil
}

func datePtr(t *time.Time) *Date {
	if t == nil {
		return nil
	}
	return &Date{*t}
}

// PolicyBinding là TenantPolicyBinding (packCode, version chỉ đọc do MOD-05 cấp, chưa có ở đây).
type PolicyBinding struct {
	Flow          string    `json:"flow"`
	PackVersionID uuid.UUID `json:"packVersionId"`
}

// StaffSource là TenantStaffSource.
type StaffSource struct {
	Type        string     `json:"type"`
	ConnectorID *uuid.UUID `json:"connectorId,omitempty"`
}

// AdminContact là TenantAdminContact (C2).
type AdminContact struct {
	FullName string `json:"fullName"`
	Email    string `json:"email"`
	Phone    string `json:"phone,omitempty"`
}

// IdpView là TenantIdp (không bao giờ chứa clientSecret hay khoá bí mật).
type IdpView struct {
	ID           *uuid.UUID `json:"id,omitempty"`
	IdpAlias     string     `json:"idpAlias"`
	Protocol     string     `json:"protocol"`
	Issuer       string     `json:"issuer"`
	IsVneid      bool       `json:"isVneid"`
	EmailDomains []string   `json:"emailDomains"`
	Status       string     `json:"status,omitempty"`
	LastTestedAt *time.Time `json:"lastTestedAt,omitempty"`
	LastTestOk   *bool      `json:"lastTestOk,omitempty"`
}

// StatusLogEntry là TenantStatusLogEntry.
type StatusLogEntry struct {
	ID         uuid.UUID `json:"id"`
	FromStatus *string   `json:"fromStatus"`
	ToStatus   string    `json:"toStatus"`
	Reason     string    `json:"reason"`
	ChangedAt  time.Time `json:"changedAt"`
}

// ConfigTenant là chi tiết tenant và cấu hình wizard.
type ConfigTenant struct {
	ID                    uuid.UUID        `json:"id"`
	Code                  string           `json:"code"`
	Name                  string           `json:"name"`
	Sector                string           `json:"sector"`
	OnboardingCode        string           `json:"onboardingCode,omitempty"`
	ContractRef           string           `json:"contractRef,omitempty"`
	ContractValidTo       *Date            `json:"contractValidTo,omitempty"`
	Flows                 []string         `json:"flows"`
	ExpectedAccounts      *int             `json:"expectedAccounts,omitempty"`
	ExpectedUnits         *int             `json:"expectedUnits,omitempty"`
	MaxAccounts           int              `json:"maxAccounts"`
	AccountCount          int              `json:"accountCount"`
	TenancyModel          string           `json:"tenancyModel,omitempty"`
	SuggestedTenancyModel string           `json:"suggestedTenancyModel,omitempty"`
	CellCode              string           `json:"cellCode,omitempty"`
	Domain                string           `json:"domain,omitempty"`
	DataRegion            string           `json:"dataRegion,omitempty"`
	KekMode               string           `json:"kekMode,omitempty"`
	PolicyBindings        []PolicyBinding  `json:"policyBindings"`
	UiPackVersionID       *uuid.UUID       `json:"uiPackVersionId,omitempty"`
	StatusLog             []StatusLogEntry `json:"statusLog"`
	Idps                  []IdpView        `json:"idps"`
	StaffSources          []StaffSource    `json:"staffSources"`
	AdminContact          *AdminContact    `json:"adminContact,omitempty"`
	Status                string           `json:"status"`
	OnboardingStep        *int             `json:"onboardingStep"`
	ProvisionJobID        *string          `json:"provisionJobId"`
	RowVersion            int              `json:"rowVersion"`
	CreatedAt             time.Time        `json:"createdAt"`
	UpdatedAt             time.Time        `json:"updatedAt"`
}

// WithoutC2 trả bản sao không có trường C2 (để lưu vào idempotency_key.response_body, TL-03).
func (c ConfigTenant) WithoutC2() ConfigTenant {
	c.AdminContact = nil
	return c
}

// TenantRow là ConfigTenantRow.
type TenantRow struct {
	ID             uuid.UUID `json:"id"`
	Code           string    `json:"code"`
	Name           string    `json:"name"`
	Domain         string    `json:"domain"`
	CellCode       string    `json:"cellCode"`
	TenancyModel   string    `json:"tenancyModel"`
	AccountCount   int       `json:"accountCount"`
	Status         string    `json:"status"`
	OnboardingStep *int      `json:"onboardingStep"`
}

// CellLoadView là CellLoad.
type CellLoadView struct {
	CellCode         string  `json:"cellCode"`
	AccountCount     int     `json:"accountCount"`
	CapacityAccounts int     `json:"capacityAccounts"`
	LoadPercent      float64 `json:"loadPercent"`
	OverThreshold    bool    `json:"overThreshold"`
}

// OnboardingItem là phần tử summary.onboarding (MSG-01-02).
type OnboardingItem struct {
	TenantID uuid.UUID `json:"tenantId"`
	Name     string    `json:"name"`
	Step     int       `json:"step"`
}

// PendingAction là TenantPendingAction.
type PendingAction struct {
	Kind     string     `json:"kind"`
	TenantID *uuid.UUID `json:"tenantId,omitempty"`
	Message  string     `json:"message"`
}

// SummaryView là ConfigTenantSummary.
type SummaryView struct {
	ActiveTenants     int              `json:"activeTenants"`
	TenantLimit       int              `json:"tenantLimit"`
	TotalAccounts     int64            `json:"totalAccounts"`
	AccountLimit      int64            `json:"accountLimit"`
	OnboardingTenants int              `json:"onboardingTenants"`
	CellCount         int              `json:"cellCount"`
	DataCenterCount   int              `json:"dataCenterCount"`
	CellLoads         []CellLoadView   `json:"cellLoads"`
	Onboarding        []OnboardingItem `json:"onboarding"`
	PendingActions    []PendingAction  `json:"pendingActions"`
}

// Page là ConfigTenantPage.
type Page struct {
	Items      []TenantRow  `json:"items"`
	NextCursor *string      `json:"nextCursor"`
	Total      int          `json:"total"`
	Summary    *SummaryView `json:"summary,omitempty"`
}

// CellBinding là TenantCellBinding (id là mã cell như ví dụ của hợp đồng).
type CellBinding struct {
	ID           uuid.UUID `json:"id"`
	Code         string    `json:"code"`
	DatabaseName string    `json:"databaseName"`
	ValidFrom    time.Time `json:"validFrom"`
}

// DomainView là TenantDomain.
type DomainView struct {
	Domain     string     `json:"domain"`
	Kind       string     `json:"kind"`
	VerifiedAt *time.Time `json:"verifiedAt"`
}

// RegistryTenant là bản ghi danh bạ (không chứa kek_ref).
type RegistryTenant struct {
	ID               uuid.UUID    `json:"id"`
	Code             string       `json:"code"`
	Name             string       `json:"name"`
	Sector           string       `json:"sector"`
	TenancyModel     string       `json:"tenancyModel"`
	Status           string       `json:"status"`
	Flows            []string     `json:"flows"`
	MaxAccounts      int          `json:"maxAccounts"`
	ExpectedAccounts *int         `json:"expectedAccounts,omitempty"`
	AccountCount     int          `json:"accountCount"`
	ContractRef      *string      `json:"contractRef,omitempty"`
	ContractValidTo  *Date        `json:"contractValidTo,omitempty"`
	OnboardingCode   *string      `json:"onboardingCode,omitempty"`
	CurrentCell      *CellBinding `json:"currentCell,omitempty"`
	Domains          []DomainView `json:"domains"`
	RowVersion       int          `json:"rowVersion"`
	CreatedAt        time.Time    `json:"createdAt"`
}

// ---------------------------------------------------------------- dựng view từ CSDL

func (s *Service) progress(ctx context.Context, q store.Querier, t *store.Tenant, o *store.Onboarding) (Progress, error) {
	var p Progress
	if _, err := store.LatestTenantCell(ctx, q, t.ID); err == nil {
		p.CellChosen = true
	} else if !errors.Is(err, store.ErrNotFound) {
		return p, err
	}
	ds, err := store.ListDomains(ctx, q, t.ID)
	if err != nil {
		return p, err
	}
	p.DomainSet = len(ds) > 0
	p.KekSet = true
	idps, err := store.ListIdps(ctx, q, t.ID, false)
	if err != nil {
		return p, err
	}
	p.IdpCount = len(idps)
	if o != nil {
		var ss []StaffSource
		_ = json.Unmarshal(o.StaffSources, &ss)
		p.StaffSourceCnt = len(ss)
		var pb []PolicyBinding
		_ = json.Unmarshal(o.PolicyBindings, &pb)
		p.PacksComplete = len(pb) >= len(t.Flows)
		p.UiPackSet = o.UiPackVersionID != nil
	}
	return p, nil
}

func stepPtr(status string, p Progress) *int {
	if n := Step(status, p); n > 0 {
		return &n
	}
	return nil
}

// loadConfig dựng ConfigTenant; withC2 = giải mã đầu mối quản trị.
func (s *Service) loadConfig(ctx context.Context, q store.Querier, id uuid.UUID, withC2 bool) (*ConfigTenant, *store.Tenant, error) {
	t, err := store.GetTenant(ctx, q, id)
	if errors.Is(err, store.ErrNotFound) {
		return nil, nil, ErrNotFound
	}
	if err != nil {
		return nil, nil, err
	}
	o, err := store.GetOnboarding(ctx, q, id)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return nil, nil, err
	}
	if errors.Is(err, store.ErrNotFound) {
		o = nil
	}
	exp := 0
	if t.ExpectedAccounts != nil {
		exp = *t.ExpectedAccounts
	}
	v := &ConfigTenant{ID: t.ID, Code: t.Code, Name: t.Name, Sector: t.Sector, ContractValidTo: datePtr(t.ContractValidTo),
		Flows: t.Flows, ExpectedAccounts: t.ExpectedAccounts, MaxAccounts: t.MaxAccounts, AccountCount: t.AccountCount,
		TenancyModel: Lower(t.TenancyModel), SuggestedTenancyModel: s.Rules.SuggestModel(exp, t.Sector, t.Code),
		PolicyBindings: []PolicyBinding{}, StatusLog: []StatusLogEntry{}, Idps: []IdpView{}, StaffSources: []StaffSource{},
		Status: t.Status, RowVersion: t.RowVersion, CreatedAt: t.CreatedAt, UpdatedAt: t.UpdatedAt}
	if t.OnboardingCode != nil {
		v.OnboardingCode = *t.OnboardingCode
	}
	if t.ContractRef != nil {
		v.ContractRef = *t.ContractRef
	}
	if tc, err := store.LatestTenantCell(ctx, q, id); err == nil {
		v.CellCode, v.TenancyModel, v.DataRegion = tc.CellCode, tc.TenancyModel, tc.DataRegion
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, nil, err
	}
	ds, err := store.ListDomains(ctx, q, id)
	if err != nil {
		return nil, nil, err
	}
	for _, d := range ds {
		if d.Kind == "PRIMARY" {
			v.Domain = d.Domain
		}
	}
	if o != nil {
		v.ExpectedUnits, v.KekMode, v.UiPackVersionID = o.ExpectedUnits, o.KekMode, o.UiPackVersionID
		if v.DataRegion == "" && o.DataRegion != nil {
			v.DataRegion = *o.DataRegion
		}
		_ = json.Unmarshal(o.PolicyBindings, &v.PolicyBindings)
		_ = json.Unmarshal(o.StaffSources, &v.StaffSources)
		if withC2 && o.AdminContactEnc != nil && s.Crypto != nil && o.DekID != nil {
			plain, err := s.Crypto.Decrypt(o.AdminContactEnc, t.ID[:], *o.DekID)
			if err != nil {
				return nil, nil, err
			}
			var ac AdminContact
			if json.Unmarshal(plain, &ac) == nil {
				v.AdminContact = &ac
			}
		}
	}
	idps, err := store.ListIdps(ctx, q, id, false)
	if err != nil {
		return nil, nil, err
	}
	idpMeta, err := store.LoadIdpMeta(ctx, q, id)
	if err != nil {
		return nil, nil, err
	}
	for _, i := range idps {
		iid := i.ID
		m := idpMeta[i.IdpAlias]
		v.Idps = append(v.Idps, IdpView{ID: &iid, IdpAlias: i.IdpAlias, Protocol: i.Protocol, Issuer: i.Issuer, IsVneid: i.IsVneid,
			EmailDomains: i.EmailDomains, Status: i.Status, LastTestedAt: m.LastTestedAt, LastTestOk: m.LastTestOk})
	}
	logs, err := store.ListStatusLog(ctx, q, id, 50)
	if err != nil {
		return nil, nil, err
	}
	for _, l := range logs {
		v.StatusLog = append(v.StatusLog, StatusLogEntry{ID: l.ID, FromStatus: l.FromStatus, ToStatus: l.ToStatus, Reason: l.Reason, ChangedAt: l.ChangedAt})
	}
	p, err := s.progress(ctx, q, t, o)
	if err != nil {
		return nil, nil, err
	}
	v.OnboardingStep = stepPtr(t.Status, p)
	return v, t, nil
}

// loadRegistry dựng RegistryTenant.
func (s *Service) loadRegistry(ctx context.Context, q store.Querier, id uuid.UUID) (*RegistryTenant, error) {
	t, err := store.GetTenant(ctx, q, id)
	if errors.Is(err, store.ErrNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	v := &RegistryTenant{ID: t.ID, Code: t.Code, Name: t.Name, Sector: t.Sector, TenancyModel: t.TenancyModel, Status: t.Status,
		Flows: t.Flows, MaxAccounts: t.MaxAccounts, ExpectedAccounts: t.ExpectedAccounts, AccountCount: t.AccountCount,
		ContractRef: t.ContractRef, ContractValidTo: datePtr(t.ContractValidTo), OnboardingCode: t.OnboardingCode,
		Domains: []DomainView{}, RowVersion: t.RowVersion, CreatedAt: t.CreatedAt}
	if tc, err := store.CurrentTenantCell(ctx, q, id, s.Now()); err == nil {
		v.CurrentCell = &CellBinding{ID: tc.CellID, Code: tc.CellCode, DatabaseName: tc.DatabaseName, ValidFrom: tc.ValidFrom}
	} else if !errors.Is(err, store.ErrNotFound) {
		return nil, err
	}
	ds, err := store.ListDomains(ctx, q, id)
	if err != nil {
		return nil, err
	}
	for _, d := range ds {
		v.Domains = append(v.Domains, DomainView{Domain: d.Domain, Kind: d.Kind, VerifiedAt: d.VerifiedAt})
	}
	return v, nil
}
