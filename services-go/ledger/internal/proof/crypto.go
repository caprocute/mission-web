package proof

import (
	"bytes"
	"crypto"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"errors"
	"fmt"
	"time"

	"github.com/digitorus/timestamp"
)

// SignedMessage là thông điệp được ký: cell_root || prev_cell_root || window_end (dạng chuẩn hoá
// YYYY-MM-DDThh:mm:ss.ffffffZ, TL-03 cột signature).
func SignedMessage(cellRoot, prevCellRoot [32]byte, windowEnd time.Time) []byte {
	b := make([]byte, 0, 64+27)
	b = append(b, cellRoot[:]...)
	b = append(b, prevCellRoot[:]...)
	return append(b, windowEnd.UTC().Format("2006-01-02T15:04:05.000000Z")...)
}

// VerifySignature kiểm chữ ký bằng khoá công khai trong chứng thư.
func VerifySignature(cert *x509.Certificate, msg, sig []byte) bool {
	pub, ok := cert.PublicKey.(*ecdsa.PublicKey)
	if !ok {
		return false
	}
	d := sha256.Sum256(msg)
	return ecdsa.VerifyASN1(pub, d[:], sig)
}

// Token là dấu thời gian RFC 3161 đã nhận.
type Token struct {
	DER     []byte
	GenTime time.Time
	Serial  string
}

// ParseToken kiểm TimeStampResp (DER): trạng thái được cấp, chữ ký CMS của TSA hợp lệ và message
// imprint đúng SHA-256(digest).
func ParseToken(der []byte, digest [32]byte) (Token, error) {
	ts, err := timestamp.ParseResponse(der)
	if err != nil {
		return Token{}, fmt.Errorf("proof: phản hồi TSA không hợp lệ: %w", err)
	}
	want := sha256.Sum256(digest[:])
	if ts.HashAlgorithm != crypto.SHA256 || !bytes.Equal(ts.HashedMessage, want[:]) {
		return Token{}, errors.New("proof: token TSA không khớp gốc cần đóng dấu")
	}
	return Token{DER: der, GenTime: ts.Time.UTC(), Serial: ts.SerialNumber.String()}, nil
}
