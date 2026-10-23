package seal

import (
	"crypto/sha256"
	"fmt"
	"testing"
)

func leaves(n int) [][32]byte {
	out := make([][32]byte, n)
	for i := range out {
		h := sha256.Sum256([]byte(fmt.Sprint(i)))
		out[i] = Leaf(h[:])
	}
	return out
}

func TestDuongDiTaiDungGoc(t *testing.T) {
	for n := 1; n <= 70; n++ {
		ls := leaves(n)
		r, err := Root(ls)
		if err != nil {
			t.Fatal(err)
		}
		for i := range ls {
			p, _ := Path(ls, i)
			if !Verify(ls[i], p, r) {
				t.Fatalf("n=%d i=%d: đường đi không tái dựng được gốc", n, i)
			}
			other := ls[(i+1)%n]
			if n > 1 && Verify(other, p, r) {
				t.Fatalf("n=%d i=%d: lá khác vẫn khớp", n, i)
			}
		}
	}
}

func TestTienToMienKhacNhau(t *testing.T) {
	a, b := leaves(2)[0], leaves(2)[1]
	n := Node(a, b)
	// Nút trong không thể giả làm lá: Leaf(a||b) khác Node(a,b).
	if Leaf(append(a[:], b[:]...)) == n {
		t.Fatal("lá và nút trùng miền băm")
	}
	if _, err := Root(nil); err == nil {
		t.Fatal("cây rỗng phải lỗi")
	}
	r, _ := Root(leaves(3))
	ls := leaves(3)
	if r != Node(Node(ls[0], ls[1]), ls[2]) {
		t.Fatal("cách chia cây khác RFC 6962")
	}
}
