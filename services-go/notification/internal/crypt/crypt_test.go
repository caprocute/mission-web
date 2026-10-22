package crypt

import (
	"bytes"
	"errors"
	"testing"
)

func TestEnvelope(t *testing.T) {
	k, err := NewLocalKeyring(bytes.Repeat([]byte{7}, 32))
	if err != nil {
		t.Fatal(err)
	}
	f := Field{Keys: k}
	const tA, tB, col, row = "tenant-a", "tenant-b", "notify.push_device.token_enc", "row-1"
	ct, dek, err := f.Encrypt(tA, PurposeIdentity, col, row, []byte("ma-thiet-bi-123"))
	if err != nil {
		t.Fatal(err)
	}
	if ct[0] != 0x01 || !bytes.Equal(ct[1:17], dek[:]) || bytes.Contains(ct, []byte("ma-thiet-bi")) {
		t.Fatal("định dạng bản mã sai hoặc lộ bản rõ")
	}
	pt, err := f.Decrypt(tA, PurposeIdentity, col, row, ct)
	if err != nil || string(pt) != "ma-thiet-bi-123" {
		t.Fatalf("giải mã sai: %q %v", pt, err)
	}
	bad := append([]byte{}, ct...)
	bad[len(bad)-1] ^= 1
	for _, c := range []struct {
		tenant, row string
		ct          []byte
	}{{tB, row, ct}, {tA, "row-2", ct}, {tA, row, bad}, {tA, row, ct[:20]}} {
		if _, err := f.Decrypt(c.tenant, PurposeIdentity, col, c.row, c.ct); !errors.Is(err, ErrDecrypt) {
			t.Fatal("lệch tenant, dòng hoặc sửa bit phải lỗi")
		}
	}
	a1, _ := f.BlindIndex(tA, col, "  Ma  Thiet\tBi ")
	a2, _ := f.BlindIndex(tA, col, "ma thiet bi")
	b1, _ := f.BlindIndex(tB, col, "ma thiet bi")
	if len(a1) != 16 || !bytes.Equal(a1, a2) || bytes.Equal(a1, b1) {
		t.Fatal("chỉ mục mù phải chuẩn hoá và tách theo tenant")
	}
}
