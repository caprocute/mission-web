package api_test

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
)

func TestConfigTenantIdpSecretAndTestEndpoint(t *testing.T) {
	f := newFixture(t, db.SvcDSN)
	ctx := context.Background()

	// Máy chủ OIDC mẫu có chứng chỉ TLS cục bộ.
	oidcSrv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/.well-known/openid-configuration":
			w.Header().Set("Content-Type", "application/json")
			base := "https://idp.tinh.gov.vn"
			_ = json.NewEncoder(w).Encode(map[string]any{
				"issuer":                 base,
				"authorization_endpoint": base + "/protocol/openid-connect/auth",
				"token_endpoint":         base + "/protocol/openid-connect/token",
				"jwks_uri":               base + "/protocol/openid-connect/certs",
			})
		case "/protocol/openid-connect/certs":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"keys":[{"kty":"RSA","kid":"k1","use":"sig","n":"AQAB","e":"AQAB"}]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer oidcSrv.Close()

	// Chuyển hướng kết nối tới máy chủ TLS mẫu với IP công khai giả lập để qua kiểm tra SSRF.
	srvAddr := oidcSrv.Listener.Addr().String()
	pool := x509.NewCertPool()
	pool.AddCert(oidcSrv.Certificate())
	f.srv.Tenants.Prober = &tenant.IdpProber{
		Timeout:   3 * time.Second,
		TLSConfig: &tls.Config{RootCAs: pool, InsecureSkipVerify: true},
		LookupIP: func(_ context.Context, host string) ([]netip.Addr, error) {
			if host == "idp.tinh.gov.vn" || host == "adfs.tinh.gov.vn" {
				return []netip.Addr{netip.MustParseAddr("203.113.130.10")}, nil
			}
			return nil, fmt.Errorf("không phân giải được %s", host)
		},
		DialContext: oidcSrv.Client().Transport.(*http.Transport).DialContext,
	}
	// Nối DialContext của Prober về địa chỉ listener của httptest.Server.
	f.srv.Tenants.Prober.DialContext = func(ctx context.Context, network, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, srvAddr)
	}

	v := mustCreate(t, f, uniq("idp"), "CHINH_QUYEN", 15000)
	ta := tenantAdminToken(v.Code)

	// 1. Lưu cấu hình IdP kèm clientSecret nhạy cảm qua PATCH.
	secretVal := "bi-mat-oidc-sieu-nhay-cam-999"
	patchBody := fmt.Sprintf(`{
		"idps":[
			{
				"idpAlias":"oidc-tinh",
				"protocol":"OIDC",
				"issuer":"https://idp.tinh.gov.vn",
				"clientId":"mission-web",
				"clientSecret":%q,
				"isVneid":true,
				"emailDomains":["%s.gov.vn"],
				"attributeMapping":{"cccd":"citizen_id","ho_ten":"name"}
			}
		]
	}`, secretVal, v.Code)
	rec := patchX(t, f, ta, v.ID, v.RowVersion, patchBody, false)
	if rec.Code != http.StatusOK {
		t.Fatalf("PATCH idps: %d %s", rec.Code, rec.Body.String())
	}
	// Không bao giờ trả ngược clientSecret trong thân phản hồi.
	if strings.Contains(rec.Body.String(), secretVal) || strings.Contains(rec.Body.String(), "clientSecret") {
		t.Fatalf("phản hồi làm lộ clientSecret: %s", rec.Body.String())
	}

	// Kiểm tra kho bí mật OpenBao đã lưu clientSecret và bảng siêu dữ liệu chỉ giữ tham chiếu openbao://.
	meta, err := store.LoadIdpMeta(ctx, f.pool, v.ID)
	if err != nil {
		t.Fatalf("LoadIdpMeta: %v", err)
	}
	entry := meta["oidc-tinh"]
	if !strings.HasPrefix(entry.SecretRef, "openbao://secret/data/mission/tenants/"+v.ID.String()+"/idp/oidc-tinh") {
		t.Fatalf("SecretRef sai: %q", entry.SecretRef)
	}
	sec, err := f.srv.Tenants.Secrets.GetIdpSecret(ctx, entry.SecretRef)
	if err != nil || sec["clientSecret"] != secretVal {
		t.Fatalf("OpenBao không lưu đúng clientSecret: %v %v", sec, err)
	}

	// 2. Gọi POST /api/v1/config/tenants/{id}/idp/test theo idpAlias đã lưu.
	testReq := []byte(`{"idpAlias":"oidc-tinh"}`)
	if err := specs.ValidateRequest(configYAML, "/config/tenants/{id}/idp/test", "post", "application/json", testReq); err != nil {
		t.Fatalf("thân yêu cầu lệch hợp đồng: %v", err)
	}
	rec = f.do(t, http.MethodPost, "/api/v1/config/tenants/"+v.ID.String()+"/idp/test", ta, testReq, idemKey())
	if rec.Code != http.StatusOK {
		t.Fatalf("testConfigTenantIdp: %d %s", rec.Code, rec.Body.String())
	}
	conform(t, configYAML, "/config/tenants/{id}/idp/test", "post", rec)
	res := decode[tenant.IdpTestResult](t, rec)
	if !res.Success || res.Protocol != "OIDC" || res.CheckedAt == "" {
		t.Fatalf("kết quả kiểm kết nối OIDC sai: %+v", res)
	}

	// 3. GET /api/v1/config/tenants/{id} phải phản ánh lastTestedAt và lastTestOk = true.
	getRec := f.do(t, http.MethodGet, "/api/v1/config/tenants/"+v.ID.String(), ta, nil, nil)
	if getRec.Code != http.StatusOK {
		t.Fatalf("GET config tenant: %d", getRec.Code)
	}
	conform(t, configYAML, "/config/tenants/{id}", "get", getRec)
	var detail struct {
		RowVersion int `json:"rowVersion"`
		Idps       []struct {
			IdpAlias     string  `json:"idpAlias"`
			LastTestedAt *string `json:"lastTestedAt"`
			LastTestOk   *bool   `json:"lastTestOk"`
		} `json:"idps"`
	}
	_ = json.Unmarshal(getRec.Body.Bytes(), &detail)
	if len(detail.Idps) != 1 || detail.Idps[0].LastTestedAt == nil || detail.Idps[0].LastTestOk == nil || !*detail.Idps[0].LastTestOk {
		t.Fatalf("chưa cập nhật lastTestedAt/lastTestOk: %+v", detail.Idps)
	}

	// 4. Kiểm kết nối SAML nội tuyến (inline idp) và chặn SSRF.
	samlXML := buildTestSAMLMetadataXML(t, "https://adfs.tinh.gov.vn", time.Now().Add(-time.Hour), time.Now().Add(24*time.Hour))
	inlineReq, _ := json.Marshal(map[string]any{
		"idp": map[string]any{
			"idpAlias":    "ad-tinh",
			"protocol":    "SAML",
			"issuer":      "https://adfs.tinh.gov.vn",
			"isVneid":     false,
			"metadataXml": samlXML,
		},
	})
	if err := specs.ValidateRequest(configYAML, "/config/tenants/{id}/idp/test", "post", "application/json", inlineReq); err != nil {
		t.Fatalf("thân yêu cầu SAML lệch hợp đồng: %v", err)
	}
	rec = f.do(t, http.MethodPost, "/api/v1/config/tenants/"+v.ID.String()+"/idp/test", adminToken(), inlineReq, idemKey())
	if rec.Code != http.StatusOK {
		t.Fatalf("test SAML: %d %s", rec.Code, rec.Body.String())
	}
	conform(t, configYAML, "/config/tenants/{id}/idp/test", "post", rec)
	samlRes := decode[tenant.IdpTestResult](t, rec)
	if !samlRes.Success || samlRes.Protocol != "SAML" {
		t.Fatalf("SAML phải thành công: %+v", samlRes)
	}

	// 5. Chặn SSRF vào địa chỉ nội bộ khi thử kết nối trực tiếp (422 CONFIG-422-VALIDATION) và ca discovery lỗi trả 200 kèm success=false.
	ssrfReq := []byte(`{"idp":{"idpAlias":"ssrf","protocol":"OIDC","issuer":"https://169.254.169.254/latest/meta-data","isVneid":false}}`)
	rec = f.do(t, http.MethodPost, "/api/v1/config/tenants/"+v.ID.String()+"/idp/test", ta, ssrfReq, idemKey())
	p := expectProblem(t, rec, 422, "CONFIG-422-VALIDATION")
	if len(p.Errors) == 0 || !strings.Contains(restErrMsg(rec), "SSRF") {
		t.Fatalf("SSRF phải bị chặn với thông báo rõ: %s", rec.Body.String())
	}
	conform(t, configYAML, "/config/tenants/{id}/idp/test", "post", rec)

	brokenReq := []byte(`{"idp":{"idpAlias":"oidc-tinh","protocol":"OIDC","issuer":"https://idp.tinh.gov.vn/khong-co","isVneid":false}}`)
	rec = f.do(t, http.MethodPost, "/api/v1/config/tenants/"+v.ID.String()+"/idp/test", ta, brokenReq, idemKey())
	if rec.Code != http.StatusOK {
		t.Fatalf("discovery hỏng phải trả 200 kèm success=false: %d %s", rec.Code, rec.Body.String())
	}
	conform(t, configYAML, "/config/tenants/{id}/idp/test", "post", rec)
	brokenRes := decode[tenant.IdpTestResult](t, rec)
	if brokenRes.Success {
		t.Fatalf("discovery hỏng phải trả success=false: %+v", brokenRes)
	}

	// 6. Chặn SSRF và ràng buộc VNeID/tên miền trùng ngay lúc PATCH.
	expectProblem(t, f.do(t, http.MethodPatch, "/api/v1/config/tenants/"+v.ID.String(), ta,
		[]byte(`{"idps":[{"idpAlias":"bad","protocol":"OIDC","issuer":"https://10.0.0.1/oidc","isVneid":false}]}`),
		idemKey("Content-Type", "application/merge-patch+json", "If-Match", fmt.Sprintf(`"%d"`, detail.RowVersion))), 422, "CONFIG-422-VALIDATION")

	expectProblem(t, f.do(t, http.MethodPatch, "/api/v1/config/tenants/"+v.ID.String(), ta,
		[]byte(`{"idps":[{"idpAlias":"vneid","protocol":"OIDC","issuer":"https://sso.vneid.gov.vn","isVneid":true,"attributeMapping":{"ho_ten":"name"}}]}`),
		idemKey("Content-Type", "application/merge-patch+json", "If-Match", fmt.Sprintf(`"%d"`, detail.RowVersion))), 422, "CONFIG-422-VALIDATION")

	// Tenant khác không được trùng tên miền email SSO.
	other := mustCreate(t, f, uniq("idpo"), "CHINH_QUYEN", 10000)
	otherTA := tenantAdminToken(other.Code)
	dupDomainBody := fmt.Sprintf(`{"idps":[{"idpAlias":"sso2","protocol":"OIDC","issuer":"https://idp2.gov.vn","isVneid":false,"emailDomains":["%s.gov.vn"]}]}`, v.Code)
	expectProblem(t, f.do(t, http.MethodPatch, "/api/v1/config/tenants/"+other.ID.String(), otherTA,
		[]byte(dupDomainBody),
		idemKey("Content-Type", "application/merge-patch+json", "If-Match", fmt.Sprintf(`"%d"`, other.RowVersion))), 422, "CONFIG-422-VALIDATION")

	// Lỗi đầu vào và phân quyền trên /idp/test.
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants/"+v.ID.String()+"/idp/test", ta, []byte(`{}`), idemKey()), 422, "CONFIG-422-VALIDATION")
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants/"+v.ID.String()+"/idp/test", ta, []byte(`{"idpAlias":"khong-ton-tai"}`), idemKey()), 422, "CONFIG-422-VALIDATION")
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants/"+other.ID.String()+"/idp/test", ta, []byte(`{"idpAlias":"oidc-tinh"}`), idemKey()), 403, "COMMON-403-OUT-OF-SCOPE")
	expectProblem(t, f.do(t, http.MethodPost, "/api/v1/config/tenants/"+uuid.NewString()+"/idp/test", adminToken(), []byte(`{"idpAlias":"oidc-tinh"}`), idemKey()), 404, "")
}

func restErrMsg(rec *httptest.ResponseRecorder) string {
	return rec.Body.String()
}

func buildTestSAMLMetadataXML(t testing.TB, entityID string, notBefore, notAfter time.Time) string {
	t.Helper()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	tpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "adfs.tinh.gov.vn"},
		NotBefore:             notBefore,
		NotAfter:              notAfter,
		KeyUsage:              x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &priv.PublicKey, priv)
	if err != nil {
		t.Fatal(err)
	}
	_ = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	b64 := base64.StdEncoding.EncodeToString(der)
	return fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<EntityDescriptor xmlns="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
  <IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
    <KeyDescriptor use="signing">
      <KeyInfo xmlns="http://www.w3.org/2000/09/xmldsig#">
        <X509Data><X509Certificate>%s</X509Certificate></X509Data>
      </KeyInfo>
    </KeyDescriptor>
    <SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST" Location="%s/adfs/ls/"/>
  </IDPSSODescriptor>
</EntityDescriptor>`, entityID, b64, entityID)
}
