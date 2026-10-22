package channel

import (
	"bytes"
	"context"
	"crypto"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// PushRouter chọn hiện thực theo nền tảng của thiết bị (notify.push_device.platform).
type PushRouter struct {
	FCM  Sender
	APNS Sender
	// Internal là kênh đẩy nội bộ của bản cách ly; khi có thì mọi thiết bị đi qua kênh này.
	Internal Sender
}

// Send gửi tới một thiết bị.
func (p PushRouter) Send(ctx context.Context, m Message) (string, error) {
	switch {
	case p.Internal != nil:
		return p.Internal.Send(ctx, m)
	case m.Platform == "FCM" && p.FCM != nil:
		return p.FCM.Send(ctx, m)
	case m.Platform == "APNS" && p.APNS != nil:
		return p.APNS.Send(ctx, m)
	default:
		return "", Permanent("PLATFORM_UNSUPPORTED")
	}
}

// ServiceAccount là phần cần dùng của tệp khoá tài khoản dịch vụ Firebase.
type ServiceAccount struct {
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
}

// FCM gửi qua Firebase Cloud Messaging HTTP v1, xác thực OAuth 2.0 bằng JWT của tài khoản dịch vụ.
type FCM struct {
	HTTP    *http.Client
	BaseURL string // mặc định https://fcm.googleapis.com
	Account ServiceAccount

	once   sync.Once
	key    crypto.Signer
	keyErr error
	mu     sync.Mutex
	token  string
	exp    time.Time
}

func (f *FCM) accessToken(ctx context.Context) (string, error) {
	f.once.Do(func() { f.key, f.keyErr = ParsePrivateKey(f.Account.PrivateKey) })
	if f.keyErr != nil {
		return "", Permanent("FCM_KEY")
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.token != "" && time.Now().Before(f.exp) {
		return f.token, nil
	}
	now := time.Now()
	assertion, err := signJWT(f.key, map[string]any{"alg": "RS256", "typ": "JWT"}, map[string]any{
		"iss": f.Account.ClientEmail, "scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud": f.Account.TokenURI, "iat": now.Unix(), "exp": now.Add(time.Hour).Unix()})
	if err != nil {
		return "", Permanent("FCM_SIGN")
	}
	form := url.Values{"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"}, "assertion": {assertion}}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, f.Account.TokenURI, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := client(f.HTTP).Do(req)
	if err != nil {
		return "", Transient{"FCM_TOKEN"}
	}
	defer resp.Body.Close()
	if err := status(resp.StatusCode, "FCM_TOKEN"); err != nil {
		return "", err
	}
	var out struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || out.AccessToken == "" {
		return "", Transient{"FCM_TOKEN"}
	}
	f.token, f.exp = out.AccessToken, now.Add(time.Duration(out.ExpiresIn)*time.Second-time.Minute)
	return f.token, nil
}

// Send gửi tới một mã thiết bị FCM.
func (f *FCM) Send(ctx context.Context, m Message) (string, error) {
	tok, err := f.accessToken(ctx)
	if err != nil {
		return "", err
	}
	prio := "normal"
	if m.High {
		prio = "high"
	}
	msg := map[string]any{"message": map[string]any{
		"token":        m.To,
		"notification": map[string]string{"title": m.Subject, "body": m.Body},
		"data":         map[string]string{"deepLink": m.DeepLink},
		"android":      map[string]any{"priority": prio},
	}}
	body, _ := json.Marshal(msg)
	base := f.BaseURL
	if base == "" {
		base = "https://fcm.googleapis.com"
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimRight(base, "/")+"/v1/projects/"+url.PathEscape(f.Account.ProjectID)+"/messages:send",
		bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := client(f.HTTP).Do(req)
	if err != nil {
		return "", Transient{"CONNECT"}
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if resp.StatusCode == http.StatusNotFound || bytes.Contains(raw, []byte("UNREGISTERED")) {
		return "", ErrUnregistered
	}
	if resp.StatusCode == http.StatusUnauthorized {
		f.mu.Lock()
		f.token = ""
		f.mu.Unlock()
		return "", Transient{"FCM_AUTH"}
	}
	if err := status(resp.StatusCode, "FCM"); err != nil {
		return "", err
	}
	var out struct {
		Name string `json:"name"`
	}
	_ = json.Unmarshal(raw, &out)
	return out.Name, nil
}

// APNs gửi qua Apple Push Notification service HTTP/2, xác thực bằng token JWT ES256 (khoá .p8).
type APNs struct {
	HTTP    *http.Client
	BaseURL string // mặc định https://api.push.apple.com
	TeamID  string
	KeyID   string
	Key     crypto.Signer
	Topic   string // bundle id của ứng dụng

	mu    sync.Mutex
	token string
	iat   time.Time
}

func (a *APNs) bearer() (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	// Apple yêu cầu làm mới token sau 20 đến 60 phút.
	if a.token != "" && time.Since(a.iat) < 45*time.Minute {
		return a.token, nil
	}
	now := time.Now()
	t, err := signJWT(a.Key, map[string]any{"alg": "ES256", "kid": a.KeyID}, map[string]any{"iss": a.TeamID,
		"iat": now.Unix()})
	if err != nil {
		return "", Permanent("APNS_SIGN")
	}
	a.token, a.iat = t, now
	return t, nil
}

// Send gửi tới một mã thiết bị APNs.
func (a *APNs) Send(ctx context.Context, m Message) (string, error) {
	tok, err := a.bearer()
	if err != nil {
		return "", err
	}
	payload := map[string]any{"aps": map[string]any{"alert": map[string]string{"title": m.Subject, "body": m.Body},
		"sound": "default"}, "deepLink": m.DeepLink}
	body, _ := json.Marshal(payload)
	base := a.BaseURL
	if base == "" {
		base = "https://api.push.apple.com"
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimRight(base, "/")+"/3/device/"+url.PathEscape(m.To), bytes.NewReader(body))
	req.Header.Set("authorization", "bearer "+tok)
	req.Header.Set("apns-topic", a.Topic)
	req.Header.Set("apns-push-type", "alert")
	if m.High {
		req.Header.Set("apns-priority", "10")
	} else {
		req.Header.Set("apns-priority", "5")
	}
	resp, err := client(a.HTTP).Do(req)
	if err != nil {
		return "", Transient{"CONNECT"}
	}
	defer resp.Body.Close()
	var out struct {
		Reason string `json:"reason"`
	}
	_ = json.NewDecoder(io.LimitReader(resp.Body, 16<<10)).Decode(&out)
	switch {
	case resp.StatusCode == http.StatusGone, out.Reason == "BadDeviceToken", out.Reason == "Unregistered":
		return "", ErrUnregistered
	case resp.StatusCode == http.StatusForbidden && out.Reason == "ExpiredProviderToken":
		a.mu.Lock()
		a.token = ""
		a.mu.Unlock()
		return "", Transient{"APNS_AUTH"}
	}
	if err := status(resp.StatusCode, "APNS"); err != nil {
		return "", err
	}
	return resp.Header.Get("apns-id"), nil
}

// InternalPush là kênh đẩy nội bộ cho bản cách ly (không ra Internet): gửi tới cổng đẩy trong mạng của khách
// hàng `POST {BaseURL}/v1/push` (suy luận về hình thức, wiki/14).
type InternalPush struct {
	HTTP    *http.Client
	BaseURL string
	APIKey  string
}

// Send gửi tới một thiết bị qua cổng nội bộ.
func (p InternalPush) Send(ctx context.Context, m Message) (string, error) {
	body, _ := json.Marshal(map[string]any{"platform": m.Platform, "token": m.To, "title": m.Subject,
		"body": m.Body, "deepLink": m.DeepLink, "high": m.High, "requestId": m.Key})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(p.BaseURL, "/")+"/v1/push",
		bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if p.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+p.APIKey)
	}
	resp, err := client(p.HTTP).Do(req)
	if err != nil {
		return "", Transient{"CONNECT"}
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusGone {
		return "", ErrUnregistered
	}
	if err := status(resp.StatusCode, "PUSH"); err != nil {
		return "", err
	}
	var out struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(io.LimitReader(resp.Body, 16<<10)).Decode(&out)
	return out.ID, nil
}
