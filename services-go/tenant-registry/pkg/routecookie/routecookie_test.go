package routecookie

import (
	"bytes"
	"encoding/base64"
	"errors"
	"strings"
	"testing"
	"time"
)

func key(b byte) []byte { return bytes.Repeat([]byte{b}, 32) }

func TestSignVerify(t *testing.T) {
	s, err := NewSigner(key(1))
	if err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1_800_000_000, 0)
	v := s.Sign("0192f1a2-3b4c-7d5e-8f60-718293a4b5c6", "CELL-HN", now)
	c, err := s.Verify(v, now.Add(4*time.Minute))
	if err != nil || c.CellCode != "CELL-HN" || c.Exp != now.Add(TTL).Unix() {
		t.Fatalf("cookie hợp lệ bị từ chối: %v %+v", err, c)
	}
	if _, err := s.Verify(v, now.Add(TTL)); !errors.Is(err, ErrExpired) {
		t.Fatalf("cookie hết hạn phải bị từ chối, có %v", err)
	}
	parts := strings.Split(v, ".")
	forged := base64.RawURLEncoding.EncodeToString([]byte(`{"t":"0192f1a2-3b4c-7d5e-8f60-718293a4b5c6","c":"CELL-G1","exp":9999999999}`))
	if _, err := s.Verify(parts[0]+"."+forged+"."+parts[2], now); !errors.Is(err, ErrSignature) {
		t.Fatalf("sửa nội dung phải bị từ chối, có %v", err)
	}
	sig := []byte(parts[2])
	sig[0] ^= 1
	if _, err := s.Verify(parts[0]+"."+parts[1]+"."+string(sig), now); err == nil {
		t.Fatal("sửa chữ ký phải bị từ chối")
	}
	other, _ := NewSigner(key(2))
	if _, err := other.Verify(v, now); !errors.Is(err, ErrSignature) {
		t.Fatalf("khoá khác phải bị từ chối, có %v", err)
	}
	for _, bad := range []string{"", "v1.a", "v2." + parts[1] + "." + parts[2], "v1.%%%." + parts[2], "v1." + parts[1] + ".%%"} {
		if _, err := s.Verify(bad, now); err == nil {
			t.Fatalf("giá trị sai định dạng %q phải bị từ chối", bad)
		}
	}
}

func TestKeyRotation(t *testing.T) {
	old, _ := NewSigner(key(1))
	v := old.Sign("t", "CELL-SH", time.Now())
	rotated, err := NewSigner(key(2), key(1))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := rotated.Verify(v, time.Now()); err != nil {
		t.Fatalf("khoá trước đó phải còn được chấp nhận: %v", err)
	}
	if _, err := NewSigner([]byte("short")); !errors.Is(err, ErrKey) {
		t.Fatal("khoá ngắn phải bị từ chối")
	}
	if _, err := NewSigner(key(1), []byte("short")); !errors.Is(err, ErrKey) {
		t.Fatal("khoá cũ ngắn phải bị từ chối")
	}
}
