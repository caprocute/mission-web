package provision

import (
	"context"
	"errors"
	"fmt"
	"math"
	"slices"
	"strings"
	"time"

	"github.com/google/uuid"

	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
)

// Mã bước của saga cấp phát phần 2 (thứ tự theo TL-02a QT-01 bước 4 và TL-01 "Tiếp nhận tenant").
const (
	StepOrganization = "TO_CHUC_DINH_DANH"
	StepDomain       = "TEN_MIEN"
	StepTLS          = "CHUNG_CHI_TLS"
	StepPacks        = "GOI_CHINH_SACH_GIAO_DIEN"
	StepCalendar     = "LICH_NGHI"
	StepQuota        = "HAN_MUC"
	StepSmoke        = "KIEM_THU_KHOI"
)

func defaultSteps() []Step {
	return []Step{orgStep{}, domainStep{}, tlsStep{}, packStep{}, calendarStep{}, quotaStep{}, smokeStep{}}
}

// KeycloakAlias là bí danh IdP trong realm của cell: bí danh trong registry.tenant_idp chỉ duy nhất trong
// một tenant, còn realm dùng chung cho mọi tenant của cell nên gắn tiền tố mã tenant (suy luận, wiki trang 14).
func KeycloakAlias(tenantCode, idpAlias string) string {
	return strings.ReplaceAll(tenantCode, "_", "-") + "-" + idpAlias
}

func splitList(s string) []string {
	if s == "" {
		return nil
	}
	return strings.Split(s, ",")
}

func addToList(s, v string) string {
	l := splitList(s)
	if slices.Contains(l, v) {
		return s
	}
	return strings.Join(append(l, v), ",")
}

// ---------------------------------------------------------------- Organization Keycloak

type orgStep struct{}

func (orgStep) Name() string  { return StepOrganization }
func (orgStep) Label() string { return "tạo tổ chức trong hệ thống đăng nhập" }

// Do tạo Organization của tenant, đăng ký và gắn các IdP đã khai báo (registry.tenant_idp đang hoạt động), tạo
// tài khoản đầu mối quản trị tenant và thêm vào Organization.
func (orgStep) Do(ctx context.Context, r *Run, out map[string]string) error {
	kc := r.s.Keycloak
	var domains []string
	for _, i := range r.Idps {
		for _, d := range i.EmailDomains {
			if !slices.Contains(domains, d) {
				domains = append(domains, d)
			}
		}
	}
	if len(domains) == 0 {
		domains = []string{primaryDomain(r.Domains)}
	}
	id, err := kc.EnsureOrganization(ctx, r.EP, OrgSpec{ID: out["orgId"], Name: r.Tenant.Name, Alias: r.Tenant.Code, Domains: domains,
		Attrs: map[string][]string{"tenant_id": {r.Tenant.ID.String()}, "tenant_code": {r.Tenant.Code}}})
	if err != nil {
		return Transient("Không tạo được tổ chức trong hệ thống đăng nhập", err)
	}
	out["orgId"] = id
	meta, err := store.LoadIdpMeta(ctx, r.s.Pool, r.Tenant.ID)
	if err != nil {
		return err
	}
	for _, i := range r.Idps {
		m := meta[i.IdpAlias]
		spec := IdpSpec{Alias: KeycloakAlias(r.Tenant.Code, i.IdpAlias), Protocol: i.Protocol, Issuer: i.Issuer, ClientID: m.ClientID,
			MetadataURL: m.MetadataURL}
		if len(i.EmailDomains) > 0 {
			spec.EmailDomain = i.EmailDomains[0]
		}
		if m.SecretRef != "" && r.s.Tenants.Secrets != nil {
			if kv, err := r.s.Tenants.Secrets.GetIdpSecret(ctx, m.SecretRef); err == nil {
				spec.ClientSecret = kv["clientSecret"]
			}
		}
		created, err := kc.EnsureIdentityProvider(ctx, r.EP, spec)
		if err != nil {
			return Transient("Không đăng ký được nhà cung cấp định danh "+i.IdpAlias, err)
		}
		if created {
			out["idpCreated"] = addToList(out["idpCreated"], spec.Alias)
		}
		if err := kc.LinkIdentityProvider(ctx, r.EP, id, spec.Alias); err != nil {
			return Transient("Không gắn được nhà cung cấp định danh "+i.IdpAlias+" vào tổ chức", err)
		}
	}
	contact, err := r.s.Tenants.AdminContactOf(ctx, r.s.Pool, r.Tenant.ID)
	if err != nil {
		return Permanent("Chưa có đầu mối quản trị tenant", err)
	}
	uid, created, err := kc.EnsureUser(ctx, r.EP, UserSpec{Email: contact.Email, FullName: contact.FullName})
	if err != nil {
		return Transient("Không tạo được tài khoản đầu mối quản trị tenant", err)
	}
	out["adminUserId"] = uid
	if created {
		out["adminUserCreated"] = "true"
	}
	if err := kc.AddMember(ctx, r.EP, id, uid); err != nil {
		return Transient("Không thêm được đầu mối quản trị vào tổ chức", err)
	}
	return nil
}

// Undo xoá tài khoản (nếu saga đã tạo), IdP do saga đăng ký, rồi xoá Organization.
func (orgStep) Undo(ctx context.Context, r *Run, out map[string]string) error {
	kc := r.s.Keycloak
	if out["adminUserCreated"] == "true" && out["adminUserId"] != "" {
		if err := kc.DeleteUser(ctx, r.EP, out["adminUserId"]); err != nil {
			return err
		}
		delete(out, "adminUserCreated")
	}
	if out["orgId"] != "" {
		if err := kc.DeleteOrganization(ctx, r.EP, out["orgId"]); err != nil {
			return err
		}
	}
	for _, a := range splitList(out["idpCreated"]) {
		if err := kc.DeleteIdentityProvider(ctx, r.EP, a); err != nil {
			return err
		}
	}
	delete(out, "idpCreated")
	return nil
}

// ---------------------------------------------------------------- tên miền

type domainStep struct{}

func (domainStep) Name() string  { return StepDomain }
func (domainStep) Label() string { return "đăng ký tên miền" }

// Do tạo CNAME của mọi tên miền tenant tới điểm vào cell, kiểm đã trỏ đúng rồi ghi verified_at.
func (domainStep) Do(ctx context.Context, r *Run, out map[string]string) error {
	if r.EP.EdgeHost == "" {
		return Permanent("Chưa có điểm vào của cell", nil)
	}
	for _, d := range r.Domains {
		if err := r.s.DNS.EnsureCNAME(ctx, d.Domain, r.EP.EdgeHost); err != nil {
			var se *StepError
			if errors.As(err, &se) {
				return err
			}
			return Transient("Không tạo được bản ghi tên miền "+d.Domain, err)
		}
		out["names"] = addToList(out["names"], d.Domain)
		got, err := r.s.DNS.LookupCNAME(ctx, d.Domain)
		if err != nil {
			return Transient("Không kiểm được tên miền "+d.Domain, err)
		}
		if fqdn(got) != fqdn(r.EP.EdgeHost) {
			return Transient("Tên miền "+d.Domain+" chưa trỏ về điểm vào của cell", nil)
		}
		now := r.s.now().UTC()
		if err := store.SetDomainVerified(ctx, r.s.Pool, d.ID, &now, r.Actor.ID); err != nil {
			return err
		}
	}
	return nil
}

// Undo thu hồi tên miền: xoá bản ghi CNAME đã tạo và bỏ đánh dấu xác minh.
func (domainStep) Undo(ctx context.Context, r *Run, out map[string]string) error {
	for _, n := range splitList(out["names"]) {
		if err := r.s.DNS.RemoveCNAME(ctx, n); err != nil {
			return err
		}
	}
	for _, d := range r.Domains {
		if err := store.SetDomainVerified(ctx, r.s.Pool, d.ID, nil, r.Actor.ID); err != nil {
			return err
		}
	}
	delete(out, "names")
	return nil
}

// ---------------------------------------------------------------- chứng chỉ TLS

type tlsStep struct{}

func (tlsStep) Name() string  { return StepTLS }
func (tlsStep) Label() string { return "cấp chứng chỉ bảo mật cho tên miền" }

// CertName là tên Certificate và Secret TLS của tenant.
func CertName(tenantCode string) string {
	return "tenant-" + strings.ReplaceAll(tenantCode, "_", "-") + "-tls"
}

func (tlsStep) Do(ctx context.Context, r *Run, out map[string]string) error {
	name := CertName(r.Tenant.Code)
	out["certName"] = name
	names := make([]string, 0, len(r.Domains))
	for _, d := range r.Domains {
		names = append(names, d.Domain)
	}
	ref, err := r.s.Certs.Issue(ctx, name, names)
	if err != nil {
		var se *StepError
		if errors.As(err, &se) {
			return err
		}
		return Transient("Chưa cấp được chứng chỉ bảo mật cho tên miền", err)
	}
	out["certRef"] = ref
	for _, d := range r.Domains {
		if err := store.SetDomainCert(ctx, r.s.Pool, d.ID, &ref, r.Actor.ID); err != nil {
			return err
		}
	}
	return nil
}

func (tlsStep) Undo(ctx context.Context, r *Run, out map[string]string) error {
	if out["certName"] != "" {
		if err := r.s.Certs.Revoke(ctx, r.s.Certs.Ref(out["certName"])); err != nil {
			return err
		}
	}
	for _, d := range r.Domains {
		if err := store.SetDomainCert(ctx, r.s.Pool, d.ID, nil, r.Actor.ID); err != nil {
			return err
		}
	}
	delete(out, "certRef")
	return nil
}

// ---------------------------------------------------------------- Policy Pack và UI Pack

type packStep struct{}

func (packStep) Name() string  { return StepPacks }
func (packStep) Label() string { return "gắn gói chính sách và gói giao diện" }

// Do gắn Policy Pack theo luồng (BR-01-03 do MOD-05 kiểm; BR-01-04 kiểm lại ở đây) và UI Pack (mặc định khối
// nếu không chọn).
func (packStep) Do(ctx context.Context, r *Run, out map[string]string) error {
	pbs := tenant.PolicyBindingsOf(r.Onboarding)
	bindings := make([]PackBinding, 0, len(pbs))
	seen := map[string]bool{}
	for _, b := range pbs {
		bindings = append(bindings, PackBinding{Flow: b.Flow, PackVersionID: b.PackVersionID})
		seen[b.Flow] = true
	}
	for _, f := range r.Tenant.Flows {
		if !seen[f] {
			return Permanent("Chưa gắn đủ gói chính sách cho mỗi luồng", nil)
		}
	}
	if err := r.s.Core.BindPolicyPacks(ctx, r.EP, r.Tenant.ID, bindings); err != nil {
		var pn *PackNotReadyError
		if errors.As(err, &pn) {
			return &StepError{Reason: pn.Error(), Msg: "MSG-01-05", Permanent: true, Err: err}
		}
		return Transient("Không gắn được gói chính sách", err)
	}
	out["policy"] = "true"
	var uiID *uuid.UUID
	if r.Onboarding != nil {
		uiID = r.Onboarding.UiPackVersionID
	}
	if err := r.s.Core.AssignUiPack(ctx, r.EP, r.Tenant.ID, r.Tenant.Sector, uiID); err != nil {
		return Transient("Không gắn được gói giao diện", err)
	}
	out["ui"] = "true"
	return nil
}

func (packStep) Undo(ctx context.Context, r *Run, out map[string]string) error {
	if err := r.s.Core.UnassignUiPack(ctx, r.EP, r.Tenant.ID); err != nil {
		return err
	}
	if err := r.s.Core.UnbindPolicyPacks(ctx, r.EP, r.Tenant.ID); err != nil {
		return err
	}
	delete(out, "ui")
	delete(out, "policy")
	return nil
}

// ---------------------------------------------------------------- lịch nghỉ mặc định

type calendarStep struct{}

func (calendarStep) Name() string  { return StepCalendar }
func (calendarStep) Label() string { return "tạo lịch nghỉ mặc định" }

func (calendarStep) Do(ctx context.Context, r *Run, out map[string]string) error {
	year := r.s.now().Year()
	if err := r.s.Core.CreateDefaultCalendar(ctx, r.EP, r.Tenant.ID, year); err != nil {
		return Transient("Không tạo được lịch nghỉ mặc định", err)
	}
	out["year"] = fmt.Sprint(year)
	return nil
}

func (calendarStep) Undo(ctx context.Context, r *Run, out map[string]string) error {
	return r.s.Core.DeleteDefaultCalendar(ctx, r.EP, r.Tenant.ID)
}

// ---------------------------------------------------------------- hạn mức cổng

type quotaStep struct{}

func (quotaStep) Name() string  { return StepQuota }
func (quotaStep) Label() string { return "cấu hình hạn mức sử dụng" }

// APIRateLimit: TL-01 "Cô lập tài nguyên": 2 request/giây mỗi 100 tài khoản, tối thiểu 20.
func APIRateLimit(accounts int) int64 {
	return int64(math.Max(20, math.Ceil(float64(accounts)*2/100)))
}

func (quotaStep) Do(ctx context.Context, r *Run, out map[string]string) error {
	acc := r.Tenant.MaxAccounts
	today := r.s.now().UTC().Truncate(24 * time.Hour)
	for _, q := range []store.Quota{
		{TenantID: r.Tenant.ID, Key: "API_RPS", Limit: APIRateLimit(acc), EffectiveFrom: today},
		{TenantID: r.Tenant.ID, Key: "ACCOUNTS", Limit: int64(acc), EffectiveFrom: today},
	} {
		if err := store.UpsertQuota(ctx, r.s.Pool, q, r.Actor.ID); err != nil {
			return err
		}
	}
	out["keys"] = "API_RPS,ACCOUNTS"
	return nil
}

func (quotaStep) Undo(ctx context.Context, r *Run, _ map[string]string) error {
	return store.CloseQuotas(ctx, r.s.Pool, r.Tenant.ID, r.s.now().UTC(), r.Actor.ID)
}

// ---------------------------------------------------------------- kiểm thử khói

type smokeStep struct{}

func (smokeStep) Name() string  { return StepSmoke }
func (smokeStep) Label() string { return "kiểm thử khói" }

// Do kiểm lại tài nguyên các bước trước đã sẵn sàng trên cell: Organization còn, CNAME đúng, chứng chỉ sẵn
// sàng, gói chính sách đã gắn đủ luồng. Đăng nhập bằng tài khoản kiểm thử và đọc cây tổ chức (TL-01) cần cây
// tổ chức đã nhập ở QT-01 bước 6, nên chuyển sang kiểm tra tổng thể bước 9 (suy luận).
func (smokeStep) Do(ctx context.Context, r *Run, _ map[string]string) error {
	org := r.Out(StepOrganization)["orgId"]
	ok, err := r.s.Keycloak.OrganizationExists(ctx, r.EP, org)
	if err != nil || !ok {
		return Transient("Kiểm thử khói: không thấy tổ chức trong hệ thống đăng nhập", err)
	}
	if got, err := r.s.DNS.LookupCNAME(ctx, primaryDomain(r.Domains)); err != nil || fqdn(got) != fqdn(r.EP.EdgeHost) {
		return Transient("Kiểm thử khói: tên miền chưa trỏ về điểm vào của cell", err)
	}
	if ok, err := r.s.Certs.Ready(ctx, r.Out(StepTLS)["certRef"]); err != nil || !ok {
		return Transient("Kiểm thử khói: chứng chỉ bảo mật chưa sẵn sàng", err)
	}
	packs, err := r.s.Core.PolicyPacks(ctx, r.EP, r.Tenant.ID)
	if err != nil {
		return Transient("Kiểm thử khói: không đọc được gói chính sách đã gắn", err)
	}
	for _, f := range r.Tenant.Flows {
		if !slices.ContainsFunc(packs, func(p PackState) bool { return p.Flow == f }) {
			return Transient("Kiểm thử khói: thiếu gói chính sách của luồng", nil)
		}
	}
	return nil
}

func (smokeStep) Undo(context.Context, *Run, map[string]string) error { return nil }
