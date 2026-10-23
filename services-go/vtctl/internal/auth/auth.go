// Package auth đăng nhập vtctl vào Keycloak vận hành (`platform-ops`, có MFA) bằng luồng thiết bị OAuth 2.0
// (RFC 8628), giữ token theo môi trường trong tệp quyền 0600 và xin bước lên `acr=aal2` khi thao tác cần.
// Token chỉ được đọc claim để quyết định có cần đăng nhập lại; kiểm chữ ký và quyền là việc của máy chủ.
package auth

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"
)

// StepUpMaxAge là tuổi tối đa của lần xác thực bậc 2 cho thao tác `aal: 2` (TL-01, wiki/07).
const StepUpMaxAge = 5 * time.Minute

// Token là bộ token lưu cục bộ của một môi trường.
type Token struct {
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token,omitempty"`
	Expiry       time.Time `json:"expiry"`
}

// Claims là các claim vtctl dùng.
type Claims struct {
	Sub         string `json:"sub"`
	Username    string `json:"preferred_username"`
	ACR         string `json:"acr"`
	AuthTime    int64  `json:"auth_time"`
	Exp         int64  `json:"exp"`
	RealmAccess struct {
		Roles []string `json:"roles"`
	} `json:"realm_access"`
}

// Actor là tên người chạy ghi vào kiểm toán.
func (c Claims) Actor() string {
	if c.Username != "" {
		return c.Username
	}
	return c.Sub
}

// HasRole cho biết token có vai trò realm.
func (c Claims) HasRole(r string) bool { return slices.Contains(c.RealmAccess.Roles, r) }

// AAL2Fresh cho biết token đạt bậc 2 và lần xác thực còn trong maxAge.
func (c Claims) AAL2Fresh(now time.Time, maxAge time.Duration) bool {
	return strings.EqualFold(c.ACR, "aal2") && c.AuthTime > 0 && now.Sub(time.Unix(c.AuthTime, 0)) <= maxAge
}

// ParseClaims đọc phần thân JWT, không kiểm chữ ký.
func ParseClaims(jwt string) (Claims, error) {
	parts := strings.Split(jwt, ".")
	if len(parts) != 3 {
		return Claims{}, errors.New("token không phải JWT")
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return Claims{}, fmt.Errorf("thân token: %w", err)
	}
	var c Claims
	if err := json.Unmarshal(raw, &c); err != nil {
		return Claims{}, fmt.Errorf("thân token: %w", err)
	}
	return c, nil
}

// Session giữ token của một môi trường và đăng nhập lại khi cần.
type Session struct {
	Issuer   string
	ClientID string
	Path     string // tệp token
	HTTP     *http.Client
	Out      io.Writer // nơi in hướng dẫn đăng nhập thiết bị
	Now      func() time.Time
	// Interactive=false thì không tự mở luồng thiết bị, chỉ báo lỗi (dùng trong Job, CI).
	Interactive bool

	tok *Token
}

// ErrLoginRequired báo cần chạy `vtctl login`.
var ErrLoginRequired = errors.New("chưa đăng nhập hoặc phiên đã hết hạn; chạy `vtctl login`")

func (s *Session) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Session) client() *http.Client {
	if s.HTTP != nil {
		return s.HTTP
	}
	return &http.Client{Timeout: 30 * time.Second}
}

func (s *Session) load() (*Token, error) {
	if s.tok != nil {
		return s.tok, nil
	}
	raw, err := os.ReadFile(s.Path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var t Token
	if err := json.Unmarshal(raw, &t); err != nil {
		return nil, fmt.Errorf("tệp token %s hỏng: %w", s.Path, err)
	}
	s.tok = &t
	return &t, nil
}

func (s *Session) save(t *Token) error {
	if err := os.MkdirAll(filepath.Dir(s.Path), 0o700); err != nil {
		return err
	}
	raw, _ := json.Marshal(t)
	tmp := s.Path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, s.Path); err != nil {
		return err
	}
	s.tok = t
	return nil
}

// Logout xoá token cục bộ.
func (s *Session) Logout() error {
	s.tok = nil
	err := os.Remove(s.Path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

// Claims trả claim của token hiện có (không làm mới).
func (s *Session) Claims() (Claims, error) {
	t, err := s.load()
	if err != nil {
		return Claims{}, err
	}
	if t == nil {
		return Claims{}, ErrLoginRequired
	}
	return ParseClaims(t.AccessToken)
}

// AccessToken trả access token còn hạn; aal2=true thì đòi bậc 2 còn trong StepUpMaxAge, thiếu thì xin bước lên.
func (s *Session) AccessToken(ctx context.Context, aal2 bool) (string, error) {
	t, err := s.load()
	if err != nil {
		return "", err
	}
	if t != nil && s.now().Add(30*time.Second).After(t.Expiry) && t.RefreshToken != "" {
		if nt, err := s.refresh(ctx, t.RefreshToken); err == nil {
			t = nt
		} else {
			t = nil
		}
	}
	if t == nil || s.now().After(t.Expiry) {
		if err := s.Login(ctx, false); err != nil {
			return "", err
		}
		t = s.tok
	}
	if aal2 {
		c, err := ParseClaims(t.AccessToken)
		if err != nil {
			return "", err
		}
		if !c.AAL2Fresh(s.now(), StepUpMaxAge) {
			if err := s.Login(ctx, true); err != nil {
				return "", err
			}
			t = s.tok
		}
	}
	return t.AccessToken, nil
}

type discovery struct {
	DeviceEndpoint string `json:"device_authorization_endpoint"`
	TokenEndpoint  string `json:"token_endpoint"`
}

func (s *Session) discover(ctx context.Context) (discovery, error) {
	var d discovery
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(s.Issuer, "/")+"/.well-known/openid-configuration", nil)
	res, err := s.client().Do(req)
	if err != nil {
		return d, fmt.Errorf("tra cấu hình OIDC: %w", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return d, fmt.Errorf("tra cấu hình OIDC: HTTP %d", res.StatusCode)
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&d); err != nil {
		return d, err
	}
	if d.DeviceEndpoint == "" || d.TokenEndpoint == "" {
		return d, errors.New("Keycloak vận hành chưa bật luồng thiết bị (device_authorization_endpoint)")
	}
	return d, nil
}

type tokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"`
	Error        string `json:"error"`
	ErrorDesc    string `json:"error_description"`
}

func (s *Session) post(ctx context.Context, endpoint string, form url.Values) (tokenResponse, int, error) {
	var tr tokenResponse
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := s.client().Do(req)
	if err != nil {
		return tr, 0, err
	}
	defer res.Body.Close()
	err = json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&tr)
	return tr, res.StatusCode, err
}

func (s *Session) store(tr tokenResponse) (*Token, error) {
	if tr.AccessToken == "" {
		return nil, errors.New("Keycloak không trả access token")
	}
	t := &Token{AccessToken: tr.AccessToken, RefreshToken: tr.RefreshToken, Expiry: s.now().Add(time.Duration(tr.ExpiresIn) * time.Second)}
	return t, s.save(t)
}

func (s *Session) refresh(ctx context.Context, rt string) (*Token, error) {
	d, err := s.discover(ctx)
	if err != nil {
		return nil, err
	}
	tr, code, err := s.post(ctx, d.TokenEndpoint, url.Values{"grant_type": {"refresh_token"}, "client_id": {s.ClientID}, "refresh_token": {rt}})
	if err != nil || code != http.StatusOK {
		return nil, fmt.Errorf("làm mới token: HTTP %d %s", code, tr.Error)
	}
	return s.store(tr)
}

// Login chạy luồng thiết bị; stepUp=true xin `acr_values=aal2` và `max_age` để buộc nhập lại MFA.
func (s *Session) Login(ctx context.Context, stepUp bool) error {
	if !s.Interactive {
		if stepUp {
			return errors.New("thao tác cần xác thực bước lên (MFA trong 5 phút); chạy `vtctl login --step-up` rồi chạy lại")
		}
		return ErrLoginRequired
	}
	d, err := s.discover(ctx)
	if err != nil {
		return err
	}
	form := url.Values{"client_id": {s.ClientID}, "scope": {"openid"}}
	if stepUp {
		form.Set("acr_values", "aal2")
		form.Set("max_age", fmt.Sprint(int(StepUpMaxAge.Seconds())))
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, d.DeviceEndpoint, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := s.client().Do(req)
	if err != nil {
		return fmt.Errorf("bắt đầu đăng nhập thiết bị: %w", err)
	}
	defer res.Body.Close()
	var da struct {
		DeviceCode string `json:"device_code"`
		UserCode   string `json:"user_code"`
		URI        string `json:"verification_uri"`
		URIFull    string `json:"verification_uri_complete"`
		ExpiresIn  int    `json:"expires_in"`
		Interval   int    `json:"interval"`
	}
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("bắt đầu đăng nhập thiết bị: HTTP %d", res.StatusCode)
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&da); err != nil {
		return err
	}
	link := da.URIFull
	if link == "" {
		link = da.URI
	}
	what := "Đăng nhập"
	if stepUp {
		what = "Xác thực bước lên (MFA)"
	}
	if s.Out != nil {
		fmt.Fprintf(s.Out, "%s: mở %s và nhập mã %s\n", what, link, da.UserCode)
	}
	interval := time.Duration(max(da.Interval, 1)) * time.Second
	deadline := s.now().Add(time.Duration(max(da.ExpiresIn, 60)) * time.Second)
	for s.now().Before(deadline) {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(interval):
		}
		tr, code, err := s.post(ctx, d.TokenEndpoint, url.Values{
			"grant_type":  {"urn:ietf:params:oauth:grant-type:device_code"},
			"client_id":   {s.ClientID},
			"device_code": {da.DeviceCode},
		})
		if err != nil {
			return fmt.Errorf("chờ đăng nhập: %w", err)
		}
		switch {
		case code == http.StatusOK:
			if _, err := s.store(tr); err != nil {
				return err
			}
			if stepUp {
				c, err := ParseClaims(s.tok.AccessToken)
				if err != nil || !strings.EqualFold(c.ACR, "aal2") {
					return errors.New("Keycloak không cấp bậc xác thực aal2; kiểm tra MFA của tài khoản vận hành")
				}
			}
			return nil
		case tr.Error == "authorization_pending":
		case tr.Error == "slow_down":
			interval += 5 * time.Second
		case tr.Error == "access_denied":
			return errors.New("người dùng từ chối đăng nhập")
		case tr.Error == "expired_token":
			return errors.New("mã đăng nhập đã hết hạn; chạy lại `vtctl login`")
		default:
			return fmt.Errorf("đăng nhập: HTTP %d %s", code, tr.Error)
		}
	}
	return errors.New("hết thời gian chờ đăng nhập")
}
