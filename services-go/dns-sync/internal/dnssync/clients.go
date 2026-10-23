package dnssync

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// RegistryClient gọi GET /api/v1/registry/cells (listCells) của SVC-03, theo con trỏ tới hết trang, với token client
// credentials của client dns-sync.
type RegistryClient struct {
	BaseURL string
	Token   TokenSource
	HTTP    *http.Client
}

type cellPage struct {
	Items      []Cell  `json:"items"`
	NextCursor *string `json:"nextCursor"`
}

func (c *RegistryClient) Cells(ctx context.Context) ([]Cell, error) {
	var all []Cell
	cursor := ""
	for i := 0; i < 100; i++ {
		q := url.Values{"limit": {"200"}}
		if cursor != "" {
			q.Set("cursor", cursor)
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(c.BaseURL, "/")+"/api/v1/registry/cells?"+q.Encode(), nil)
		if err != nil {
			return nil, err
		}
		if c.Token != nil {
			tok, err := c.Token.Token(ctx)
			if err != nil {
				return nil, err
			}
			req.Header.Set("Authorization", "Bearer "+tok)
		}
		req.Header.Set("Accept", "application/json")
		var p cellPage
		if err := doJSON(c.HTTP, req, &p); err != nil {
			return nil, err
		}
		all = append(all, p.Items...)
		if p.NextCursor == nil || *p.NextCursor == "" {
			return all, nil
		}
		cursor = *p.NextCursor
	}
	return nil, fmt.Errorf("listCells: quá 100 trang")
}

// TokenSource cấp access token.
type TokenSource interface {
	Token(ctx context.Context) (string, error)
}

// ClientCredentials lấy token OAuth 2.0 client credentials từ Keycloak và giữ tới 30 giây trước khi hết hạn.
type ClientCredentials struct {
	TokenURL, ClientID, ClientSecret string
	HTTP                             *http.Client
	mu                               sync.Mutex
	tok                              string
	exp                              time.Time
}

func (c *ClientCredentials) Token(ctx context.Context) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.tok != "" && time.Now().Before(c.exp) {
		return c.tok, nil
	}
	form := url.Values{"grant_type": {"client_credentials"}, "client_id": {c.ClientID}, "client_secret": {c.ClientSecret}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.TokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	var t struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := doJSON(c.HTTP, req, &t); err != nil {
		return "", fmt.Errorf("token: %w", err)
	}
	c.tok, c.exp = t.AccessToken, time.Now().Add(time.Duration(t.ExpiresIn-30)*time.Second)
	return c.tok, nil
}

// PowerDNS gọi HTTP API của PowerDNS Authoritative (cùng máy chủ với bản ghi tenant của OPS-07).
type PowerDNS struct {
	BaseURL, ServerID, Zone, APIKey string
	HTTP                            *http.Client
}

func (p *PowerDNS) zoneURL() string {
	sid := p.ServerID
	if sid == "" {
		sid = "localhost"
	}
	return strings.TrimRight(p.BaseURL, "/") + "/api/v1/servers/" + url.PathEscape(sid) + "/zones/" +
		url.PathEscape(strings.TrimSuffix(p.Zone, ".")+".")
}

func (p *PowerDNS) A(ctx context.Context, name string) ([]string, error) {
	q := url.Values{"rrset_name": {name}, "rrset_type": {"A"}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.zoneURL()+"?"+q.Encode(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-API-Key", p.APIKey)
	var z struct {
		RRsets []struct {
			Name, Type string
			Records    []struct {
				Content  string `json:"content"`
				Disabled bool   `json:"disabled"`
			} `json:"records"`
		} `json:"rrsets"`
	}
	if err := doJSON(p.HTTP, req, &z); err != nil {
		return nil, err
	}
	var ips []string
	for _, rr := range z.RRsets {
		if strings.EqualFold(rr.Name, name) && rr.Type == "A" {
			for _, r := range rr.Records {
				if !r.Disabled {
					ips = append(ips, r.Content)
				}
			}
		}
	}
	return ips, nil
}

func (p *PowerDNS) ReplaceA(ctx context.Context, name string, ttl int, ips []string) error {
	recs := make([]map[string]any, 0, len(ips))
	for _, ip := range ips {
		recs = append(recs, map[string]any{"content": ip, "disabled": false})
	}
	body, _ := json.Marshal(map[string]any{"rrsets": []any{map[string]any{
		"name": name, "type": "A", "ttl": ttl, "changetype": "REPLACE", "records": recs,
	}}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPatch, p.zoneURL(), bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("X-API-Key", p.APIKey)
	req.Header.Set("Content-Type", "application/json")
	return doJSON(p.HTTP, req, nil)
}

func doJSON(hc *http.Client, req *http.Request, out any) error {
	if hc == nil {
		hc = &http.Client{Timeout: 10 * time.Second}
	}
	resp, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("%s %s: HTTP %d", req.Method, req.URL.Path, resp.StatusCode)
	}
	if out == nil || len(b) == 0 {
		return nil
	}
	return json.Unmarshal(b, out)
}
