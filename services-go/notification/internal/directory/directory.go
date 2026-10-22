// Package directory lấy địa chỉ liên lạc (email, số điện thoại) của người nhận lúc gửi (DV-12-03). Địa chỉ là C2:
// chỉ nằm trong bộ nhớ, không lưu vào notify.delivery (chỉ lưu chỉ mục mù) và không ghi nhật ký. Hợp đồng chưa có
// API tra địa chỉ liên lạc, nên bản hiện thực đọc hồ sơ người dùng ở Keycloak (ADR-08), nơi đồng bộ nhân sự đã ghi
// email và số điện thoại (suy luận nguồn, wiki/14).
package directory

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// ErrNotFound là người dùng không có trong danh bạ.
var ErrNotFound = errors.New("directory: không tìm thấy người dùng")

// Contact là địa chỉ liên lạc của một người dùng.
type Contact struct {
	Email string
	Phone string
}

// String che địa chỉ khi bị in nhầm vào nhật ký.
func (Contact) String() string { return "Contact{[REDACTED:C2]}" }

// GoString che địa chỉ với định dạng %#v.
func (c Contact) GoString() string { return c.String() }

// Directory tra địa chỉ liên lạc theo tenant và người dùng.
type Directory interface {
	Contact(ctx context.Context, tenantID, userID string) (Contact, error)
}

// Static là danh bạ trong bộ nhớ cho dev và kiểm thử.
type Static map[string]Contact

// Contact tra theo khoá "tenant/user".
func (s Static) Contact(_ context.Context, tenantID, userID string) (Contact, error) {
	c, ok := s[tenantID+"/"+userID]
	if !ok {
		return Contact{}, ErrNotFound
	}
	return c, nil
}

// Keycloak đọc người dùng qua Admin REST API `GET /admin/realms/{realm}/users/{id}` bằng tài khoản dịch vụ
// (client_credentials, vai trò view-users). Số điện thoại lấy ở thuộc tính PhoneAttr.
type Keycloak struct {
	HTTP         *http.Client
	BaseURL      string // https://idp.example/
	Realm        string
	ClientID     string
	ClientSecret string
	PhoneAttr    string // mặc định phoneNumber

	mu    sync.Mutex
	token string
	exp   time.Time
}

func (k *Keycloak) client() *http.Client {
	if k.HTTP != nil {
		return k.HTTP
	}
	return &http.Client{Timeout: 5 * time.Second}
}

func (k *Keycloak) bearer(ctx context.Context) (string, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.token != "" && time.Now().Before(k.exp) {
		return k.token, nil
	}
	form := url.Values{"grant_type": {"client_credentials"}, "client_id": {k.ClientID},
		"client_secret": {k.ClientSecret}}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(k.BaseURL, "/")+"/realms/"+
		url.PathEscape(k.Realm)+"/protocol/openid-connect/token", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := k.client().Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&out) != nil || out.AccessToken == "" {
		return "", errors.New("directory: không lấy được token dịch vụ")
	}
	k.token, k.exp = out.AccessToken, time.Now().Add(time.Duration(out.ExpiresIn)*time.Second-30*time.Second)
	return k.token, nil
}

// Contact đọc email và số điện thoại. Realm theo tenant chưa chốt nên dùng một realm cấu hình (suy luận).
func (k *Keycloak) Contact(ctx context.Context, _, userID string) (Contact, error) {
	tok, err := k.bearer(ctx)
	if err != nil {
		return Contact{}, err
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(k.BaseURL, "/")+"/admin/realms/"+
		url.PathEscape(k.Realm)+"/users/"+url.PathEscape(userID), nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := k.client().Do(req)
	if err != nil {
		return Contact{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return Contact{}, ErrNotFound
	}
	if resp.StatusCode == http.StatusUnauthorized {
		k.mu.Lock()
		k.token = ""
		k.mu.Unlock()
	}
	if resp.StatusCode != http.StatusOK {
		return Contact{}, errors.New("directory: Keycloak trả " + resp.Status)
	}
	var u struct {
		Email         string              `json:"email"`
		EmailVerified bool                `json:"emailVerified"`
		Attributes    map[string][]string `json:"attributes"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&u); err != nil {
		return Contact{}, err
	}
	attr := k.PhoneAttr
	if attr == "" {
		attr = "phoneNumber"
	}
	c := Contact{Email: u.Email}
	if v := u.Attributes[attr]; len(v) > 0 {
		c.Phone = v[0]
	}
	return c, nil
}
