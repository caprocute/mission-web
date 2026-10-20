// Package crypto mã hoá trường C2 ở tầng ứng dụng (AES-256-GCM) trước khi ghi cột _enc.
// Điểm chờ nền móng NEN-015: thay bằng mã hoá phong bì qua OpenBao Transit và bảng keyring.dek (ADR-30);
// ở đây khoá đọc từ biến môi trường do External Secrets cấp, dek_id là mã cố định của khoá đó.
package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"errors"

	"github.com/google/uuid"
)

// Encryptor mã hoá và giải mã một trường C2; aad gắn bản mã với bản ghi (ví dụ id tenant).
type Encryptor interface {
	Encrypt(plain, aad []byte) (ct []byte, dekID uuid.UUID, err error)
	Decrypt(ct, aad []byte, dekID uuid.UUID) ([]byte, error)
}

// AESGCM hiện thực Encryptor với một khoá 32 byte.
type AESGCM struct {
	aead  cipher.AEAD
	dekID uuid.UUID
}

const algVersion = 1 // byte phiên bản thuật toán đầu bản mã (TL-03 keyring.dek.alg_version = 1: AES-256-GCM)

// NewAESGCM tạo bộ mã hoá; khoá phải dài 32 byte.
func NewAESGCM(key []byte) (*AESGCM, error) {
	if len(key) != 32 {
		return nil, errors.New("crypto: khoá C2 phải dài 32 byte")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	sum := sha256.Sum256(append([]byte("tenant-registry-dek:"), key...))
	id, _ := uuid.FromBytes(sum[:16])
	return &AESGCM{aead: aead, dekID: id}, nil
}

// Encrypt mã hoá.
func (a *AESGCM) Encrypt(plain, aad []byte) ([]byte, uuid.UUID, error) {
	nonce := make([]byte, a.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, uuid.Nil, err
	}
	out := append([]byte{algVersion}, nonce...)
	return a.aead.Seal(out, nonce, plain, aad), a.dekID, nil
}

// Decrypt giải mã.
func (a *AESGCM) Decrypt(ct, aad []byte, dekID uuid.UUID) ([]byte, error) {
	ns := a.aead.NonceSize()
	if dekID != a.dekID || len(ct) < 1+ns || ct[0] != algVersion {
		return nil, errors.New("crypto: bản mã không khớp khoá")
	}
	return a.aead.Open(nil, ct[1:1+ns], ct[1+ns:], aad)
}
