// Package seal hiện thực niêm phong Merkle hai mức của SVC-09 (DV-09-03, TL-03 "Niêm phong Merkle và
// dấu thời gian", ADR-26): cây mức tenant có lá là entry_hash cuối của từng chuỗi có bản ghi mới, cây
// mức cell có lá là gốc các tenant; gốc cell được ký một lần và lấy dấu thời gian RFC 3161 một lần.
package seal

import (
	"bytes"
	"crypto/sha256"
	"errors"
)

// Tiền tố miền của lá và nút, khác nhau để chống tấn công cây thứ hai (ADR-26).
const (
	leafPrefix = 0x01
	nodePrefix = 0x02
)

// Leaf là SHA-256(0x01 || h).
func Leaf(h []byte) [32]byte {
	return sha256.Sum256(append([]byte{leafPrefix}, h...))
}

// Node là SHA-256(0x02 || trái || phải).
func Node(l, r [32]byte) [32]byte {
	b := make([]byte, 0, 65)
	b = append(b, nodePrefix)
	b = append(b, l[:]...)
	b = append(b, r[:]...)
	return sha256.Sum256(b)
}

// split là luỹ thừa của 2 lớn nhất nhỏ hơn n (cách chia cây của RFC 6962 mục 2.1), để cây có số lá
// bất kỳ mà không phải nhân đôi lá cuối.
func split(n int) int {
	k := 1
	for k<<1 < n {
		k <<= 1
	}
	return k
}

// Root là gốc cây của các lá đã băm (Leaf). Cây rỗng không có gốc.
func Root(leaves [][32]byte) ([32]byte, error) {
	if len(leaves) == 0 {
		return [32]byte{}, errors.New("seal: cây Merkle rỗng")
	}
	return root(leaves), nil
}

func root(leaves [][32]byte) [32]byte {
	if len(leaves) == 1 {
		return leaves[0]
	}
	k := split(len(leaves))
	return Node(root(leaves[:k]), root(leaves[k:]))
}

// Step là một bước của đường đi Merkle: nút anh em và vị trí của nó (bên trái hay phải).
type Step struct {
	Sibling [32]byte
	Left    bool
}

// Path là đường đi từ lá thứ i tới gốc, từ dưới lên.
func Path(leaves [][32]byte, i int) ([]Step, error) {
	if i < 0 || i >= len(leaves) {
		return nil, errors.New("seal: vị trí lá ngoài cây")
	}
	return path(leaves, i), nil
}

func path(leaves [][32]byte, i int) []Step {
	if len(leaves) == 1 {
		return nil
	}
	k := split(len(leaves))
	if i < k {
		return append(path(leaves[:k], i), Step{Sibling: root(leaves[k:]), Left: false})
	}
	return append(path(leaves[k:], i-k), Step{Sibling: root(leaves[:k]), Left: true})
}

// Verify tái dựng gốc từ lá và đường đi rồi so với gốc mong đợi.
func Verify(leaf [32]byte, steps []Step, want [32]byte) bool {
	h := leaf
	for _, s := range steps {
		if s.Left {
			h = Node(s.Sibling, h)
		} else {
			h = Node(h, s.Sibling)
		}
	}
	return bytes.Equal(h[:], want[:])
}
