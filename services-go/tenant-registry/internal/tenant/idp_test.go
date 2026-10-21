package tenant_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"mission/services-go/tenant-registry/internal/tenant"
)

func generateCertDER(t testing.TB, notBefore, notAfter time.Time) []byte {
	t.Helper()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "idp.thainguyen.gov.vn"},
		NotBefore:    notBefore,
		NotAfter:     notAfter,
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatal(err)
	}
	return der
}

func publicProberForServer(srv *httptest.Server, publicHost string) *tenant.IdpProber {
	srvAddr := srv.Listener.Addr().String()
	tlsCfg := srv.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	tlsCfg.ServerName = ""
	tlsCfg.InsecureSkipVerify = true
	return &tenant.IdpProber{
		Timeout:   5 * time.Second,
		TLSConfig: tlsCfg,
		LookupIP: func(_ context.Context, host string) ([]netip.Addr, error) {
			if host == publicHost {
				return []netip.Addr{netip.MustParseAddr("203.113.131.10")}, nil
			}
			return nil, fmt.Errorf("unknown host %s", host)
		},
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, network, srvAddr)
		},
	}
}

func TestSSRFProtection(t *testing.T) {
	prober := tenant.NewIdpProber()
	ctx := context.Background()

	ssrfTargets := []string{
		"http://sso.thainguyen.gov.vn",
		"https://127.0.0.1/realms/main",
		"https://localhost/realms/main",
		"https://169.254.169.254/latest/meta-data",
		"https://10.0.0.15/oidc",
		"https://172.16.5.4/oidc",
		"https://172.31.255.1/oidc",
		"https://192.168.1.10/oidc",
		"https://100.64.0.1/oidc",
		"https://[::1]/oidc",
		"https://[fe80::1]/oidc",
		"https://[fd00::1]/oidc",
		"https://keycloak.default.svc.cluster.local/realms/mission",
		"https://internal.svc/oidc",
	}
	for _, target := range ssrfTargets {
		res, err := prober.Probe(ctx, tenant.IdpProbeInput{
			IdpAlias: "ssrf",
			Protocol: "OIDC",
			Issuer:   target,
		})
		var ve *tenant.ValidationError
		if !errors.As(err, &ve) || res.Success {
			t.Fatalf("đích %s phải bị chặn SSRF (ValidationError), nhận res=%+v err=%v", target, res, err)
		}
	}

	// Chặn DNS rebinding: tên miền công khai nhưng phân giải ra IP nội bộ 10.10.1.5 hoặc 169.254.169.254.
	rebindProber := &tenant.IdpProber{
		LookupIP: func(_ context.Context, _ string) ([]netip.Addr, error) {
			return []netip.Addr{netip.MustParseAddr("169.254.169.254")}, nil
		},
	}
	res, err := rebindProber.Probe(ctx, tenant.IdpProbeInput{
		IdpAlias: "rebind",
		Protocol: "OIDC",
		Issuer:   "https://evil-rebind.example.com",
	})
	var ve *tenant.ValidationError
	if !errors.As(err, &ve) || res.Success {
		t.Fatalf("DNS rebinding phải bị chặn: res=%+v err=%v", res, err)
	}
}

func TestOIDCAndSAMLProber(t *testing.T) {
	ctx := context.Background()
	const host = "sso.thainguyen.gov.vn"
	const issuer = "https://" + host + "/realms/thainguyen"

	mode := "ok"
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/realms/thainguyen/.well-known/openid-configuration":
			if mode == "bad_json" {
				_, _ = w.Write([]byte("{broken"))
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"issuer":                 issuer,
				"authorization_endpoint": issuer + "/protocol/openid-connect/auth",
				"token_endpoint":         issuer + "/protocol/openid-connect/token",
				"jwks_uri":               issuer + "/protocol/openid-connect/certs",
			})
		case "/realms/thainguyen/protocol/openid-connect/certs":
			if mode == "empty_jwks" {
				_ = json.NewEncoder(w).Encode(map[string]any{"keys": []any{}})
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"keys": []map[string]any{
					{"kty": "RSA", "kid": "k1", "n": "AQAB", "e": "AQAB"},
				},
			})
		case "/saml/metadata":
			var certDER []byte
			if mode == "expired_cert" {
				certDER = generateCertDER(t, time.Now().Add(-48*time.Hour), time.Now().Add(-1*time.Hour))
			} else {
				certDER = generateCertDER(t, time.Now().Add(-1*time.Hour), time.Now().Add(24*time.Hour))
			}
			certB64 := base64.StdEncoding.EncodeToString(certDER)
			sigB64 := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{0x42}, 64))
			if mode == "bad_sig" {
				sigB64 = "!!!"
			}
			xmlDoc := fmt.Sprintf(`<EntityDescriptor xmlns="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
				<Signature><SignedInfo><SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/></SignedInfo><SignatureValue>%s</SignatureValue></Signature>
				<IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
					<KeyDescriptor use="signing"><KeyInfo><X509Data><X509Certificate>%s</X509Certificate></X509Data></KeyInfo></KeyDescriptor>
					<SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="https://%s/saml/sso"/>
				</IDPSSODescriptor>
			</EntityDescriptor>`, issuer, sigB64, certB64, host)
			w.Header().Set("Content-Type", "application/xml")
			_, _ = w.Write([]byte(xmlDoc))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	prober := publicProberForServer(srv, host)

	// 1. OIDC thành công
	res, err := prober.Probe(ctx, tenant.IdpProbeInput{
		IdpAlias: "oidc-ok",
		Protocol: "OIDC",
		Issuer:   issuer,
	})
	if err != nil || !res.Success || res.Protocol != "OIDC" {
		t.Fatalf("OIDC phải thành công: %+v err=%v", res, err)
	}

	// 2. OIDC discovery hỏng JSON
	mode = "bad_json"
	res, err = prober.Probe(ctx, tenant.IdpProbeInput{IdpAlias: "oidc-bad", Protocol: "OIDC", Issuer: issuer})
	if err != nil || res.Success || !strings.Contains(res.Message, "JSON") {
		t.Fatalf("OIDC JSON hỏng phải trả success=false: %+v err=%v", res, err)
	}

	// 3. OIDC JWKS rỗng
	mode = "empty_jwks"
	res, err = prober.Probe(ctx, tenant.IdpProbeInput{IdpAlias: "oidc-jwks", Protocol: "OIDC", Issuer: issuer})
	if err != nil || res.Success || !strings.Contains(res.Message, "JWKS") {
		t.Fatalf("OIDC JWKS rỗng phải trả success=false: %+v err=%v", res, err)
	}

	// 4. SAML thành công
	mode = "ok"
	res, err = prober.Probe(ctx, tenant.IdpProbeInput{
		IdpAlias:    "saml-ok",
		Protocol:    "SAML",
		Issuer:      issuer,
		MetadataURL: "https://" + host + "/saml/metadata",
	})
	if err != nil || !res.Success || res.Protocol != "SAML" {
		t.Fatalf("SAML phải thành công: %+v err=%v", res, err)
	}

	// 5. SAML chứng chỉ hết hạn
	mode = "expired_cert"
	res, err = prober.Probe(ctx, tenant.IdpProbeInput{
		IdpAlias:    "saml-exp",
		Protocol:    "SAML",
		Issuer:      issuer,
		MetadataURL: "https://" + host + "/saml/metadata",
	})
	if err != nil || res.Success || !strings.Contains(res.Message, "hết hạn") {
		t.Fatalf("SAML chứng chỉ hết hạn phải bị từ chối: %+v err=%v", res, err)
	}

	// 6. SAML chữ ký hỏng
	mode = "bad_sig"
	res, err = prober.Probe(ctx, tenant.IdpProbeInput{
		IdpAlias:    "saml-sig",
		Protocol:    "SAML",
		Issuer:      issuer,
		MetadataURL: "https://" + host + "/saml/metadata",
	})
	if err != nil || res.Success || !strings.Contains(res.Message, "Chữ ký") {
		t.Fatalf("SAML chữ ký hỏng phải bị từ chối: %+v err=%v", res, err)
	}
}

func startMockLDAPSServer(t testing.TB, validDN, validPass string) (addr string, clientTLS *tls.Config, closeFn func()) {
	t.Helper()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "ldap.thainguyen.gov.vn"},
		DNSNames:     []string{"ldap.thainguyen.gov.vn"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatal(err)
	}
	srvCert := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: priv}
	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{srvCert}})
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go handleLDAPSConn(conn, validDN, validPass)
		}
	}()
	pool := x509.NewCertPool()
	parsed, _ := x509.ParseCertificate(der)
	pool.AddCert(parsed)
	return ln.Addr().String(), &tls.Config{RootCAs: pool, ServerName: "ldap.thainguyen.gov.vn"}, func() { _ = ln.Close() }
}

func handleLDAPSConn(conn net.Conn, validDN, validPass string) {
	defer conn.Close()
	buf := make([]byte, 4096)
	// Đọc gói BindRequest
	n, err := conn.Read(buf)
	if err != nil || n == 0 {
		return
	}
	pkt := buf[:n]
	code := byte(0) // success
	diag := ""
	if !bytes.Contains(pkt, []byte(validDN)) || !bytes.Contains(pkt, []byte(validPass)) {
		code = 49 // invalidCredentials
		diag = "sai thông tin xác thực"
	}
	// Gửi BindResponse: SEQUENCE { messageID 1, [APPLICATION 1] { ENUMERATED code, OCTET STRING "", OCTET STRING diag } }
	resPayload := append([]byte{0x0a, 0x01, code, 0x04, 0x00, 0x04, byte(len(diag))}, []byte(diag)...)
	bindResp := append([]byte{0x61, byte(len(resPayload))}, resPayload...)
	msg1 := append([]byte{0x02, 0x01, 0x01}, bindResp...)
	_, _ = conn.Write(append([]byte{0x30, byte(len(msg1))}, msg1...))
	if code != 0 {
		return
	}
	// Đọc SearchRequest
	if _, err := io.ReadAtLeast(conn, buf, 4); err != nil {
		return
	}
	// Gửi SearchResultDone [APPLICATION 5] (0x65) resultCode=0
	donePayload := []byte{0x0a, 0x01, 0x00, 0x04, 0x00, 0x04, 0x00}
	searchDone := append([]byte{0x65, byte(len(donePayload))}, donePayload...)
	msg2 := append([]byte{0x02, 0x01, 0x02}, searchDone...)
	_, _ = conn.Write(append([]byte{0x30, byte(len(msg2))}, msg2...))
}

func TestLDAPSProberAndOpenBaoStore(t *testing.T) {
	ctx := context.Background()
	ldapAddr, clientTLS, closeLDAP := startMockLDAPSServer(t, "cn=readonly,dc=thainguyen,dc=gov,dc=vn", "BiMat@2026")
	defer closeLDAP()

	prober := &tenant.IdpProber{
		Timeout:   5 * time.Second,
		TLSConfig: clientTLS,
		LookupIP: func(_ context.Context, host string) ([]netip.Addr, error) {
			if host == "ldap.thainguyen.gov.vn" {
				return []netip.Addr{netip.MustParseAddr("203.113.131.20")}, nil
			}
			return nil, fmt.Errorf("unknown host %s", host)
		},
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, network, ldapAddr)
		},
	}

	// 1. Không dùng TLS (ldap://) bị từ chối ngay (ValidationError).
	_, err := prober.Probe(ctx, tenant.IdpProbeInput{
		IdpAlias: "ldap-plain",
		Protocol: "LDAP",
		Issuer:   "ldap://ldap.thainguyen.gov.vn:389",
	})
	var ve *tenant.ValidationError
	if !errors.As(err, &ve) {
		t.Fatalf("ldap:// không TLS phải bị từ chối ValidationError, nhận %v", err)
	}

	// 2. LDAPS đúng thông tin tài khoản dịch vụ → thành công, Protocol rỗng trong kết quả để hợp lược đồ OpenAPI.
	res, err := prober.Probe(ctx, tenant.IdpProbeInput{
		IdpAlias:     "ldap-ok",
		Protocol:     "LDAP",
		Issuer:       "ldaps://ldap.thainguyen.gov.vn:636",
		BindDN:       "cn=readonly,dc=thainguyen,dc=gov,dc=vn",
		BindPassword: "BiMat@2026",
		BaseDN:       "dc=thainguyen,dc=gov,dc=vn",
	})
	if err != nil || !res.Success || res.Protocol != "" {
		t.Fatalf("LDAPS phải thành công: %+v err=%v", res, err)
	}

	// 3. LDAPS sai mật khẩu → trả success=false.
	res, err = prober.Probe(ctx, tenant.IdpProbeInput{
		IdpAlias:     "ldap-wrong",
		Protocol:     "LDAP",
		Issuer:       "ldaps://ldap.thainguyen.gov.vn:636",
		BindDN:       "cn=readonly,dc=thainguyen,dc=gov,dc=vn",
		BindPassword: "SaiMatKhau",
	})
	if err != nil || res.Success || !strings.Contains(res.Message, "49") {
		t.Fatalf("LDAPS sai mật khẩu phải trả success=false: %+v err=%v", res, err)
	}

	// 4. Kiểm thử OpenBaoSecretStore qua HTTP server giả lập KV v2.
	storedSecrets := map[string]map[string]string{}
	baoSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Vault-Token") != "bao-test-token" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		switch r.Method {
		case http.MethodPost:
			var body struct {
				Data map[string]string `json:"data"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			storedSecrets[r.URL.Path] = body.Data
			w.WriteHeader(http.StatusOK)
		case http.MethodGet:
			d, ok := storedSecrets[r.URL.Path]
			if !ok {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"data": map[string]any{"data": d}})
		case http.MethodDelete:
			delete(storedSecrets, r.URL.Path)
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	defer baoSrv.Close()

	bao := tenant.NewOpenBaoSecretStore(baoSrv.URL, "bao-test-token", baoSrv.Client())
	tid := uuid.New()
	ref, err := bao.PutIdpSecret(ctx, tid, "sso-tinh", map[string]string{"clientSecret": "TopSecret#999"})
	if err != nil || !strings.HasPrefix(ref, "openbao://secret/data/mission/tenants/") {
		t.Fatalf("PutIdpSecret lỗi: ref=%s err=%v", ref, err)
	}
	got, err := bao.GetIdpSecret(ctx, ref)
	if err != nil || got["clientSecret"] != "TopSecret#999" {
		t.Fatalf("GetIdpSecret lỗi: %+v err=%v", got, err)
	}
	if err := bao.DeleteIdpSecret(ctx, ref); err != nil {
		t.Fatalf("DeleteIdpSecret lỗi: %v", err)
	}
}
