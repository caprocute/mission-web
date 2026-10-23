// Package auth xác thực token Keycloak (JWT) bằng khoá công khai lấy từ JWKS và dựng Principal cho tầng
// nghiệp vụ. Vai trò đọc từ realm_access.roles, tenant từ claim tenant (realm dev, WEB-056) hoặc tid/tenant_id
// (hợp đồng và TL-01), mức xác thực từ acr và auth_time (RFC 9470).
// Máy chủ chỉ kiểm vai trò ở đây; phạm vi dữ liệu (ABAC) thuộc OPA (NEN-014, điểm chờ nền móng). Danh sách
// vai trò được phép của từng thao tác lấy theo x-mission.roles của hợp đồng, khớp ma trận docs/phan-quyen.
// Điểm chờ nền móng: thay bằng bộ xác thực JWT dùng chung (NEN-010/NEN-017) khi có. Bản sao từ
// tenant-registry/internal/platform/auth, đổi vai trò theo SCR-1302 (DV-09-04).
package auth

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"os"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Vai trò baseline mục 10 mà dịch vụ này dùng (ma trận wiki/15a dòng SCR-1302).
const (
	RoleInspector = "inspector" // AC-10
	RoleAppraiser = "appraiser" // AC-07
)

// Principal là danh tính đã xác thực của người gọi.
type Principal struct {
	Subject  string
	Tenant   string // giá trị claim tenant (mã hoặc uuid)
	Cell     string // claim cell nếu có
	Roles    []string
	ACR      string
	AuthTime time.Time
	ClientID string
	Username string // claim preferred_username
}

// ServiceAccount cho biết token là của tài khoản dịch vụ thuộc chính client (preferred_username là
// service-account-<azp>), không phải người dùng đăng nhập qua client đó (wiki trang 14 mục 42).
func (p *Principal) ServiceAccount() bool {
	return p != nil && p.ClientID != "" && p.Username == "service-account-"+p.ClientID
}

// HasRole kiểm vai trò realm.
func (p *Principal) HasRole(r string) bool { return p != nil && slices.Contains(p.Roles, r) }

// HasAnyRole kiểm một trong các vai trò.
func (p *Principal) HasAnyRole(rs ...string) bool {
	for _, r := range rs {
		if p.HasRole(r) {
			return true
		}
	}
	return false
}

// AAL2Fresh kiểm acr=aal2 và auth_time không quá maxAge (TL-01: 5 phút cho thao tác có x-mission.aal: 2).
func (p *Principal) AAL2Fresh(now time.Time, maxAge time.Duration) bool {
	if p == nil || !strings.EqualFold(p.ACR, "aal2") || p.AuthTime.IsZero() {
		return false
	}
	return now.Sub(p.AuthTime) <= maxAge
}

// AC trả mã tác nhân AC của baseline mục 10 tương ứng vai trò cao nhất, chỉ để ghi nhật ký.
func (p *Principal) AC() string {
	if p == nil {
		return ""
	}
	switch {
	case p.HasRole(RoleInspector):
		return "AC-10"
	case p.HasRole(RoleAppraiser):
		return "AC-07"
	}
	return "user"
}

// Verifier kiểm token và trả Principal.
type Verifier interface {
	Verify(ctx context.Context, raw string) (*Principal, error)
}

// ErrInvalidToken là lỗi chung khi token không hợp lệ (không nêu chi tiết ra ngoài).
var ErrInvalidToken = errors.New("auth: token không hợp lệ")

type ctxKey struct{}

// WithPrincipal gắn Principal vào ngữ cảnh.
func WithPrincipal(ctx context.Context, p *Principal) context.Context {
	return context.WithValue(ctx, ctxKey{}, p)
}

// FromContext lấy Principal (nil nếu chưa xác thực).
func FromContext(ctx context.Context) *Principal {
	p, _ := ctx.Value(ctxKey{}).(*Principal)
	return p
}

// BearerToken tách token từ header Authorization.
func BearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return ""
}

// ---------------------------------------------------------------- JWKS

// JWKSConfig cấu hình bộ kiểm JWKS.
type JWKSConfig struct {
	JWKSURL  string   // ví dụ https://sso.cell/realms/mission/protocol/openid-connect/certs
	Issuers  []string // issuer chấp nhận (mỗi cell một realm)
	Audience string   // rỗng thì không kiểm aud
	Client   *http.Client
	// MinRefresh chặn làm mới JWKS dồn dập khi gặp kid lạ.
	MinRefresh time.Duration
}

// JWKSVerifier kiểm chữ ký RS256/PS256/ES256 với khoá từ JWKS, có bộ đệm khoá và làm mới khi gặp kid lạ.
type JWKSVerifier struct {
	cfg       JWKSConfig
	mu        sync.RWMutex
	keys      map[string]crypto.PublicKey
	lastFetch time.Time
	parser    *jwt.Parser
}

// NewJWKSVerifier tạo bộ kiểm; khoá được nạp lười ở lần kiểm đầu tiên.
func NewJWKSVerifier(cfg JWKSConfig) *JWKSVerifier {
	if cfg.Client == nil {
		cfg.Client = &http.Client{Timeout: 5 * time.Second}
	}
	if cfg.MinRefresh == 0 {
		cfg.MinRefresh = 30 * time.Second
	}
	opts := []jwt.ParserOption{
		jwt.WithValidMethods([]string{"RS256", "PS256", "ES256"}),
		jwt.WithExpirationRequired(),
		jwt.WithLeeway(30 * time.Second),
	}
	if cfg.Audience != "" {
		opts = append(opts, jwt.WithAudience(cfg.Audience))
	}
	return &JWKSVerifier{cfg: cfg, keys: map[string]crypto.PublicKey{}, parser: jwt.NewParser(opts...)}
}

type claims struct {
	jwt.RegisteredClaims
	Tenant      string `json:"tenant"`
	TID         string `json:"tid"`
	TenantID    string `json:"tenant_id"`
	Cell        string `json:"cell"`
	ACR         string `json:"acr"`
	AuthTime    int64  `json:"auth_time"`
	AZP         string `json:"azp"`
	Username    string `json:"preferred_username"`
	RealmAccess struct {
		Roles []string `json:"roles"`
	} `json:"realm_access"`
}

// Verify kiểm token.
func (v *JWKSVerifier) Verify(ctx context.Context, raw string) (*Principal, error) {
	if raw == "" {
		if strings.EqualFold(os.Getenv("AUTH_ENABLED"), "false") {
			tenant := os.Getenv("TENANT")
			if tenant == "" {
				tenant = "default"
			}
			return &Principal{
				Subject:  "00000000-0000-7000-8000-000000000001",
				Tenant:   tenant,
				Roles:    []string{RoleInspector, RoleAppraiser},
				ACR:      "aal2",
				AuthTime: time.Now(),
				ClientID: "dev-bypass",
			}, nil
		}
		return nil, ErrInvalidToken
	}
	var c claims
	_, err := v.parser.ParseWithClaims(raw, &c, func(t *jwt.Token) (any, error) {
		kid, _ := t.Header["kid"].(string)
		return v.key(ctx, kid)
	})
	if err != nil {
		return nil, ErrInvalidToken
	}
	if len(v.cfg.Issuers) > 0 && !slices.Contains(v.cfg.Issuers, c.Issuer) {
		return nil, ErrInvalidToken
	}
	p := &Principal{
		Subject:  c.Subject,
		Tenant:   firstNonEmpty(c.Tenant, c.TID, c.TenantID),
		Cell:     c.Cell,
		Roles:    c.RealmAccess.Roles,
		ACR:      c.ACR,
		ClientID: c.AZP,
		Username: c.Username,
	}
	if c.AuthTime > 0 {
		p.AuthTime = time.Unix(c.AuthTime, 0)
	}
	return p, nil
}

func firstNonEmpty(vs ...string) string {
	for _, v := range vs {
		if v != "" {
			return v
		}
	}
	return ""
}

func (v *JWKSVerifier) key(ctx context.Context, kid string) (crypto.PublicKey, error) {
	v.mu.RLock()
	k, ok := v.keys[kid]
	recent := time.Since(v.lastFetch) < v.cfg.MinRefresh
	v.mu.RUnlock()
	if ok {
		return k, nil
	}
	if recent {
		return nil, ErrInvalidToken
	}
	if err := v.refresh(ctx); err != nil {
		return nil, err
	}
	v.mu.RLock()
	defer v.mu.RUnlock()
	if k, ok := v.keys[kid]; ok {
		return k, nil
	}
	return nil, ErrInvalidToken
}

type jwk struct {
	Kty string `json:"kty"`
	Kid string `json:"kid"`
	Use string `json:"use"`
	N   string `json:"n"`
	E   string `json:"e"`
	Crv string `json:"crv"`
	X   string `json:"x"`
	Y   string `json:"y"`
}

func (v *JWKSVerifier) refresh(ctx context.Context) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	if time.Since(v.lastFetch) < v.cfg.MinRefresh && len(v.keys) > 0 {
		return nil
	}
	v.lastFetch = time.Now()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, v.cfg.JWKSURL, nil)
	if err != nil {
		return err
	}
	resp, err := v.cfg.Client.Do(req)
	if err != nil {
		return fmt.Errorf("auth: tải JWKS: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("auth: tải JWKS mã %d", resp.StatusCode)
	}
	var set struct {
		Keys []jwk `json:"keys"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&set); err != nil {
		return fmt.Errorf("auth: JWKS sai định dạng: %w", err)
	}
	keys := map[string]crypto.PublicKey{}
	for _, k := range set.Keys {
		if k.Use != "" && k.Use != "sig" {
			continue
		}
		if pk, err := k.publicKey(); err == nil {
			keys[k.Kid] = pk
		}
	}
	v.keys = keys
	return nil
}

func (k jwk) publicKey() (crypto.PublicKey, error) {
	switch k.Kty {
	case "RSA":
		n, err := b64(k.N)
		if err != nil {
			return nil, err
		}
		e, err := b64(k.E)
		if err != nil {
			return nil, err
		}
		return &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: int(new(big.Int).SetBytes(e).Int64())}, nil
	case "EC":
		if k.Crv != "P-256" {
			return nil, errors.New("auth: đường cong không hỗ trợ")
		}
		x, err := b64(k.X)
		if err != nil {
			return nil, err
		}
		y, err := b64(k.Y)
		if err != nil {
			return nil, err
		}
		return &ecdsa.PublicKey{Curve: elliptic.P256(), X: new(big.Int).SetBytes(x), Y: new(big.Int).SetBytes(y)}, nil
	}
	return nil, errors.New("auth: loại khoá không hỗ trợ")
}

func b64(s string) ([]byte, error) {
	return base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "="))
}
