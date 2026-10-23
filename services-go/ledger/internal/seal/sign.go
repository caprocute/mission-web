package seal

import (
	"bytes"
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/digitorus/timestamp"
)

// Signature là chữ ký gốc cell bằng khoá niêm phong của cell.
type Signature struct {
	Value      []byte
	KeyID      string
	CertSHA256 [32]byte
}

// Signer ký thông điệp niêm phong. Bản production gọi esign-service (nhà cung cấp HSM của DV-10-03)
// qua giao diện nội bộ mTLS; giao diện đó chưa có trong hợp đồng nên luồng niêm phong chỉ phụ thuộc
// interface này (suy luận).
type Signer interface {
	Sign(ctx context.Context, msg []byte) (Signature, error)
}

// KeySigner ký ECDSA P-256 bằng khoá giữ trong tiến trình; dùng ở dev và kiểm thử thay SoftHSM.
type KeySigner struct {
	Key   *ecdsa.PrivateKey
	Cert  *x509.Certificate
	KeyID string
}

// Sign ký SHA-256 của msg theo ASN.1 DER.
func (s *KeySigner) Sign(_ context.Context, msg []byte) (Signature, error) {
	d := sha256.Sum256(msg)
	sig, err := ecdsa.SignASN1(rand.Reader, s.Key, d[:])
	if err != nil {
		return Signature{}, err
	}
	return Signature{Value: sig, KeyID: s.KeyID, CertSHA256: sha256.Sum256(s.Cert.Raw)}, nil
}

// LoadKeySigner đọc khoá PKCS#8 và chứng thư PEM.
func LoadKeySigner(keyPath, certPath, keyID string) (*KeySigner, error) {
	kb, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, err
	}
	cb, err := os.ReadFile(certPath)
	if err != nil {
		return nil, err
	}
	kp, _ := pem.Decode(kb)
	cp, _ := pem.Decode(cb)
	if kp == nil || cp == nil {
		return nil, errors.New("seal: tệp khoá hoặc chứng thư không phải PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(kp.Bytes)
	if err != nil {
		return nil, err
	}
	ek, ok := k.(*ecdsa.PrivateKey)
	if !ok {
		return nil, errors.New("seal: khoá niêm phong phải là ECDSA P-256")
	}
	cert, err := x509.ParseCertificate(cp.Bytes)
	if err != nil {
		return nil, err
	}
	return &KeySigner{Key: ek, Cert: cert, KeyID: keyID}, nil
}

// TSA lấy dấu thời gian cho một băm SHA-256.
type TSA interface {
	Timestamp(ctx context.Context, digest [32]byte) (Token, error)
}

// HTTPTSA gửi TimeStampReq (application/timestamp-query) tới dịch vụ cấp dấu thời gian RFC 3161.
type HTTPTSA struct {
	URL    string
	Client *http.Client
}

// Timestamp xin token có nonce và chứng thư TSA, kiểm trạng thái và message imprint của phản hồi.
func (t *HTTPTSA) Timestamp(ctx context.Context, digest [32]byte) (Token, error) {
	req, err := timestamp.CreateRequest(bytes.NewReader(digest[:]), &timestamp.RequestOptions{
		Hash: crypto.SHA256, Certificates: true,
	})
	if err != nil {
		return Token{}, err
	}
	// CreateRequest băm dữ liệu đầu vào; message imprint là SHA-256(digest) (suy luận, xem ParseToken).
	hr, err := http.NewRequestWithContext(ctx, http.MethodPost, t.URL, bytes.NewReader(req))
	if err != nil {
		return Token{}, err
	}
	hr.Header.Set("Content-Type", "application/timestamp-query")
	cl := t.Client
	if cl == nil {
		cl = &http.Client{Timeout: 15 * time.Second}
	}
	res, err := cl.Do(hr)
	if err != nil {
		return Token{}, fmt.Errorf("seal: gọi TSA: %w", err)
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		return Token{}, err
	}
	if res.StatusCode != http.StatusOK {
		return Token{}, fmt.Errorf("seal: TSA trả HTTP %d", res.StatusCode)
	}
	return ParseToken(body, digest)
}
