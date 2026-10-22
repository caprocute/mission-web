package provision

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// ClientCredentials lấy token OAuth 2.0 client_credentials (RFC 6749 mục 4.4) của tài khoản dịch vụ cấp phát
// trên Keycloak của cell, đệm tới 30 giây trước khi hết hạn. Bí mật đọc từ OpenBao qua External Secrets.
type ClientCredentials struct {
	ClientID     string
	ClientSecret string
	HTTP         *http.Client

	mu    sync.Mutex
	cache map[string]cachedToken
}

type cachedToken struct {
	value string
	exp   time.Time
}

// Token trả access token cho điểm cấp token tokenURL.
func (c *ClientCredentials) Token(ctx context.Context, tokenURL string) (string, error) {
	if tokenURL == "" {
		return "", errors.New("provision: thiếu địa chỉ cấp token")
	}
	c.mu.Lock()
	if t, ok := c.cache[tokenURL]; ok && time.Now().Before(t.exp) {
		c.mu.Unlock()
		return t.value, nil
	}
	c.mu.Unlock()
	form := url.Values{"grant_type": {"client_credentials"}, "client_id": {c.ClientID}, "client_secret": {c.ClientSecret}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := httpClient(c.HTTP).Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		// Không đưa thân phản hồi của điểm cấp token vào lỗi (có thể chứa thông tin nhạy cảm).
		return "", &HTTPError{System: "keycloak-token", Status: resp.StatusCode}
	}
	var tr struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &tr); err != nil || tr.AccessToken == "" {
		return "", errors.New("provision: phản hồi cấp token không hợp lệ")
	}
	ttl := time.Duration(tr.ExpiresIn)*time.Second - 30*time.Second
	if ttl < 5*time.Second {
		ttl = 5 * time.Second
	}
	c.mu.Lock()
	if c.cache == nil {
		c.cache = map[string]cachedToken{}
	}
	c.cache[tokenURL] = cachedToken{value: tr.AccessToken, exp: time.Now().Add(ttl)}
	c.mu.Unlock()
	return tr.AccessToken, nil
}

// TokenSource cấp token cho lời gọi tới hệ của cell.
type TokenSource interface {
	Token(ctx context.Context, tokenURL string) (string, error)
}

func httpClient(c *http.Client) *http.Client {
	if c != nil {
		return c
	}
	return &http.Client{Timeout: 15 * time.Second}
}

// call là một lời gọi HTTP JSON.
type call struct {
	system  string
	method  string
	url     string
	body    any    // mã hoá JSON; string được gửi nguyên văn
	ctype   string // mặc định application/json
	token   string
	headers map[string]string
	ok      []int // mã thành công thêm ngoài 2xx (ví dụ 404 khi xoá)
}

type result struct {
	status int
	header http.Header
	body   []byte
}

func do(ctx context.Context, hc *http.Client, c call) (*result, error) {
	var rd io.Reader
	switch b := c.body.(type) {
	case nil:
	case string:
		rd = strings.NewReader(b)
	case []byte:
		rd = bytes.NewReader(b)
	default:
		raw, err := json.Marshal(b)
		if err != nil {
			return nil, err
		}
		rd = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, c.method, c.url, rd)
	if err != nil {
		return nil, err
	}
	if rd != nil {
		ct := c.ctype
		if ct == "" {
			ct = "application/json"
		}
		req.Header.Set("Content-Type", ct)
	}
	req.Header.Set("Accept", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	for k, v := range c.headers {
		req.Header.Set(k, v)
	}
	resp, err := httpClient(hc).Do(req)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", c.system, err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	r := &result{status: resp.StatusCode, header: resp.Header, body: body}
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return r, nil
	}
	for _, s := range c.ok {
		if s == resp.StatusCode {
			return r, nil
		}
	}
	return r, &HTTPError{System: c.system, Status: resp.StatusCode, Body: string(body)}
}

func decode(r *result, v any) error {
	if len(r.body) == 0 {
		return errors.New("provision: phản hồi rỗng")
	}
	return json.Unmarshal(r.body, v)
}

// lastSegment lấy id cuối trong header Location.
func lastSegment(loc string) string {
	loc = strings.TrimRight(loc, "/")
	if i := strings.LastIndexByte(loc, '/'); i >= 0 {
		return loc[i+1:]
	}
	return loc
}

// TemplateResolver dựng CellEndpoints từ mẫu có chỗ {cell} (mã cell chữ thường), ví dụ
// https://sso.{cell}.okr.viettel.vn. EdgeHost lấy tên máy của registry.cell.api_base_url.
type TemplateResolver struct {
	KeycloakURL string
	Realm       string
	TokenURL    string // rỗng: <KeycloakURL>/realms/<Realm>/protocol/openid-connect/token
	CoreURL     string
	OrgURL      string
}

// Endpoints hiện thực EndpointResolver.
func (t TemplateResolver) Endpoints(cellCode, apiBaseURL string) (CellEndpoints, error) {
	c := strings.ToLower(cellCode)
	fill := func(s string) string { return strings.TrimRight(strings.ReplaceAll(s, "{cell}", c), "/") }
	ep := CellEndpoints{Cell: cellCode, KeycloakURL: fill(t.KeycloakURL), Realm: t.Realm, TokenURL: fill(t.TokenURL),
		CoreURL: fill(t.CoreURL), OrgURL: fill(t.OrgURL)}
	if ep.Realm == "" {
		ep.Realm = "mission"
	}
	if ep.TokenURL == "" && ep.KeycloakURL != "" {
		ep.TokenURL = ep.KeycloakURL + "/realms/" + ep.Realm + "/protocol/openid-connect/token"
	}
	u, err := url.Parse(apiBaseURL)
	if err != nil || u.Hostname() == "" {
		return ep, fmt.Errorf("provision: địa chỉ API của cell %s không hợp lệ", cellCode)
	}
	ep.EdgeHost = u.Hostname()
	return ep, nil
}
