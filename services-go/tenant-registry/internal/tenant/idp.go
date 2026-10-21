package tenant

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/store"
)

// SecretStore quản lý bí mật IdP (clientSecret, bindPassword, khoá ký) trong OpenBao KV v2.
// Tuyệt đối không lưu bí mật dạng rõ trong bảng registry.tenant_idp hay nhật ký.
type SecretStore interface {
	PutIdpSecret(ctx context.Context, tenantID uuid.UUID, idpAlias string, data map[string]string) (secretRef string, err error)
	GetIdpSecret(ctx context.Context, secretRef string) (map[string]string, error)
	DeleteIdpSecret(ctx context.Context, secretRef string) error
}

// MemorySecretStore lưu bí mật trong bộ nhớ phục vụ dev/test hoặc làm bộ nhớ đệm khi OpenBao chưa cấu hình HTTP.
type MemorySecretStore struct {
	mu   sync.RWMutex
	data map[string]map[string]string
}

// NewMemorySecretStore khởi tạo kho bí mật trong bộ nhớ.
func NewMemorySecretStore() *MemorySecretStore {
	return &MemorySecretStore{data: map[string]map[string]string{}}
}

// IdpSecretRef trả đường dẫn chuẩn của bí mật IdP trong OpenBao KV v2.
func IdpSecretRef(tenantID uuid.UUID, idpAlias string) string {
	return fmt.Sprintf("openbao://secret/data/mission/tenants/%s/idp/%s", tenantID.String(), strings.ToLower(strings.TrimSpace(idpAlias)))
}

func (m *MemorySecretStore) PutIdpSecret(_ context.Context, tenantID uuid.UUID, idpAlias string, kv map[string]string) (string, error) {
	ref := IdpSecretRef(tenantID, idpAlias)
	cp := make(map[string]string, len(kv))
	for k, v := range kv {
		if v != "" {
			cp[k] = v
		}
	}
	m.mu.Lock()
	m.data[ref] = cp
	m.mu.Unlock()
	return ref, nil
}

func (m *MemorySecretStore) GetIdpSecret(_ context.Context, secretRef string) (map[string]string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	kv, ok := m.data[secretRef]
	if !ok {
		return map[string]string{}, nil
	}
	cp := make(map[string]string, len(kv))
	for k, v := range kv {
		cp[k] = v
	}
	return cp, nil
}

func (m *MemorySecretStore) DeleteIdpSecret(_ context.Context, secretRef string) error {
	m.mu.Lock()
	delete(m.data, secretRef)
	m.mu.Unlock()
	return nil
}

// OpenBaoSecretStore gọi API KV v2 của OpenBao (/v1/secret/data/mission/tenants/{tenantId}/idp/{idpAlias}).
type OpenBaoSecretStore struct {
	BaseURL string
	Token   string
	Client  *http.Client
	mem     *MemorySecretStore
}

// NewOpenBaoSecretStore tạo client OpenBao KV v2 kèm bộ nhớ dự phòng cho dev.
func NewOpenBaoSecretStore(baseURL, token string, client *http.Client) *OpenBaoSecretStore {
	if client == nil {
		client = &http.Client{Timeout: 5 * time.Second}
	}
	return &OpenBaoSecretStore{
		BaseURL: strings.TrimRight(strings.TrimSpace(baseURL), "/"),
		Token:   strings.TrimSpace(token),
		Client:  client,
		mem:     NewMemorySecretStore(),
	}
}

func openBaoAPIPath(secretRef string) string {
	const prefix = "openbao://"
	p := strings.TrimPrefix(secretRef, prefix)
	return "/v1/" + strings.TrimLeft(p, "/")
}

func (s *OpenBaoSecretStore) PutIdpSecret(ctx context.Context, tenantID uuid.UUID, idpAlias string, kv map[string]string) (string, error) {
	ref, err := s.mem.PutIdpSecret(ctx, tenantID, idpAlias, kv)
	if err != nil || s.BaseURL == "" {
		return ref, err
	}
	body, err := json.Marshal(map[string]any{"data": kv})
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.BaseURL+openBaoAPIPath(ref), bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	if s.Token != "" {
		req.Header.Set("X-Vault-Token", s.Token)
	}
	resp, err := s.Client.Do(req)
	if err != nil {
		return "", fmt.Errorf("không ghi được bí mật IdP vào OpenBao: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return "", fmt.Errorf("OpenBao trả mã trạng thái %d khi ghi bí mật IdP", resp.StatusCode)
	}
	return ref, nil
}

func (s *OpenBaoSecretStore) GetIdpSecret(ctx context.Context, secretRef string) (map[string]string, error) {
	if s.BaseURL == "" {
		return s.mem.GetIdpSecret(ctx, secretRef)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.BaseURL+openBaoAPIPath(secretRef), nil)
	if err != nil {
		return nil, err
	}
	if s.Token != "" {
		req.Header.Set("X-Vault-Token", s.Token)
	}
	resp, err := s.Client.Do(req)
	if err != nil {
		return s.mem.GetIdpSecret(ctx, secretRef)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return s.mem.GetIdpSecret(ctx, secretRef)
	}
	if resp.StatusCode >= 300 {
		return nil, fmt.Errorf("OpenBao trả mã trạng thái %d khi đọc bí mật IdP", resp.StatusCode)
	}
	var env struct {
		Data struct {
			Data map[string]string `json:"data"`
		} `json:"data"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64*1024)).Decode(&env); err != nil {
		return nil, err
	}
	return env.Data.Data, nil
}

func (s *OpenBaoSecretStore) DeleteIdpSecret(ctx context.Context, secretRef string) error {
	_ = s.mem.DeleteIdpSecret(ctx, secretRef)
	if s.BaseURL == "" {
		return nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodDelete, s.BaseURL+openBaoAPIPath(secretRef), nil)
	if err != nil {
		return err
	}
	if s.Token != "" {
		req.Header.Set("X-Vault-Token", s.Token)
	}
	resp, err := s.Client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return nil
}

// ---------------------------------------------------------------- Chống SSRF

var blockedCIDRs = mustParseCIDRs(
	"0.0.0.0/8",
	"10.0.0.0/8",
	"100.64.0.0/10",
	"127.0.0.0/8",
	"169.254.0.0/16",
	"172.16.0.0/12",
	"192.0.0.0/24",
	"192.0.2.0/24",
	"192.168.0.0/16",
	"198.18.0.0/15",
	"198.51.100.0/24",
	"203.0.113.0/24",
	"224.0.0.0/4",
	"240.0.0.0/4",
	"255.255.255.255/32",
	"::/128",
	"::1/128",
	"fc00::/7",
	"fe80::/10",
	"ff00::/8",
)

func mustParseCIDRs(raw ...string) []netip.Prefix {
	out := make([]netip.Prefix, 0, len(raw))
	for _, s := range raw {
		out = append(out, netip.MustParsePrefix(s))
	}
	return out
}

// IsBlockedIP kiểm tra địa chỉ IP có thuộc dải nội bộ, loopback, link-local hay metadata đám mây không.
func IsBlockedIP(addr netip.Addr) (bool, string) {
	if !addr.IsValid() {
		return true, "địa chỉ IP không hợp lệ"
	}
	u := addr.Unmap()
	if u.IsLoopback() {
		return true, "địa chỉ loopback (127.0.0.0/8, ::1) bị chặn"
	}
	if u.IsLinkLocalUnicast() || u.IsLinkLocalMulticast() {
		return true, "địa chỉ link-local/metadata (169.254.0.0/16, fe80::/10) bị chặn"
	}
	if u.IsPrivate() {
		return true, "địa chỉ mạng nội bộ RFC 1918 (10/8, 172.16/12, 192.168/16, fc00::/7) bị chặn"
	}
	if u.IsMulticast() || u.IsUnspecified() {
		return true, "địa chỉ quảng bá hoặc chưa xác định bị chặn"
	}
	for _, p := range blockedCIDRs {
		if p.Contains(u) {
			return true, fmt.Sprintf("địa chỉ thuộc dải bị chặn %s", p.String())
		}
	}
	return false, ""
}

// IsBlockedHost kiểm tra tên miền nội bộ cụm Kubernetes hoặc máy cục bộ.
func IsBlockedHost(host string) (bool, string) {
	h := strings.ToLower(strings.TrimSpace(strings.TrimSuffix(host, ".")))
	if h == "" {
		return true, "thiếu tên máy chủ"
	}
	if h == "localhost" || strings.HasSuffix(h, ".localhost") ||
		strings.HasSuffix(h, ".local") ||
		strings.HasSuffix(h, ".internal") ||
		strings.HasSuffix(h, ".cluster.local") ||
		strings.HasSuffix(h, ".svc") ||
		h == "metadata" || h == "metadata.google.internal" {
		return true, fmt.Sprintf("tên máy chủ nội bộ %q bị chặn để chống SSRF", host)
	}
	if addr, err := netip.ParseAddr(h); err == nil {
		return IsBlockedIP(addr)
	}
	return false, ""
}

// ValidateExternalURL kiểm tra cú pháp URL, giao thức bắt buộc (https hoặc ldaps) và chặn đích nội bộ tĩnh.
func ValidateExternalURL(rawURL string, allowedSchemes ...string) (*url.URL, error) {
	s := strings.TrimSpace(rawURL)
	if s == "" {
		return nil, errors.New("địa chỉ URL không được để trống")
	}
	u, err := url.Parse(s)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return nil, fmt.Errorf("địa chỉ URL %q không đúng định dạng", rawURL)
	}
	scheme := strings.ToLower(u.Scheme)
	allowed := false
	for _, a := range allowedSchemes {
		if scheme == strings.ToLower(a) {
			allowed = true
			break
		}
	}
	if !allowed {
		if scheme == "ldap" {
			return nil, errors.New("kết nối LDAP bắt buộc sử dụng LDAPS (TLS); không chấp nhận ldap:// không mã hoá")
		}
		if scheme == "http" {
			return nil, errors.New("kết nối IdP bắt buộc sử dụng HTTPS; không chấp nhận http:// không mã hoá")
		}
		return nil, fmt.Errorf("giao thức %q không nằm trong tập cho phép (%s)", u.Scheme, strings.Join(allowedSchemes, ", "))
	}
	if u.User != nil {
		return nil, errors.New("URL không được chứa thông tin xác thực trên thanh địa chỉ")
	}
	host := u.Hostname()
	if blocked, reason := IsBlockedHost(host); blocked {
		return nil, fmt.Errorf("chặn kết nối SSRF tới %q: %s", host, reason)
	}
	return u, nil
}

// ---------------------------------------------------------------- Kiểm kết nối IdP (OIDC, SAML, LDAP)

// IdpProbeInput là đầu vào đầy đủ cho một lần thử kết nối IdP.
type IdpProbeInput struct {
	ID               *uuid.UUID        `json:"id,omitempty"`
	IdpAlias         string            `json:"idpAlias"`
	Protocol         string            `json:"protocol"`
	Issuer           string            `json:"issuer"`
	IsVneid          bool              `json:"isVneid"`
	EmailDomains     []string          `json:"emailDomains,omitempty"`
	ClientID         string            `json:"clientId,omitempty"`
	ClientSecret     string            `json:"clientSecret,omitempty"`
	MetadataURL      string            `json:"metadataUrl,omitempty"`
	MetadataXML      string            `json:"metadataXml,omitempty"`
	LdapURL          string            `json:"ldapUrl,omitempty"`
	BindDN           string            `json:"bindDn,omitempty"`
	BindPassword     string            `json:"bindPassword,omitempty"`
	BaseDN           string            `json:"baseDn,omitempty"`
	UserFilter       string            `json:"userFilter,omitempty"`
	AttributeMapping map[string]string `json:"attributeMapping,omitempty"`
	Status           string            `json:"status,omitempty"`
	LastTestedAt     *time.Time        `json:"lastTestedAt,omitempty"`
	LastTestOk       *bool             `json:"lastTestOk,omitempty"`
}

// IdpTestRequest là thân yêu cầu POST /api/v1/config/tenants/{id}/idp/test.
type IdpTestRequest struct {
	IdpAlias string         `json:"idpAlias,omitempty"`
	Idp      *IdpProbeInput `json:"idp,omitempty"`
}

// IdpTestResult ánh xạ TenantIdpTestResult trong hợp đồng OpenAPI.
type IdpTestResult struct {
	Success   bool   `json:"success"`
	Protocol  string `json:"protocol,omitempty"`
	LatencyMs int    `json:"latencyMs"`
	Message   string `json:"message"`
	CheckedAt string `json:"checkedAt"`
}

// IdpProber thực thi kiểm tra kết nối OIDC, SAML, LDAPS với bộ lọc chống SSRF.
type IdpProber struct {
	Timeout time.Duration
	// LookupIP phân giải tên miền sang danh sách địa chỉ IP; mặc định dùng net.DefaultResolver.
	LookupIP func(ctx context.Context, host string) ([]netip.Addr, error)
	// DialContext tạo kết nối TCP tới địa chỉ IP đã qua kiểm tra SSRF.
	DialContext func(ctx context.Context, network, addr string) (net.Conn, error)
	// TLSConfig tuỳ chỉnh cấu hình TLS (ví dụ RootCAs trong kiểm thử).
	TLSConfig *tls.Config
	// AllowedHosts danh sách tên miền công khai cho phép theo môi trường (rỗng = mọi tên miền công khai hợp lệ).
	AllowedHosts []string
}

// NewIdpProber tạo bộ kiểm kết nối IdP mặc định (timeout 8 giây, chống SSRF).
func NewIdpProber() *IdpProber {
	return &IdpProber{Timeout: 8 * time.Second}
}

func vErr(field, msg string) *ValidationError {
	return &ValidationError{Fields: []FieldError{{Field: field, Message: msg}}}
}

func (p *IdpProber) resolveAndValidate(ctx context.Context, host string) ([]netip.Addr, error) {
	if blocked, reason := IsBlockedHost(host); blocked {
		return nil, vErr("issuer", fmt.Sprintf("Chặn kết nối SSRF tới %q: %s", host, reason))
	}
	if len(p.AllowedHosts) > 0 {
		h := strings.ToLower(strings.TrimSpace(host))
		matched := false
		for _, allow := range p.AllowedHosts {
			a := strings.ToLower(strings.TrimSpace(allow))
			if h == a || (strings.HasPrefix(a, "*.") && strings.HasSuffix(h, a[1:])) {
				matched = true
				break
			}
		}
		if !matched {
			return nil, vErr("issuer", fmt.Sprintf("Tên máy chủ %q không nằm trong danh sách cho phép của môi trường", host))
		}
	}
	if addr, err := netip.ParseAddr(host); err == nil {
		if blocked, reason := IsBlockedIP(addr); blocked {
			return nil, vErr("issuer", fmt.Sprintf("Chặn kết nối SSRF tới %s: %s", addr.String(), reason))
		}
		return []netip.Addr{addr}, nil
	}
	lookup := p.LookupIP
	if lookup == nil {
		lookup = func(ctx context.Context, h string) ([]netip.Addr, error) {
			ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", h)
			return ips, err
		}
	}
	addrs, err := lookup(ctx, host)
	if err != nil || len(addrs) == 0 {
		return nil, fmt.Errorf("không phân giải được tên miền %q", host)
	}
	for _, a := range addrs {
		if blocked, reason := IsBlockedIP(a); blocked {
			return nil, vErr("issuer", fmt.Sprintf("Chặn kết nối SSRF: tên miền %q phân giải ra IP nội bộ %s (%s)", host, a.String(), reason))
		}
	}
	return addrs, nil
}

func (p *IdpProber) httpClient() *http.Client {
	timeout := p.Timeout
	if timeout <= 0 {
		timeout = 8 * time.Second
	}
	baseDial := p.DialContext
	if baseDial == nil {
		d := &net.Dialer{Timeout: timeout}
		baseDial = d.DialContext
	}
	tr := &http.Transport{
		TLSClientConfig: p.TLSConfig,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			addrs, err := p.resolveAndValidate(ctx, host)
			if err != nil {
				return nil, err
			}
			return baseDial(ctx, network, net.JoinHostPort(addrs[0].String(), port))
		},
	}
	return &http.Client{
		Timeout:   timeout,
		Transport: tr,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 3 {
				return errors.New("quá số lần chuyển hướng cho phép")
			}
			_, err := ValidateExternalURL(req.URL.String(), "https")
			return err
		},
	}
}

// Probe thực hiện kiểm tra kết nối theo giao thức OIDC, SAML hoặc LDAP.
func (p *IdpProber) Probe(ctx context.Context, in IdpProbeInput) (IdpTestResult, error) {
	start := time.Now()
	nowStr := start.UTC().Format(time.RFC3339)
	proto := strings.ToUpper(strings.TrimSpace(in.Protocol))
	if proto == "" {
		return IdpTestResult{}, vErr("protocol", "Giao thức IdP không được để trống")
	}
	outProto := ""
	if proto == "OIDC" || proto == "SAML" {
		outProto = proto
	}
	var msg string
	var err error
	switch proto {
	case "OIDC":
		msg, err = p.probeOIDC(ctx, in)
	case "SAML":
		msg, err = p.probeSAML(ctx, in)
	case "LDAP":
		msg, err = p.probeLDAP(ctx, in)
	default:
		return IdpTestResult{}, vErr("protocol", "Giao thức IdP phải thuộc tập OIDC, SAML hoặc LDAP")
	}
	latency := int(time.Since(start).Milliseconds())
	if latency < 0 {
		latency = 0
	}
	if err != nil {
		var ve *ValidationError
		if errors.As(err, &ve) {
			detail := ve.Error()
			if len(ve.Fields) > 0 {
				detail = ve.Fields[0].Message
			}
			return IdpTestResult{
				Success:   false,
				Protocol:  outProto,
				LatencyMs: latency,
				Message:   detail,
				CheckedAt: nowStr,
			}, ve
		}
		return IdpTestResult{
			Success:   false,
			Protocol:  outProto,
			LatencyMs: latency,
			Message:   err.Error(),
			CheckedAt: nowStr,
		}, nil
	}
	return IdpTestResult{
		Success:   true,
		Protocol:  outProto,
		LatencyMs: latency,
		Message:   msg,
		CheckedAt: nowStr,
	}, nil
}

func (p *IdpProber) probeOIDC(ctx context.Context, in IdpProbeInput) (string, error) {
	u, err := ValidateExternalURL(in.Issuer, "https")
	if err != nil {
		return "", vErr("issuer", err.Error())
	}
	if _, err := p.resolveAndValidate(ctx, u.Hostname()); err != nil {
		return "", err
	}
	discURL := strings.TrimRight(in.Issuer, "/") + "/.well-known/openid-configuration"
	client := p.httpClient()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, discURL, nil)
	if err != nil {
		return "", err
	}
	resp, err := client.Do(req)
	if err != nil {
		var ve *ValidationError
		if errors.As(err, &ve) {
			return "", ve
		}
		return "", fmt.Errorf("Không kết nối được tài liệu khám phá OIDC: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("Máy chủ OIDC trả mã trạng thái %d tại điểm cuối khám phá", resp.StatusCode)
	}
	var doc struct {
		Issuer                string `json:"issuer"`
		AuthorizationEndpoint string `json:"authorization_endpoint"`
		TokenEndpoint         string `json:"token_endpoint"`
		JwksURI               string `json:"jwks_uri"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&doc); err != nil {
		return "", errors.New("Tài liệu khám phá OIDC không đúng định dạng JSON")
	}
	if strings.TrimRight(doc.Issuer, "/") != strings.TrimRight(in.Issuer, "/") {
		return "", fmt.Errorf("Trường issuer trong tài liệu khám phá (%q) không khớp với cấu hình (%q)", doc.Issuer, in.Issuer)
	}
	for name, ep := range map[string]string{
		"authorization_endpoint": doc.AuthorizationEndpoint,
		"token_endpoint":         doc.TokenEndpoint,
		"jwks_uri":               doc.JwksURI,
	} {
		epURL, err := ValidateExternalURL(ep, "https")
		if err != nil {
			return "", fmt.Errorf("Điểm cuối %s trong tài liệu OIDC không hợp lệ: %v", name, err)
		}
		if _, err := p.resolveAndValidate(ctx, epURL.Hostname()); err != nil {
			return "", err
		}
	}
	jreq, err := http.NewRequestWithContext(ctx, http.MethodGet, doc.JwksURI, nil)
	if err != nil {
		return "", err
	}
	jresp, err := client.Do(jreq)
	if err != nil {
		var ve *ValidationError
		if errors.As(err, &ve) {
			return "", ve
		}
		return "", fmt.Errorf("Không tải được bộ khoá ký JWKS của IdP: %v", err)
	}
	defer jresp.Body.Close()
	if jresp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("Điểm cuối JWKS trả mã trạng thái %d", jresp.StatusCode)
	}
	var jwks struct {
		Keys []struct {
			Kty string   `json:"kty"`
			Kid string   `json:"kid"`
			N   string   `json:"n"`
			Crv string   `json:"crv"`
			X5c []string `json:"x5c"`
		} `json:"keys"`
	}
	if err := json.NewDecoder(io.LimitReader(jresp.Body, 1<<20)).Decode(&jwks); err != nil {
		return "", errors.New("Tài liệu JWKS không đúng định dạng JSON")
	}
	validKeys := 0
	for _, k := range jwks.Keys {
		if k.Kty != "" && (k.N != "" || k.Crv != "" || len(k.X5c) > 0) {
			validKeys++
		}
	}
	if validKeys == 0 {
		return "", errors.New("Bộ khoá JWKS không có khoá ký công khai hợp lệ")
	}
	return "Đã lấy được tài liệu khám phá OIDC và khoá ký của IdP.", nil
}

type samlEntityDescriptor struct {
	XMLName          xml.Name `xml:"EntityDescriptor"`
	EntityID         string   `xml:"entityID,attr"`
	Signature        *samlSig `xml:"Signature"`
	IDPSSODescriptor struct {
		Signature           *samlSig `xml:"Signature"`
		SingleSignOnService []struct {
			Binding  string `xml:"Binding,attr"`
			Location string `xml:"Location,attr"`
		} `xml:"SingleSignOnService"`
		KeyDescriptor []struct {
			Use     string `xml:"use,attr"`
			KeyInfo struct {
				X509Data struct {
					X509Certificate []string `xml:"X509Certificate"`
				} `xml:"X509Data"`
			} `xml:"KeyInfo"`
		} `xml:"KeyDescriptor"`
	} `xml:"IDPSSODescriptor"`
}

type samlSig struct {
	SignedInfo struct {
		SignatureMethod struct {
			Algorithm string `xml:"Algorithm,attr"`
		} `xml:"SignatureMethod"`
	} `xml:"SignedInfo"`
	SignatureValue string `xml:"SignatureValue"`
}

func (p *IdpProber) probeSAML(ctx context.Context, in IdpProbeInput) (string, error) {
	if _, err := ValidateExternalURL(in.Issuer, "https"); err != nil {
		return "", vErr("issuer", err.Error())
	}
	var rawXML []byte
	metaURL := strings.TrimSpace(in.MetadataURL)
	if metaURL == "" && strings.TrimSpace(in.MetadataXML) == "" {
		metaURL = in.Issuer
	}
	if metaURL != "" {
		u, err := ValidateExternalURL(metaURL, "https")
		if err != nil {
			return "", vErr("metadataUrl", err.Error())
		}
		if _, err := p.resolveAndValidate(ctx, u.Hostname()); err != nil {
			return "", err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, metaURL, nil)
		if err != nil {
			return "", err
		}
		resp, err := p.httpClient().Do(req)
		if err != nil {
			var ve *ValidationError
			if errors.As(err, &ve) {
				return "", ve
			}
			return "", fmt.Errorf("Không tải được siêu dữ liệu SAML: %v", err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return "", fmt.Errorf("Điểm cuối siêu dữ liệu SAML trả mã trạng thái %d", resp.StatusCode)
		}
		rawXML, err = io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		if err != nil {
			return "", errors.New("Không đọc được nội dung siêu dữ liệu SAML")
		}
	} else {
		rawXML = []byte(in.MetadataXML)
	}
	var desc samlEntityDescriptor
	if err := xml.Unmarshal(rawXML, &desc); err != nil {
		return "", errors.New("Siêu dữ liệu SAML không đúng định dạng XML EntityDescriptor")
	}
	if strings.TrimSpace(desc.EntityID) == "" {
		return "", errors.New("Siêu dữ liệu SAML thiếu thuộc tính entityID")
	}
	if in.Issuer != "" && strings.TrimRight(desc.EntityID, "/") != strings.TrimRight(in.Issuer, "/") {
		return "", fmt.Errorf("Trường entityID trong SAML metadata (%q) không khớp với issuer (%q)", desc.EntityID, in.Issuer)
	}
	if len(desc.IDPSSODescriptor.SingleSignOnService) == 0 {
		return "", errors.New("Siêu dữ liệu SAML thiếu điểm cuối SingleSignOnService")
	}
	validSSO := false
	for _, sso := range desc.IDPSSODescriptor.SingleSignOnService {
		if _, err := ValidateExternalURL(sso.Location, "https"); err == nil {
			validSSO = true
			break
		}
	}
	if !validSSO {
		return "", errors.New("Điểm cuối SingleSignOnService trong SAML metadata không hợp lệ hoặc không dùng HTTPS")
	}
	now := time.Now()
	validCert := false
	for _, kd := range desc.IDPSSODescriptor.KeyDescriptor {
		if kd.Use != "" && kd.Use != "signing" {
			continue
		}
		for _, cstr := range kd.KeyInfo.X509Data.X509Certificate {
			clean := strings.Join(strings.Fields(cstr), "")
			if block, _ := pem.Decode([]byte(cstr)); block != nil {
				clean = base64.StdEncoding.EncodeToString(block.Bytes)
			}
			der, err := base64.StdEncoding.DecodeString(clean)
			if err != nil {
				return "", errors.New("Chứng chỉ X.509 trong SAML metadata không giải mã được")
			}
			cert, err := x509.ParseCertificate(der)
			if err != nil {
				return "", errors.New("Chứng chỉ X.509 trong SAML metadata bị hỏng")
			}
			if now.Before(cert.NotBefore) {
				return "", fmt.Errorf("Chứng chỉ ký SAML chưa đến thời điểm có hiệu lực (%s)", cert.NotBefore.Format(time.RFC3339))
			}
			if !now.Before(cert.NotAfter) {
				return "", fmt.Errorf("Chứng chỉ ký SAML đã hết hạn vào %s", cert.NotAfter.Format(time.RFC3339))
			}
			validCert = true
		}
	}
	if !validCert {
		return "", errors.New("Siêu dữ liệu SAML không có chứng chỉ ký X.509 hợp lệ")
	}
	sig := desc.Signature
	if sig == nil {
		sig = desc.IDPSSODescriptor.Signature
	}
	if sig != nil {
		sv := strings.Join(strings.Fields(sig.SignatureValue), "")
		rawSig, err := base64.StdEncoding.DecodeString(sv)
		if err != nil || len(rawSig) < 32 {
			return "", errors.New("Chữ ký số XML của siêu dữ liệu SAML không hợp lệ")
		}
	}
	return "Đã xác thực siêu dữ liệu SAML, điểm cuối SSO và chứng chỉ ký còn hạn.", nil
}

func (p *IdpProber) probeLDAP(ctx context.Context, in IdpProbeInput) (string, error) {
	rawURL := strings.TrimSpace(in.LdapURL)
	if rawURL == "" {
		rawURL = strings.TrimSpace(in.Issuer)
	}
	u, err := ValidateExternalURL(rawURL, "ldaps")
	if err != nil {
		return "", vErr("issuer", err.Error())
	}
	host := u.Hostname()
	port := u.Port()
	if port == "" {
		port = "636"
	}
	addrs, err := p.resolveAndValidate(ctx, host)
	if err != nil {
		return "", err
	}
	timeout := p.Timeout
	if timeout <= 0 {
		timeout = 8 * time.Second
	}
	baseDial := p.DialContext
	if baseDial == nil {
		d := &net.Dialer{Timeout: timeout}
		baseDial = d.DialContext
	}
	rawConn, err := baseDial(ctx, "tcp", net.JoinHostPort(addrs[0].String(), port))
	if err != nil {
		return "", fmt.Errorf("Không kết nối được máy chủ LDAPS: %v", err)
	}
	defer rawConn.Close()
	_ = rawConn.SetDeadline(time.Now().Add(timeout))
	tlsCfg := &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12}
	if p.TLSConfig != nil {
		tlsCfg = p.TLSConfig.Clone()
		if tlsCfg.ServerName == "" {
			tlsCfg.ServerName = host
		}
	}
	tlsConn := tls.Client(rawConn, tlsCfg)
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		return "", fmt.Errorf("Bắt tay TLS với máy chủ LDAPS thất bại: %v", err)
	}
	defer tlsConn.Close()

	// Gửi gói LDAPv3 Simple BindRequest (RFC 4511) và kiểm tra BindResponse.
	bindPkt := encodeLDAPBindRequest(1, in.BindDN, in.BindPassword)
	if _, err := tlsConn.Write(bindPkt); err != nil {
		return "", fmt.Errorf("Lỗi gửi yêu cầu xác thực LDAPS: %v", err)
	}
	code, diag, err := readLDAPResult(tlsConn, 0x61) // [APPLICATION 1] BindResponse
	if err != nil {
		return "", fmt.Errorf("Lỗi đọc phản hồi xác thực LDAPS: %v", err)
	}
	if code != 0 {
		if diag != "" {
			return "", fmt.Errorf("Xác thực tài khoản dịch vụ LDAPS thất bại (mã %d: %s)", code, diag)
		}
		return "", fmt.Errorf("Xác thực tài khoản dịch vụ LDAPS thất bại (mã %d)", code)
	}
	// Tìm thử một mục tại baseDn.
	baseDN := strings.TrimSpace(in.BaseDN)
	if baseDN == "" {
		baseDN = "dc=gov,dc=vn"
	}
	searchPkt := encodeLDAPSearchRequest(2, baseDN)
	if _, err := tlsConn.Write(searchPkt); err != nil {
		return "", fmt.Errorf("Lỗi gửi truy vấn tìm kiếm LDAPS: %v", err)
	}
	scode, sdiag, err := readLDAPSearchDone(tlsConn)
	if err != nil {
		return "", fmt.Errorf("Lỗi đọc kết quả tìm kiếm LDAPS: %v", err)
	}
	if scode != 0 {
		return "", fmt.Errorf("Truy vấn thử danh bạ LDAPS thất bại (mã %d: %s)", scode, sdiag)
	}
	return "Đã kết nối LDAPS (TLS), xác thực tài khoản dịch vụ và tìm kiếm thử thành công.", nil
}

// ---------------------------------------------------------------- BER ASN.1 tối giản cho LDAPv3 (RFC 4511)

func berLen(n int) []byte {
	if n < 0x80 {
		return []byte{byte(n)}
	}
	if n <= 0xff {
		return []byte{0x81, byte(n)}
	}
	return []byte{0x82, byte(n >> 8), byte(n)}
}

func berTLV(tag byte, val []byte) []byte {
	out := []byte{tag}
	out = append(out, berLen(len(val))...)
	out = append(out, val...)
	return out
}

func encodeLDAPBindRequest(msgID byte, bindDN, password string) []byte {
	var body []byte
	body = append(body, berTLV(0x02, []byte{0x03})...)       // version INTEGER 3
	body = append(body, berTLV(0x04, []byte(bindDN))...)     // name OCTET STRING
	body = append(body, berTLV(0x80, []byte(password))...)   // authentication [0] IMPLICIT OCTET STRING (simple)
	bindReq := berTLV(0x60, body)                            // [APPLICATION 0] BindRequest
	msg := append(berTLV(0x02, []byte{msgID}), bindReq...)   // messageID + protocolOp
	return berTLV(0x30, msg)                                 // LDAPMessage SEQUENCE
}

func encodeLDAPSearchRequest(msgID byte, baseDN string) []byte {
	var body []byte
	body = append(body, berTLV(0x04, []byte(baseDN))...)          // baseObject
	body = append(body, berTLV(0x0a, []byte{0x00})...)            // scope: baseObject (0)
	body = append(body, berTLV(0x0a, []byte{0x00})...)            // derefAliases: neverDerefAliases (0)
	body = append(body, berTLV(0x02, []byte{0x01})...)            // sizeLimit: 1
	body = append(body, berTLV(0x02, []byte{0x05})...)            // timeLimit: 5
	body = append(body, berTLV(0x01, []byte{0x00})...)            // typesOnly: FALSE
	body = append(body, berTLV(0x87, []byte("objectClass"))...)   // filter: present [7] "objectClass"
	body = append(body, berTLV(0x30, nil)...)                     // attributes: SEQUENCE OF
	searchReq := berTLV(0x63, body)                               // [APPLICATION 3] SearchRequest
	msg := append(berTLV(0x02, []byte{msgID}), searchReq...)
	return berTLV(0x30, msg)
}

func readBERElement(r io.Reader) (byte, []byte, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return 0, nil, err
	}
	tag := hdr[0]
	length := int(hdr[1])
	if length&0x80 != 0 {
		numBytes := length & 0x7f
		if numBytes == 0 || numBytes > 3 {
			return 0, nil, errors.New("độ dài BER không hợp lệ")
		}
		buf := make([]byte, numBytes)
		if _, err := io.ReadFull(r, buf); err != nil {
			return 0, nil, err
		}
		length = 0
		for _, b := range buf {
			length = (length << 8) | int(b)
		}
	}
	if length > 64*1024 {
		return 0, nil, errors.New("gói LDAP vượt kích thước cho phép")
	}
	val := make([]byte, length)
	if _, err := io.ReadFull(r, val); err != nil {
		return 0, nil, err
	}
	return tag, val, nil
}

func parseLDAPResultPayload(opVal []byte) (int, string, error) {
	r := bytes.NewReader(opVal)
	tag, codeBytes, err := readBERElement(r)
	if err != nil || tag != 0x0a || len(codeBytes) == 0 {
		return -1, "", errors.New("thiếu resultCode trong phản hồi LDAP")
	}
	code := int(codeBytes[0])
	_, _, _ = readBERElement(r) // matchedDN
	_, diagBytes, _ := readBERElement(r)
	return code, string(diagBytes), nil
}

func readLDAPResult(r io.Reader, expectedOpTag byte) (int, string, error) {
	tag, msgVal, err := readBERElement(r)
	if err != nil || tag != 0x30 {
		return -1, "", errors.New("gói phản hồi LDAP không hợp lệ")
	}
	mr := bytes.NewReader(msgVal)
	if _, _, err := readBERElement(mr); err != nil { // messageID
		return -1, "", err
	}
	opTag, opVal, err := readBERElement(mr)
	if err != nil {
		return -1, "", err
	}
	if opTag != expectedOpTag {
		return -1, "", fmt.Errorf("nhận loại phản hồi LDAP 0x%x thay vì 0x%x", opTag, expectedOpTag)
	}
	return parseLDAPResultPayload(opVal)
}

func readLDAPSearchDone(r io.Reader) (int, string, error) {
	for i := 0; i < 8; i++ {
		tag, msgVal, err := readBERElement(r)
		if err != nil || tag != 0x30 {
			return -1, "", errors.New("gói phản hồi tìm kiếm LDAP không hợp lệ")
		}
		mr := bytes.NewReader(msgVal)
		if _, _, err := readBERElement(mr); err != nil {
			return -1, "", err
		}
		opTag, opVal, err := readBERElement(mr)
		if err != nil {
			return -1, "", err
		}
		if opTag == 0x64 { // SearchResultEntry — tiếp tục đọc tới SearchResultDone
			continue
		}
		if opTag == 0x65 { // SearchResultDone
			return parseLDAPResultPayload(opVal)
		}
		return -1, "", fmt.Errorf("nhận gói LDAP không mong đợi 0x%x", opTag)
	}
	return -1, "", errors.New("quá số bản ghi trả về khi tìm kiếm thử LDAP")
}

// ---------------------------------------------------------------- Service.TestIdp

// TestIdp thực hiện POST /api/v1/config/tenants/{id}/idp/test: kiểm tra kết nối OIDC/SAML/LDAP
// và ghi nhận lastTestedAt, lastTestOk vào CSDL nếu tương ứng với IdP đã lưu của đơn vị.
func (s *Service) TestIdp(ctx context.Context, pool *pgxpool.Pool, a Actor, tenantID uuid.UUID, req IdpTestRequest) (IdpTestResult, error) {
	ten, err := store.GetTenant(ctx, pool, tenantID)
	if errors.Is(err, store.ErrNotFound) {
		return IdpTestResult{}, ErrNotFound
	}
	if err != nil {
		return IdpTestResult{}, err
	}
	if !a.PlatformAdmin && !a.owns(ten) {
		return IdpTestResult{}, ErrForbidden
	}
	alias := strings.TrimSpace(req.IdpAlias)
	if alias == "" && req.Idp != nil {
		alias = strings.TrimSpace(req.Idp.IdpAlias)
	}
	if alias == "" && req.Idp == nil {
		return IdpTestResult{}, vErr("idpAlias", "Cần truyền idpAlias của IdP đã lưu hoặc cấu hình idp cần kiểm tra")
	}

	idps, err := store.ListIdps(ctx, pool, tenantID, false)
	if err != nil {
		return IdpTestResult{}, err
	}
	metaMap, err := store.LoadIdpMeta(ctx, pool, tenantID)
	if err != nil {
		return IdpTestResult{}, err
	}

	var saved *store.Idp
	for i := range idps {
		if idps[i].IdpAlias == alias {
			saved = &idps[i]
			break
		}
	}
	if saved == nil && req.Idp == nil {
		return IdpTestResult{}, vErr("idpAlias", "Không tìm thấy cấu hình IdP đã lưu với bí danh này")
	}

	var probeIn IdpProbeInput
	if saved != nil {
		m := metaMap[saved.IdpAlias]
		probeIn = IdpProbeInput{
			IdpAlias:         saved.IdpAlias,
			Protocol:         saved.Protocol,
			Issuer:           saved.Issuer,
			IsVneid:          saved.IsVneid,
			EmailDomains:     saved.EmailDomains,
			ClientID:         m.ClientID,
			MetadataURL:      m.MetadataURL,
			MetadataXML:      m.MetadataXML,
			LdapURL:          m.LdapURL,
			BindDN:           m.BindDN,
			BaseDN:           m.BaseDN,
			UserFilter:       m.UserFilter,
			AttributeMapping: m.AttributeMapping,
		}
		if m.SecretRef != "" && s.Secrets != nil {
			if sec, err := s.Secrets.GetIdpSecret(ctx, m.SecretRef); err == nil {
				probeIn.ClientSecret = sec["clientSecret"]
				probeIn.BindPassword = sec["bindPassword"]
			}
		}
	}
	if req.Idp != nil {
		in := *req.Idp
		if in.IdpAlias != "" {
			probeIn.IdpAlias = in.IdpAlias
		}
		if in.Protocol != "" {
			probeIn.Protocol = in.Protocol
		}
		if in.Issuer != "" {
			probeIn.Issuer = in.Issuer
		}
		if in.ClientID != "" {
			probeIn.ClientID = in.ClientID
		}
		if in.ClientSecret != "" {
			probeIn.ClientSecret = in.ClientSecret
		}
		if in.MetadataURL != "" {
			probeIn.MetadataURL = in.MetadataURL
		}
		if in.MetadataXML != "" {
			probeIn.MetadataXML = in.MetadataXML
		}
		if in.LdapURL != "" {
			probeIn.LdapURL = in.LdapURL
		}
		if in.BindDN != "" {
			probeIn.BindDN = in.BindDN
		}
		if in.BindPassword != "" {
			probeIn.BindPassword = in.BindPassword
		}
		if in.BaseDN != "" {
			probeIn.BaseDN = in.BaseDN
		}
		if in.UserFilter != "" {
			probeIn.UserFilter = in.UserFilter
		}
	}

	prober := s.Prober
	if prober == nil {
		prober = NewIdpProber()
	}
	res, probeErr := prober.Probe(ctx, probeIn)

	// Ghi nhận kết quả kiểm tra gần nhất nếu trùng với IdP đã lưu của đơn vị.
	if saved != nil {
		now := time.Now().UTC()
		ok := res.Success && probeErr == nil
		m := metaMap[saved.IdpAlias]
		m.LastTestedAt = &now
		m.LastTestOk = &ok
		metaMap[saved.IdpAlias] = m
		_ = store.SaveIdpMeta(ctx, pool, tenantID, metaMap, a.ID)
	}
	if probeErr != nil {
		return IdpTestResult{}, probeErr
	}
	return res, nil
}
