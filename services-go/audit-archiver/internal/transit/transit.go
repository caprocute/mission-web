// Package transit ký kê khai lô kiểm toán bằng OpenBao Transit (INF-13, INF-24): khoá ký ed25519 riêng của
// audit-archiver nằm trong OpenBao, không xuất được; dịch vụ chỉ gửi byte cần ký và nhận chữ ký dạng
// "vault:v<phiên bản>:<base64>". Khoá công khai đọc được qua API keys nên công cụ kiểm độc lập chỉ cần khoá công khai.
//
// Xác thực tới OpenBao theo thứ tự: tệp token (BAO_TOKEN_FILE, do agent hay CSI ghi), đăng nhập Kubernetes bằng
// token tài khoản dịch vụ (BAO_K8S_ROLE), hoặc biến BAO_TOKEN (chỉ dùng ở dev và kiểm thử).
package transit

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Client là client Transit tối giản (không dùng SDK để giữ ảnh nhỏ và ít phụ thuộc).
type Client struct {
	Addr  string // ví dụ https://openbao.secure-data.svc:8200
	Mount string // mặc định "transit"
	Key   string // tên khoá ký
	HTTP  *http.Client

	// Nguồn token; đặt một trong các trường.
	TokenFile string
	Token     string
	K8sRole   string
	K8sMount  string // mặc định "kubernetes"
	K8sJWT    string // đường dẫn token tài khoản dịch vụ

	mu      sync.Mutex
	cached  string
	expires time.Time
}

func (c *Client) httpClient() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return &http.Client{Timeout: 15 * time.Second}
}

func (c *Client) mount() string {
	if c.Mount == "" {
		return "transit"
	}
	return c.Mount
}

func (c *Client) token(ctx context.Context) (string, error) {
	switch {
	case c.TokenFile != "":
		b, err := os.ReadFile(c.TokenFile)
		if err != nil {
			return "", fmt.Errorf("transit: đọc tệp token: %w", err)
		}
		return strings.TrimSpace(string(b)), nil
	case c.K8sRole != "":
		c.mu.Lock()
		defer c.mu.Unlock()
		if c.cached != "" && time.Now().Before(c.expires) {
			return c.cached, nil
		}
		jwtPath := c.K8sJWT
		if jwtPath == "" {
			jwtPath = "/var/run/secrets/kubernetes.io/serviceaccount/token"
		}
		jwt, err := os.ReadFile(jwtPath)
		if err != nil {
			return "", fmt.Errorf("transit: đọc token tài khoản dịch vụ: %w", err)
		}
		mount := c.K8sMount
		if mount == "" {
			mount = "kubernetes"
		}
		var out struct {
			Auth struct {
				ClientToken   string `json:"client_token"`
				LeaseDuration int    `json:"lease_duration"`
			} `json:"auth"`
		}
		body := map[string]string{"role": c.K8sRole, "jwt": strings.TrimSpace(string(jwt))}
		if err := c.do(ctx, http.MethodPost, "/v1/auth/"+mount+"/login", "", body, &out); err != nil {
			return "", err
		}
		if out.Auth.ClientToken == "" {
			return "", errors.New("transit: đăng nhập Kubernetes không trả token")
		}
		ttl := time.Duration(out.Auth.LeaseDuration) * time.Second
		c.cached, c.expires = out.Auth.ClientToken, time.Now().Add(ttl*3/4)
		return c.cached, nil
	case c.Token != "":
		return c.Token, nil
	}
	return "", errors.New("transit: chưa cấu hình nguồn token OpenBao")
}

func (c *Client) do(ctx context.Context, method, path, token string, in, out any) error {
	var body io.Reader
	if in != nil {
		b, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(c.Addr, "/")+path, body)
	if err != nil {
		return err
	}
	if token != "" {
		req.Header.Set("X-Vault-Token", token)
	}
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.httpClient().Do(req)
	if err != nil {
		return fmt.Errorf("transit: gọi OpenBao: %w", err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode/100 != 2 {
		// Thân lỗi của OpenBao chỉ có thông điệp, không có token hay khoá; vẫn cắt ngắn cho nhật ký.
		msg := string(data)
		if len(msg) > 300 {
			msg = msg[:300]
		}
		return fmt.Errorf("transit: OpenBao %s %s trả %d: %s", method, path, resp.StatusCode, msg)
	}
	if out != nil {
		return json.Unmarshal(data, out)
	}
	return nil
}

// Sign ký các byte bằng khoá Transit; trả chữ ký "vault:v<n>:<base64>".
func (c *Client) Sign(ctx context.Context, data []byte) (string, error) {
	tok, err := c.token(ctx)
	if err != nil {
		return "", err
	}
	var out struct {
		Data struct {
			Signature string `json:"signature"`
		} `json:"data"`
	}
	in := map[string]string{"input": base64.StdEncoding.EncodeToString(data)}
	if err := c.do(ctx, http.MethodPost, "/v1/"+c.mount()+"/sign/"+c.Key, tok, in, &out); err != nil {
		return "", err
	}
	if !strings.HasPrefix(out.Data.Signature, "vault:v") {
		return "", errors.New("transit: chữ ký trả về sai dạng")
	}
	return out.Data.Signature, nil
}

// PublicKeys đọc khoá công khai ed25519 theo phiên bản của khoá Transit.
func (c *Client) PublicKeys(ctx context.Context) (map[int]ed25519.PublicKey, error) {
	tok, err := c.token(ctx)
	if err != nil {
		return nil, err
	}
	var out struct {
		Data struct {
			Type string `json:"type"`
			Keys map[string]struct {
				PublicKey string `json:"public_key"`
			} `json:"keys"`
		} `json:"data"`
	}
	if err := c.do(ctx, http.MethodGet, "/v1/"+c.mount()+"/keys/"+c.Key, tok, nil, &out); err != nil {
		return nil, err
	}
	if out.Data.Type != "ed25519" {
		return nil, fmt.Errorf("transit: khoá %q là %q, cần ed25519", c.Key, out.Data.Type)
	}
	keys := map[int]ed25519.PublicKey{}
	for v, k := range out.Data.Keys {
		n, err := strconv.Atoi(v)
		if err != nil {
			continue
		}
		raw, err := base64.StdEncoding.DecodeString(k.PublicKey)
		if err != nil || len(raw) != ed25519.PublicKeySize {
			return nil, fmt.Errorf("transit: khoá công khai phiên bản %s hỏng", v)
		}
		keys[n] = ed25519.PublicKey(raw)
	}
	return keys, nil
}

// PublicKeyFile là dạng tệp khoá công khai giao cho kiểm toán viên: {"khoa": "<tên>", "phienBan": {"1": "<base64>"}}.
type PublicKeyFile struct {
	Khoa     string            `json:"khoa"`
	PhienBan map[string]string `json:"phienBan"`
}

// EncodePublicKeys ghi tập khoá công khai ra dạng tệp cho kiểm toán viên.
func EncodePublicKeys(name string, keys map[int]ed25519.PublicKey) ([]byte, error) {
	f := PublicKeyFile{Khoa: name, PhienBan: map[string]string{}}
	for v, k := range keys {
		f.PhienBan[strconv.Itoa(v)] = base64.StdEncoding.EncodeToString(k)
	}
	return json.MarshalIndent(f, "", "  ")
}

// DecodePublicKeys đọc tệp khoá công khai.
func DecodePublicKeys(b []byte) (map[int]ed25519.PublicKey, error) {
	var f PublicKeyFile
	if err := json.Unmarshal(b, &f); err != nil {
		return nil, fmt.Errorf("transit: tệp khoá công khai hỏng: %w", err)
	}
	keys := map[int]ed25519.PublicKey{}
	for v, s := range f.PhienBan {
		n, err := strconv.Atoi(v)
		raw, err2 := base64.StdEncoding.DecodeString(s)
		if err != nil || err2 != nil || len(raw) != ed25519.PublicKeySize {
			return nil, fmt.Errorf("transit: khoá phiên bản %q hỏng", v)
		}
		keys[n] = ed25519.PublicKey(raw)
	}
	if len(keys) == 0 {
		return nil, errors.New("transit: tệp không có khoá công khai nào")
	}
	return keys, nil
}

// Verify kiểm chữ ký Transit ed25519 trên msg bằng khoá công khai theo phiên bản ghi trong chữ ký.
func Verify(keys map[int]ed25519.PublicKey, msg []byte, sig string) error {
	rest, ok := strings.CutPrefix(strings.TrimSpace(sig), "vault:v")
	if !ok {
		return errors.New("chữ ký sai dạng")
	}
	ver, b64, ok := strings.Cut(rest, ":")
	if !ok {
		return errors.New("chữ ký sai dạng")
	}
	n, err := strconv.Atoi(ver)
	if err != nil {
		return errors.New("chữ ký sai dạng phiên bản")
	}
	pub, ok := keys[n]
	if !ok {
		return fmt.Errorf("không có khoá công khai phiên bản %d", n)
	}
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return errors.New("chữ ký không phải base64")
	}
	if !ed25519.Verify(pub, msg, raw) {
		return errors.New("chữ ký không khớp")
	}
	return nil
}
