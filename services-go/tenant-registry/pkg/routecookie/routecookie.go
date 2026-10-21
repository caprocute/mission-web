// Package routecookie ký và kiểm cookie định tuyến tới cell (TL-01 "Định tuyến tới cell": cookie ký HMAC hạn
// 5 phút để request sau đi thẳng tới cell). Gói công khai để APISIX lớp ngoài và cổng cell (DV-01-04) dùng
// chung đúng một định dạng.
//
// Định dạng giá trị (ASCII, không dấu cách):
//
//	v1.<payload>.<sig>
//	payload = base64url không đệm của JSON {"t":"<tenant uuid>","c":"<mã cell>","exp":<unix giây>}
//	sig     = base64url không đệm của HMAC-SHA256(khoá, "v1." + payload)
//
// Khoá HMAC dài tối thiểu 32 byte, đọc từ OpenBao qua External Secrets; kiểm chấp nhận khoá hiện hành và khoá
// trước đó để xoay vòng khoá không làm hỏng cookie đang lưu hành (suy luận: TL-01 chưa ghi cách xoay khoá).
package routecookie

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

// Name là tên cookie (suy luận: TL-01 không đặt tên).
const Name = "mission_route"

// TTL là hạn của cookie (TL-01: 5 phút).
const TTL = 5 * time.Minute

const version = "v1"

// Claims là nội dung cookie.
type Claims struct {
	TenantID string `json:"t"`
	CellCode string `json:"c"`
	Exp      int64  `json:"exp"`
}

// Lỗi kiểm cookie.
var (
	ErrMalformed = errors.New("routecookie: sai định dạng")
	ErrSignature = errors.New("routecookie: chữ ký không hợp lệ")
	ErrExpired   = errors.New("routecookie: đã hết hạn")
	ErrKey       = errors.New("routecookie: khoá HMAC phải dài tối thiểu 32 byte")
)

// Signer ký và kiểm cookie.
type Signer struct {
	current  []byte
	previous [][]byte
}

// NewSigner tạo Signer với khoá hiện hành và các khoá cũ còn chấp nhận khi kiểm.
func NewSigner(current []byte, previous ...[]byte) (*Signer, error) {
	if len(current) < 32 {
		return nil, ErrKey
	}
	s := &Signer{current: current}
	for _, p := range previous {
		if len(p) == 0 {
			continue
		}
		if len(p) < 32 {
			return nil, ErrKey
		}
		s.previous = append(s.previous, p)
	}
	return s, nil
}

func mac(key []byte, msg string) []byte {
	m := hmac.New(sha256.New, key)
	m.Write([]byte(msg))
	return m.Sum(nil)
}

// Sign tạo giá trị cookie cho tenant và cell, hết hạn sau TTL kể từ now.
func (s *Signer) Sign(tenantID, cellCode string, now time.Time) string {
	b, _ := json.Marshal(Claims{TenantID: tenantID, CellCode: cellCode, Exp: now.Add(TTL).Unix()})
	head := version + "." + base64.RawURLEncoding.EncodeToString(b)
	return head + "." + base64.RawURLEncoding.EncodeToString(mac(s.current, head))
}

// Verify kiểm chữ ký (so sánh hằng thời gian) và hạn; trả nội dung khi hợp lệ.
func (s *Signer) Verify(value string, now time.Time) (Claims, error) {
	var c Claims
	parts := strings.Split(value, ".")
	if len(parts) != 3 || parts[0] != version {
		return c, ErrMalformed
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return c, ErrMalformed
	}
	head := parts[0] + "." + parts[1]
	ok := hmac.Equal(sig, mac(s.current, head))
	for _, k := range s.previous {
		ok = ok || hmac.Equal(sig, mac(k, head))
	}
	if !ok {
		return c, ErrSignature
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil || json.Unmarshal(raw, &c) != nil || c.TenantID == "" || c.CellCode == "" {
		return Claims{}, ErrMalformed
	}
	if now.Unix() >= c.Exp {
		return Claims{}, ErrExpired
	}
	return c, nil
}
