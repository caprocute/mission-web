package transit

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakeBao giả lập ba API Transit và đăng nhập Kubernetes mà audit-archiver dùng.
func fakeBao(t *testing.T, priv ed25519.PrivateKey) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/auth/kubernetes/login" {
			var in map[string]string
			_ = json.NewDecoder(r.Body).Decode(&in)
			if in["role"] != "audit-archiver" || in["jwt"] != "jwt-thu" {
				w.WriteHeader(403)
				return
			}
			_, _ = w.Write([]byte(`{"auth":{"client_token":"tok-k8s","lease_duration":3600}}`))
			return
		}
		if tok := r.Header.Get("X-Vault-Token"); tok != "tok-thu" && tok != "tok-k8s" {
			w.WriteHeader(403)
			_, _ = w.Write([]byte(`{"errors":["permission denied"]}`))
			return
		}
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/v1/transit/sign/audit-archiver":
			var in map[string]string
			_ = json.NewDecoder(r.Body).Decode(&in)
			msg, _ := base64.StdEncoding.DecodeString(in["input"])
			sig := ed25519.Sign(priv, msg)
			_ = json.NewEncoder(w).Encode(map[string]any{"data": map[string]string{"signature": "vault:v1:" + base64.StdEncoding.EncodeToString(sig)}})
		case r.Method == http.MethodGet && r.URL.Path == "/v1/transit/keys/audit-archiver":
			pub := priv.Public().(ed25519.PublicKey)
			_ = json.NewEncoder(w).Encode(map[string]any{"data": map[string]any{"type": "ed25519", "exportable": false,
				"keys": map[string]any{"1": map[string]string{"public_key": base64.StdEncoding.EncodeToString(pub)}}}})
		default:
			w.WriteHeader(404)
		}
	}))
}

func TestKyVaKiemChuKy(t *testing.T) {
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	srv := fakeBao(t, priv)
	defer srv.Close()
	c := &Client{Addr: srv.URL, Key: "audit-archiver", Token: "tok-thu"}
	ctx := context.Background()
	msg := []byte(`{"phienBan":1}`)
	sig, err := c.Sign(ctx, msg)
	if err != nil {
		t.Fatal(err)
	}
	keys, err := c.PublicKeys(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := Verify(keys, msg, sig); err != nil {
		t.Fatalf("chữ ký đúng bị từ chối: %v", err)
	}
	if Verify(keys, []byte(`{"phienBan":2}`), sig) == nil {
		t.Fatal("kê khai bị sửa vẫn qua kiểm chữ ký")
	}
	if Verify(keys, msg, strings.Replace(sig, "vault:v1:", "vault:v2:", 1)) == nil {
		t.Fatal("phiên bản khoá không có vẫn qua")
	}
	if Verify(keys, msg, "khong-phai-chu-ky") == nil {
		t.Fatal("chữ ký sai dạng vẫn qua")
	}
	// Tệp khoá công khai cho kiểm toán viên đọc lại được và kiểm được chữ ký.
	f, _ := EncodePublicKeys("audit-archiver", keys)
	k2, err := DecodePublicKeys(f)
	if err != nil || Verify(k2, msg, sig) != nil {
		t.Fatalf("tệp khoá công khai không dùng được: %v", err)
	}
	if _, err := DecodePublicKeys([]byte(`{"khoa":"x","phienBan":{}}`)); err == nil {
		t.Fatal("tệp không có khoá phải bị từ chối")
	}
	// Token sai: lỗi không lộ token.
	bad := &Client{Addr: srv.URL, Key: "audit-archiver", Token: "tok-sai"}
	if _, err := bad.Sign(ctx, msg); err == nil || strings.Contains(err.Error(), "tok-sai") {
		t.Fatalf("token sai phải lỗi và không lộ token: %v", err)
	}
}

func TestDangNhapKubernetesVaTepToken(t *testing.T) {
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	srv := fakeBao(t, priv)
	defer srv.Close()
	dir := t.TempDir()
	jwt := filepath.Join(dir, "token")
	_ = os.WriteFile(jwt, []byte("jwt-thu\n"), 0o600)
	c := &Client{Addr: srv.URL, Key: "audit-archiver", K8sRole: "audit-archiver", K8sJWT: jwt}
	if _, err := c.Sign(context.Background(), []byte("x")); err != nil {
		t.Fatalf("đăng nhập Kubernetes: %v", err)
	}
	tf := filepath.Join(dir, "bao-token")
	_ = os.WriteFile(tf, []byte("tok-thu\n"), 0o600)
	c2 := &Client{Addr: srv.URL, Key: "audit-archiver", TokenFile: tf}
	if _, err := c2.Sign(context.Background(), []byte("x")); err != nil {
		t.Fatalf("tệp token: %v", err)
	}
	if _, err := (&Client{Addr: srv.URL, Key: "audit-archiver"}).Sign(context.Background(), []byte("x")); err == nil {
		t.Fatal("không có nguồn token phải lỗi")
	}
}
