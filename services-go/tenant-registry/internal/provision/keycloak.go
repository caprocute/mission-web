package provision

import (
	"context"
	"net/http"
	"net/url"
	"strings"
)

// KeycloakAdmin hiện thực cổng Keycloak bằng Admin REST API của Keycloak 26 (Organizations, Identity
// Providers, Users) qua mạng quản trị của cell. Token lấy bằng client_credentials của tài khoản dịch vụ có vai
// trò realm-management hẹp (manage-realm, manage-users); không dùng tài khoản quản trị master.
type KeycloakAdmin struct {
	HTTP   *http.Client
	Tokens TokenSource
}

const sysKeycloak = "keycloak-admin"

func (k *KeycloakAdmin) call(ctx context.Context, ep CellEndpoints, method, path string, body any, ok ...int) (*result, error) {
	tok, err := k.Tokens.Token(ctx, ep.TokenURL)
	if err != nil {
		return nil, err
	}
	u := ep.KeycloakURL + "/admin/realms/" + url.PathEscape(ep.Realm) + path
	return do(ctx, k.HTTP, call{system: sysKeycloak, method: method, url: u, body: body, token: tok, ok: ok})
}

type kcOrgDomain struct {
	Name     string `json:"name"`
	Verified bool   `json:"verified"`
}

type kcOrg struct {
	ID          string              `json:"id,omitempty"`
	Name        string              `json:"name"`
	Alias       string              `json:"alias,omitempty"`
	Enabled     bool                `json:"enabled"`
	Description string              `json:"description,omitempty"`
	Attributes  map[string][]string `json:"attributes,omitempty"`
	Domains     []kcOrgDomain       `json:"domains,omitempty"`
}

// EnsureOrganization tạo Organization (idempotent: có id thì kiểm còn tồn tại; trùng thì tìm theo alias).
func (k *KeycloakAdmin) EnsureOrganization(ctx context.Context, ep CellEndpoints, spec OrgSpec) (string, error) {
	if spec.ID != "" {
		if ok, err := k.OrganizationExists(ctx, ep, spec.ID); err != nil || ok {
			return spec.ID, err
		}
	}
	if id, err := k.findOrg(ctx, ep, spec); err != nil || id != "" {
		return id, err
	}
	org := kcOrg{Name: spec.Name, Alias: spec.Alias, Enabled: true, Attributes: spec.Attrs,
		Description: "Tổ chức của tenant " + spec.Alias}
	for _, d := range spec.Domains {
		org.Domains = append(org.Domains, kcOrgDomain{Name: d})
	}
	r, err := k.call(ctx, ep, http.MethodPost, "/organizations", org, http.StatusConflict)
	if err != nil {
		return "", err
	}
	if r.status == http.StatusConflict {
		id, err := k.findOrg(ctx, ep, spec)
		if err == nil && id == "" {
			err = &HTTPError{System: sysKeycloak, Status: http.StatusConflict, Body: "tên hoặc tên miền của tổ chức đã được dùng"}
		}
		return id, err
	}
	if id := lastSegment(r.header.Get("Location")); id != "" {
		return id, nil
	}
	return k.findOrg(ctx, ep, spec)
}

// findOrg tìm Organization theo tên đúng và khớp alias (mã tenant).
func (k *KeycloakAdmin) findOrg(ctx context.Context, ep CellEndpoints, spec OrgSpec) (string, error) {
	q := url.Values{"search": {spec.Name}, "exact": {"true"}, "briefRepresentation": {"true"}}
	r, err := k.call(ctx, ep, http.MethodGet, "/organizations?"+q.Encode(), nil)
	if err != nil {
		return "", err
	}
	var orgs []kcOrg
	if err := decode(r, &orgs); err != nil {
		return "", err
	}
	for _, o := range orgs {
		if o.Alias == spec.Alias || (o.Alias == "" && o.Name == spec.Name) {
			return o.ID, nil
		}
	}
	return "", nil
}

// OrganizationExists kiểm Organization còn tồn tại.
func (k *KeycloakAdmin) OrganizationExists(ctx context.Context, ep CellEndpoints, id string) (bool, error) {
	r, err := k.call(ctx, ep, http.MethodGet, "/organizations/"+url.PathEscape(id), nil, http.StatusNotFound)
	if err != nil {
		return false, err
	}
	return r.status == http.StatusOK, nil
}

// DeleteOrganization xoá Organization (bù trừ); không còn thì coi như xong.
func (k *KeycloakAdmin) DeleteOrganization(ctx context.Context, ep CellEndpoints, id string) error {
	_, err := k.call(ctx, ep, http.MethodDelete, "/organizations/"+url.PathEscape(id), nil, http.StatusNotFound)
	return err
}

type kcIdp struct {
	Alias       string            `json:"alias"`
	DisplayName string            `json:"displayName,omitempty"`
	ProviderID  string            `json:"providerId"`
	Enabled     bool              `json:"enabled"`
	TrustEmail  bool              `json:"trustEmail"`
	HideOnLogin bool              `json:"hideOnLogin"`
	Config      map[string]string `json:"config"`
}

// EnsureIdentityProvider đăng ký IdP liên kết trong realm (chưa có thì tạo). Điểm cuối chi tiết (authorization,
// token, JWKS, SSO) được Keycloak nạp lại từ metadataDescriptorUrl; cấu hình ánh xạ thuộc tính do DV-02.
func (k *KeycloakAdmin) EnsureIdentityProvider(ctx context.Context, ep CellEndpoints, idp IdpSpec) (bool, error) {
	r, err := k.call(ctx, ep, http.MethodGet, "/identity-provider/instances/"+url.PathEscape(idp.Alias), nil, http.StatusNotFound)
	if err != nil {
		return false, err
	}
	if r.status == http.StatusOK {
		return false, nil
	}
	cfg := map[string]string{"syncMode": "IMPORT"}
	if idp.EmailDomain != "" {
		cfg["kc.org.domain"] = idp.EmailDomain
		cfg["kc.org.broker.redirect.mode.email-matches"] = "true"
	}
	body := kcIdp{Alias: idp.Alias, DisplayName: idp.Alias, Enabled: true, Config: cfg}
	switch strings.ToUpper(idp.Protocol) {
	case "SAML":
		body.ProviderID = "saml"
		cfg["entityId"] = idp.Issuer
		cfg["idpEntityId"] = idp.Issuer
		if idp.MetadataURL != "" {
			cfg["metadataDescriptorUrl"] = idp.MetadataURL
		}
		cfg["validateSignature"] = "true"
		cfg["wantAssertionsSigned"] = "true"
	default:
		body.ProviderID = "oidc"
		iss := strings.TrimRight(idp.Issuer, "/")
		cfg["issuer"] = iss
		cfg["metadataDescriptorUrl"] = iss + "/.well-known/openid-configuration"
		cfg["clientId"] = idp.ClientID
		cfg["clientAuthMethod"] = "client_secret_post"
		if idp.ClientSecret != "" {
			cfg["clientSecret"] = idp.ClientSecret
		}
		cfg["pkceEnabled"] = "true"
		cfg["pkceMethod"] = "S256"
		cfg["validateSignature"] = "true"
		cfg["useJwksUrl"] = "true"
	}
	r, err = k.call(ctx, ep, http.MethodPost, "/identity-provider/instances", body, http.StatusConflict)
	if err != nil {
		return false, err
	}
	return r.status != http.StatusConflict, nil
}

// DeleteIdentityProvider gỡ IdP khỏi realm (bù trừ).
func (k *KeycloakAdmin) DeleteIdentityProvider(ctx context.Context, ep CellEndpoints, alias string) error {
	_, err := k.call(ctx, ep, http.MethodDelete, "/identity-provider/instances/"+url.PathEscape(alias), nil, http.StatusNotFound)
	return err
}

// LinkIdentityProvider gắn IdP vào Organization; đã gắn thì coi như xong. Keycloak đọc thân là alias nguyên văn.
func (k *KeycloakAdmin) LinkIdentityProvider(ctx context.Context, ep CellEndpoints, orgID, alias string) error {
	_, err := k.call(ctx, ep, http.MethodPost, "/organizations/"+url.PathEscape(orgID)+"/identity-providers", alias, http.StatusConflict)
	return err
}

type kcUser struct {
	ID            string `json:"id,omitempty"`
	Username      string `json:"username"`
	Email         string `json:"email"`
	FirstName     string `json:"firstName,omitempty"`
	LastName      string `json:"lastName,omitempty"`
	Enabled       bool   `json:"enabled"`
	EmailVerified bool   `json:"emailVerified"`
}

// EnsureUser tạo tài khoản đầu mối quản trị tenant (đã có thì dùng lại, created = false).
func (k *KeycloakAdmin) EnsureUser(ctx context.Context, ep CellEndpoints, u UserSpec) (string, bool, error) {
	if id, err := k.findUser(ctx, ep, u.Email); err != nil || id != "" {
		return id, false, err
	}
	first, last := splitName(u.FullName)
	r, err := k.call(ctx, ep, http.MethodPost, "/users", kcUser{Username: strings.ToLower(u.Email), Email: u.Email,
		FirstName: first, LastName: last, Enabled: true}, http.StatusConflict)
	if err != nil {
		return "", false, err
	}
	if r.status == http.StatusConflict {
		id, err := k.findUser(ctx, ep, u.Email)
		if err == nil && id == "" {
			err = &HTTPError{System: sysKeycloak, Status: http.StatusConflict, Body: "tài khoản đã tồn tại"}
		}
		return id, false, err
	}
	id := lastSegment(r.header.Get("Location"))
	if id == "" {
		id, err = k.findUser(ctx, ep, u.Email)
	}
	return id, true, err
}

func (k *KeycloakAdmin) findUser(ctx context.Context, ep CellEndpoints, email string) (string, error) {
	q := url.Values{"email": {email}, "exact": {"true"}, "briefRepresentation": {"true"}}
	r, err := k.call(ctx, ep, http.MethodGet, "/users?"+q.Encode(), nil)
	if err != nil {
		return "", err
	}
	var us []kcUser
	if err := decode(r, &us); err != nil {
		return "", err
	}
	for _, x := range us {
		if strings.EqualFold(x.Email, email) {
			return x.ID, nil
		}
	}
	return "", nil
}

// DeleteUser xoá tài khoản (chỉ gọi khi saga đã tạo tài khoản này).
func (k *KeycloakAdmin) DeleteUser(ctx context.Context, ep CellEndpoints, id string) error {
	_, err := k.call(ctx, ep, http.MethodDelete, "/users/"+url.PathEscape(id), nil, http.StatusNotFound)
	return err
}

// AddMember thêm tài khoản vào Organization; đã là thành viên thì coi như xong.
func (k *KeycloakAdmin) AddMember(ctx context.Context, ep CellEndpoints, orgID, userID string) error {
	_, err := k.call(ctx, ep, http.MethodPost, "/organizations/"+url.PathEscape(orgID)+"/members", userID, http.StatusConflict)
	return err
}

// splitName tách họ tên tiếng Việt: tên là từ cuối, phần còn lại là họ và tên đệm.
func splitName(full string) (first, last string) {
	parts := strings.Fields(full)
	if len(parts) == 0 {
		return "", ""
	}
	return parts[len(parts)-1], strings.Join(parts[:len(parts)-1], " ")
}
