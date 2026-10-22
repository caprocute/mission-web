// Package crypt mã hoá phong bì trường C2 và tính chỉ mục mù cho notification-service theo đúng định dạng của
// vt-platform-starter (wiki trang 20, TL-03): bản mã `0x01 || dek_ref 16B || nonce 12B || ciphertext || tag 16B`
// bằng AES-256-GCM, AAD `tenant_id|schema.bảng.cột|id dòng`; chỉ mục mù HMAC-SHA-256 cắt 16 byte trên giá trị đã
// chuẩn hoá, khoá con riêng theo cột.
//
// Nguồn khoá là giao diện Keyring. Bản LocalKeyring dẫn xuất DEK theo (tenant, mục đích, tháng) từ một khoá gốc
// cấu hình, chỉ dùng cho dev và kiểm thử; bản production dùng keyring.dek và OpenBao Transit như DekService của
// vt-platform-starter (chưa hiện thực trong Go, ghi ở báo cáo DV-12-05).
package crypt

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"strings"
	"time"
	"unicode"

	"github.com/google/uuid"
	"golang.org/x/text/unicode/norm"
)

// Mục đích khoá theo CHECK của keyring.dek (DekPurpose); mã thiết bị là định danh cá nhân.
const (
	PurposeIdentity   = "DINH_DANH_CA_NHAN"
	PurposeBlindIndex = "BLIND_INDEX"
)

const version byte = 0x01

// ErrDecrypt được trả khi bản mã sai, bị sửa hoặc lệch AAD; không bao giờ trả bản rõ sai.
var ErrDecrypt = errors.New("crypt: không giải mã được trường C2")

// Keyring cấp khoá dữ liệu theo tenant và mục đích.
type Keyring interface {
	// Current trả DEK đang dùng để mã hoá.
	Current(tenantID, purpose string) (uuid.UUID, []byte, error)
	// ByID trả DEK theo mã để giải mã dữ liệu lịch sử.
	ByID(tenantID, purpose string, id uuid.UUID) ([]byte, error)
	// BlindKey trả khoá gốc chỉ mục mù của tenant; không xoay theo tháng để so khớp được dữ liệu cũ.
	BlindKey(tenantID string) ([]byte, error)
}

// LocalKeyring dẫn xuất DEK từ khoá gốc bằng HMAC-SHA-256 (dev, kiểm thử).
type LocalKeyring struct {
	master []byte
	now    func() time.Time
}

// NewLocalKeyring tạo LocalKeyring; khoá gốc phải đủ 32 byte.
func NewLocalKeyring(master []byte) (*LocalKeyring, error) {
	if len(master) < 32 {
		return nil, errors.New("crypt: khoá gốc phải từ 32 byte")
	}
	return &LocalKeyring{master: append([]byte{}, master...), now: time.Now}, nil
}

func (k *LocalKeyring) dekID(tenantID, purpose, month string) uuid.UUID {
	return uuid.NewSHA1(uuid.NameSpaceOID, []byte("dek:"+tenantID+":"+purpose+":"+month))
}

func (k *LocalKeyring) derive(id uuid.UUID) []byte {
	m := hmac.New(sha256.New, k.master)
	m.Write(id[:])
	return m.Sum(nil)
}

// Current trả DEK của tháng hiện tại.
func (k *LocalKeyring) Current(tenantID, purpose string) (uuid.UUID, []byte, error) {
	id := k.dekID(tenantID, purpose, k.now().UTC().Format("200601"))
	return id, k.derive(id), nil
}

// ByID dẫn xuất lại DEK từ mã; LocalKeyring không lưu bảng nên không kiểm được mục đích của mã lạ.
func (k *LocalKeyring) ByID(_, _ string, id uuid.UUID) ([]byte, error) {
	return k.derive(id), nil
}

// BlindKey dẫn xuất khoá gốc chỉ mục mù của tenant.
func (k *LocalKeyring) BlindKey(tenantID string) ([]byte, error) {
	return k.derive(k.dekID(tenantID, PurposeBlindIndex, "-")), nil
}

// Field mã hoá, giải mã trường C2 và tính chỉ mục mù.
type Field struct{ Keys Keyring }

func aad(tenantID, column, rowID string) []byte {
	return []byte(tenantID + "|" + column + "|" + rowID)
}

// Encrypt trả bản mã và mã DEK.
func (f Field) Encrypt(tenantID, purpose, column, rowID string, plaintext []byte) ([]byte, uuid.UUID, error) {
	id, key, err := f.Keys.Current(tenantID, purpose)
	if err != nil {
		return nil, uuid.Nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, uuid.Nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, uuid.Nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, uuid.Nil, err
	}
	out := make([]byte, 0, 1+16+len(nonce)+len(plaintext)+gcm.Overhead())
	out = append(out, version)
	out = append(out, id[:]...)
	out = append(out, nonce...)
	out = gcm.Seal(out, nonce, plaintext, aad(tenantID, column, rowID))
	return out, id, nil
}

// Decrypt mở bản mã; sai AAD, sai tenant, sửa một bit đều trả ErrDecrypt.
func (f Field) Decrypt(tenantID, purpose, column, rowID string, ct []byte) ([]byte, error) {
	if len(ct) < 1+16+12+16 || ct[0] != version {
		return nil, ErrDecrypt
	}
	id, _ := uuid.FromBytes(ct[1:17])
	key, err := f.Keys.ByID(tenantID, purpose, id)
	if err != nil {
		return nil, ErrDecrypt
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, ErrDecrypt
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, ErrDecrypt
	}
	pt, err := gcm.Open(nil, ct[17:29], ct[29:], aad(tenantID, column, rowID))
	if err != nil {
		return nil, ErrDecrypt
	}
	return pt, nil
}

// Normalize chuẩn hoá giá trị trước khi tính chỉ mục mù: NFC, gom khoảng trắng, chữ thường (như BlindIndexer).
func Normalize(v string) string {
	v = norm.NFC.String(v)
	v = strings.Join(strings.FieldsFunc(v, unicode.IsSpace), " ")
	return strings.ToLower(v)
}

// BlindIndex tính HMAC-SHA-256 cắt 16 byte của giá trị đã chuẩn hoá bằng khoá con riêng của cột.
func (f Field) BlindIndex(tenantID, column, value string) ([]byte, error) {
	root, err := f.Keys.BlindKey(tenantID)
	if err != nil {
		return nil, err
	}
	sub := hmac.New(sha256.New, root)
	sub.Write([]byte(column))
	m := hmac.New(sha256.New, sub.Sum(nil))
	m.Write([]byte(Normalize(value)))
	return m.Sum(nil)[:16], nil
}
