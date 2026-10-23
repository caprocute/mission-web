package pushmock

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

// DevCredentials là khoá sinh lúc chạy cho môi trường dev: tài khoản dịch vụ FCM giả và khoá .p8 APNs giả. Khoá
// chỉ có giá trị với bản giả lập, không phải bí mật và không được commit (thư mục đích nằm ngoài repo).
type DevCredentials struct {
	ServiceAccountFile string
	APNsKeyFile        string
	FCMPublic          *rsa.PublicKey
	APNsPublic         *ecdsa.PublicKey
}

// WriteDevCredentials sinh cặp khoá và ghi `service-account.json` (token_uri trỏ về bản giả lập) và
// `apns-key.p8` vào dir.
func WriteDevCredentials(dir, baseURL, projectID string) (DevCredentials, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return DevCredentials{}, err
	}
	rk, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return DevCredentials{}, err
	}
	rder, _ := x509.MarshalPKCS8PrivateKey(rk)
	sa := map[string]string{
		"type": "service_account", "project_id": projectID,
		"client_email": "mission-push@" + projectID + ".iam.gserviceaccount.com",
		"private_key":  string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: rder})),
		"token_uri":    baseURL + "/token",
	}
	saFile := filepath.Join(dir, "service-account.json")
	b, _ := json.MarshalIndent(sa, "", "  ")
	if err := os.WriteFile(saFile, b, 0o600); err != nil {
		return DevCredentials{}, err
	}
	ek, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return DevCredentials{}, err
	}
	eder, _ := x509.MarshalPKCS8PrivateKey(ek)
	p8 := filepath.Join(dir, "apns-key.p8")
	if err := os.WriteFile(p8, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: eder}), 0o600); err != nil {
		return DevCredentials{}, err
	}
	return DevCredentials{ServiceAccountFile: saFile, APNsKeyFile: p8, FCMPublic: &rk.PublicKey, APNsPublic: &ek.PublicKey}, nil
}

// SelfSignedTLS sinh chứng thư tự ký cho cổng APNs giả (APNs thật chỉ nhận HTTP/2 qua TLS) và ghi
// `ca-bundle.pem` gồm kho tin cậy của hệ điều hành (nếu đọc được) cộng chứng thư này, để notification-service
// tin cậy bản giả lập qua biến SSL_CERT_FILE mà vẫn tin các máy chủ khác.
func SelfSignedTLS(dir string, hosts []string) (tls.Certificate, error) {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	tpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: "push-mock"},
		NotBefore:    time.Now().Add(-time.Hour), NotAfter: time.Now().Add(365 * 24 * time.Hour),
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IsCA:        true, BasicConstraintsValid: true,
	}
	for _, h := range hosts {
		if ip := net.ParseIP(h); ip != nil {
			tpl.IPAddresses = append(tpl.IPAddresses, ip)
		} else {
			tpl.DNSNames = append(tpl.DNSNames, h)
		}
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &k.PublicKey, k)
	if err != nil {
		return tls.Certificate{}, err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	if dir != "" {
		var bundle []byte
		for _, f := range []string{"/etc/ssl/certs/ca-certificates.crt", "/etc/pki/tls/certs/ca-bundle.crt", "/etc/ssl/cert.pem"} {
			if b, err := os.ReadFile(f); err == nil {
				bundle = append(b, '\n')
				break
			}
		}
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return tls.Certificate{}, err
		}
		if err := os.WriteFile(filepath.Join(dir, "ca-bundle.pem"), append(bundle, certPEM...), 0o644); err != nil {
			return tls.Certificate{}, err
		}
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: k, Leaf: mustParse(der)}, nil
}

func mustParse(der []byte) *x509.Certificate {
	c, _ := x509.ParseCertificate(der)
	return c
}
