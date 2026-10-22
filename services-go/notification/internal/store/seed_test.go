package store

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"mission/services-go/notification/internal/render"
)

func seedPath() string {
	_, f, _, _ := runtime.Caller(0)
	return filepath.Join(filepath.Dir(f), "..", "..", "..", "..", "data", "seed", "notify", "mau-thong-bao-chung.json")
}

// Bộ mẫu chung phải hợp lệ, không chứa C2 và có đủ mẫu mà consumer dùng.
func TestSeedFile(t *testing.T) {
	b, err := os.ReadFile(seedPath())
	if err != nil {
		t.Fatal(err)
	}
	f, err := ParseSeed(b)
	if err != nil {
		t.Fatal(err)
	}
	have := map[string]bool{}
	for _, tpl := range f.Templates {
		if tpl.Channel != ChannelInApp || tpl.Locale != DefaultLocale {
			t.Fatalf("mẫu %s phải là INAPP tiếng Việt", tpl.TemplateCode)
		}
		have[tpl.TemplateCode] = true
	}
	for _, code := range []string{"NV_DUOC_GIAO", "NV_PHOI_HOP", "MC_DA_NOP", "HS_DA_CHUYEN_BUOC", "CANH_BAO_BAT_THUONG", "OKR_CHO_DUYET"} {
		if !have[code] {
			t.Fatalf("thiếu mẫu %s", code)
		}
	}
}

func TestSeedRejectsC2(t *testing.T) {
	bad := []byte(`{"templates":[{"templateCode":"X","channel":"INAPP","locale":"vi","subject":"Kết quả","body":"Điểm {{diem|C2}}"}]}`)
	if _, err := ParseSeed(bad); !errors.Is(err, render.ErrC2Param) {
		t.Fatalf("mẫu có tham số C2 phải bị từ chối, nhận %v", err)
	}
	dup := []byte(`{"templates":[{"templateCode":"X","channel":"INAPP","locale":"vi","subject":"a","body":""},{"templateCode":"X","channel":"INAPP","locale":"vi","subject":"b","body":""}]}`)
	if _, err := ParseSeed(dup); err == nil {
		t.Fatal("mẫu trùng khoá phải bị từ chối")
	}
}

func TestCursorRoundTrip(t *testing.T) {
	if _, err := decodeCursor("không-hợp-lệ"); !errors.Is(err, ErrInvalidCursor) {
		t.Fatal("con trỏ sai phải bị từ chối")
	}
}
