package channel

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestBreaker(t *testing.T) {
	now := time.Unix(0, 0)
	b := NewBreaker(2, time.Minute)
	b.Now = func() time.Time { return now }
	b.Failure()
	if !b.Allow() {
		t.Fatal("một lỗi chưa mở mạch")
	}
	b.Failure()
	if b.Allow() || !b.IsOpen() {
		t.Fatal("hai lỗi liên tiếp phải mở mạch")
	}
	now = now.Add(time.Minute)
	if !b.Allow() || b.Allow() {
		t.Fatal("nửa mở chỉ cho một lần thử")
	}
	b.Failure()
	if b.Allow() {
		t.Fatal("thử lỗi thì mở lại")
	}
	now = now.Add(time.Minute)
	if !b.Allow() {
		t.Fatal("hết thời gian mở thì cho thử")
	}
	b.Success()
	if !b.Allow() || !b.Allow() || b.IsOpen() {
		t.Fatal("thử được thì đóng mạch")
	}
}

func TestPhoneAndText(t *testing.T) {
	for in, want := range map[string]string{"0912 345 678": "84912345678", "+84 912.345.678": "84912345678",
		"(84) 987-654-321": "84987654321"} {
		if got, ok := NormalizePhone(in); !ok || got != want {
			t.Fatalf("%q → %q", in, got)
		}
	}
	for _, bad := range []string{"12345", "abc0912345678", "0912"} {
		if _, ok := NormalizePhone(bad); ok {
			t.Fatalf("%q phải bị từ chối", bad)
		}
	}
	if got := Unaccent("Đồng chí được giao nhiệm vụ mới"); got != "Dong chi duoc giao nhiem vu moi" {
		t.Fatal(got)
	}
	if got := truncate(strings.Repeat("a", 500), MaxSMSRunes); len([]rune(got)) != MaxSMSRunes {
		t.Fatal(len(got))
	}
}

func TestRedactionAndCodes(t *testing.T) {
	m := Message{To: "can.bo@donvi.test", Key: "d1"}
	for _, s := range []string{fmt.Sprint(m), fmt.Sprintf("%v %+v %#v", m, m, m)} {
		if strings.Contains(s, "can.bo") {
			t.Fatal("Message không được in địa chỉ")
		}
	}
	if Code(Permanent("BAD_ADDRESS")) != "BAD_ADDRESS" || Code(Transient{"SMS_x"}) != "SMS_x" ||
		Code(ErrUnregistered) != "UNREGISTERED" {
		t.Fatal("mã lỗi")
	}
	if !errors.Is(status(400, "SMS"), ErrPermanent) || status(503, "SMS") == nil || errors.Is(status(429, "SMS"), ErrPermanent) {
		t.Fatal("phân loại mã HTTP")
	}
}

func TestAPNsTokenIsValidES256(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	var auth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth = r.Header.Get("authorization")
		w.WriteHeader(http.StatusGone)
		_, _ = w.Write([]byte(`{"reason":"Unregistered"}`))
	}))
	defer srv.Close()
	a := &APNs{BaseURL: srv.URL, TeamID: "T", KeyID: "K", Key: key, Topic: "x"}
	if _, err := a.Send(context.Background(), Message{To: "tok"}); !errors.Is(err, ErrUnregistered) {
		t.Fatal(err)
	}
	parts := strings.Split(strings.TrimPrefix(auth, "bearer "), ".")
	if len(parts) != 3 {
		t.Fatal(auth)
	}
	var hdr map[string]string
	h, _ := b64.DecodeString(parts[0])
	_ = json.Unmarshal(h, &hdr)
	sig, _ := b64.DecodeString(parts[2])
	sum := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	r, s := new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:])
	if hdr["alg"] != "ES256" || hdr["kid"] != "K" || !ecdsa.Verify(&key.PublicKey, sum[:], r, s) {
		t.Fatal("JWT APNs không hợp lệ")
	}
}

func TestSMTPRequiresStartTLS(t *testing.T) {
	// Máy chủ thư không hỗ trợ STARTTLS thì không gửi bản rõ ở chế độ mặc định.
	ln := httptest.NewUnstartedServer(nil).Listener
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		_, _ = c.Write([]byte("220 thu ESMTP\r\n"))
		buf := make([]byte, 512)
		_, _ = c.Read(buf)
		_, _ = c.Write([]byte("250-thu\r\n250 AUTH PLAIN\r\n"))
		_, _ = c.Read(buf)
	}()
	_, err := SMTP{Addr: ln.Addr().String(), From: "a@b.test", Timeout: 2 * time.Second}.Send(context.Background(),
		Message{To: "c@d.test"})
	var tr Transient
	if !errors.As(err, &tr) || tr.Code != "NO_STARTTLS" {
		t.Fatal(err)
	}
}
