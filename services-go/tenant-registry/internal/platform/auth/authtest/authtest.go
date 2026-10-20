// Package authtest dựng JWKS và token ký bằng khoá RSA tự sinh lúc chạy, chỉ dùng trong kiểm thử.
package authtest

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Issuer giả dùng trong kiểm thử.
const Issuer = "https://sso.test.invalid/realms/mission"

// Keys là cặp khoá kiểm thử kèm máy chủ JWKS.
type Keys struct {
	Priv   *rsa.PrivateKey
	Kid    string
	Server *httptest.Server
}

// New sinh khoá RSA 2048 và mở máy chủ JWKS.
func New() *Keys {
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		panic(err)
	}
	k := &Keys{Priv: priv, Kid: "test-kid"}
	k.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []map[string]string{{
			"kty": "RSA", "kid": k.Kid, "use": "sig", "alg": "RS256",
			"n": base64.RawURLEncoding.EncodeToString(priv.N.Bytes()),
			"e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(priv.E)).Bytes()),
		}}})
	}))
	return k
}

// Close dừng máy chủ JWKS.
func (k *Keys) Close() { k.Server.Close() }

// JWKSURL là địa chỉ JWKS.
func (k *Keys) JWKSURL() string { return k.Server.URL }

// Claims mô tả token cần ký.
type Claims struct {
	Subject  string
	Tenant   string
	Cell     string
	Roles    []string
	ACR      string
	AuthTime time.Time
	TTL      time.Duration
}

// Token ký token RS256.
func (k *Keys) Token(c Claims) string {
	if c.TTL == 0 {
		c.TTL = 5 * time.Minute
	}
	now := time.Now()
	m := jwt.MapClaims{
		"iss": Issuer, "sub": c.Subject, "iat": now.Unix(), "exp": now.Add(c.TTL).Unix(),
		"realm_access": map[string]any{"roles": c.Roles},
	}
	if c.Tenant != "" {
		m["tenant"] = c.Tenant
	}
	if c.Cell != "" {
		m["cell"] = c.Cell
	}
	if c.ACR != "" {
		m["acr"] = c.ACR
	}
	if !c.AuthTime.IsZero() {
		m["auth_time"] = c.AuthTime.Unix()
	}
	t := jwt.NewWithClaims(jwt.SigningMethodRS256, m)
	t.Header["kid"] = k.Kid
	s, err := t.SignedString(k.Priv)
	if err != nil {
		panic(err)
	}
	return s
}

// PlatformAdmin là token quản trị nền tảng đã bước lên AAL2.
func (k *Keys) PlatformAdmin(sub string) string {
	return k.Token(Claims{Subject: sub, Roles: []string{"platform_admin"}, ACR: "aal2", AuthTime: time.Now()})
}
