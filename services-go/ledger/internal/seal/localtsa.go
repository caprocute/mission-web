package seal

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"math/big"
	"time"

	"github.com/digitorus/timestamp"
)

// devPolicyOID là OID chính sách của TSA nội bộ dùng ở dev và kiểm thử (nhánh thử nghiệm 1.3.6.1.4.1.0, suy luận).
var devPolicyOID = asn1.ObjectIdentifier{1, 3, 6, 1, 4, 1, 0, 3161, 1}

// LocalTSA cấp TimeStampResp RFC 3161 trong tiến trình; thay TSA thật của esign-service (DV-10-03) ở dev
// và kiểm thử. Token kiểm được bằng `openssl ts -verify` với chứng thư của nó làm CA.
type LocalTSA struct {
	Key  *ecdsa.PrivateKey
	Cert *x509.Certificate
	Now  func() time.Time
}

// NewLocalTSA tạo TSA nội bộ với chứng thư tự ký có EKU timeStamping (critical).
func NewLocalTSA() (*LocalTSA, error) {
	k, c, err := SelfSigned("Mission ledger TSA (dev)", true)
	if err != nil {
		return nil, err
	}
	return &LocalTSA{Key: k, Cert: c}, nil
}

// Timestamp đóng dấu thời gian cho SHA-256(digest), cùng message imprint với HTTPTSA.
func (t *LocalTSA) Timestamp(_ context.Context, digest [32]byte) (Token, error) {
	now := time.Now
	if t.Now != nil {
		now = t.Now
	}
	h := sha256.Sum256(digest[:])
	ts := timestamp.Timestamp{
		HashAlgorithm:     crypto.SHA256,
		HashedMessage:     h[:],
		Time:              now().UTC().Truncate(time.Second),
		Accuracy:          time.Second,
		Policy:            devPolicyOID,
		AddTSACertificate: true,
	}
	der, err := ts.CreateResponseWithOpts(t.Cert, t.Key, crypto.SHA256)
	if err != nil {
		return Token{}, err
	}
	return ParseToken(der, digest)
}

// SelfSigned tạo khoá ECDSA P-256 và chứng thư tự ký (dev, kiểm thử). timestamping bật EKU timeStamping
// critical theo RFC 3161 mục 2.3.
func SelfSigned(cn string, timestamping bool) (*ecdsa.PrivateKey, *x509.Certificate, error) {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 62))
	if err != nil {
		return nil, nil, err
	}
	tpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: cn, Organization: []string{"Mission dev"}},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(1, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	if timestamping {
		// Chứng thư TSA là chứng thư lá: openssl từ chối mục đích timeStamping trên chứng thư CA.
		tpl.IsCA = false
		tpl.KeyUsage = x509.KeyUsageDigitalSignature
		ext, err := asn1.Marshal([]asn1.ObjectIdentifier{{1, 3, 6, 1, 5, 5, 7, 3, 8}})
		if err != nil {
			return nil, nil, err
		}
		tpl.ExtraExtensions = []pkix.Extension{{Id: asn1.ObjectIdentifier{2, 5, 29, 37}, Critical: true, Value: ext}}
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &k.PublicKey, k)
	if err != nil {
		return nil, nil, err
	}
	c, err := x509.ParseCertificate(der)
	return k, c, err
}
