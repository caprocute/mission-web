package proof

import (
	"encoding/hex"
	"fmt"
)

// ChainRange là khoảng seq_no mới của một chuỗi trong cửa sổ.
type ChainRange struct {
	Partition int16  `json:"p"`
	From      int64  `json:"from"`
	To        int64  `json:"to"`
	Tip       string `json:"tip"`
}

// TenantRoot là gốc cây mức tenant: lá là SHA-256(0x01 || entry_hash cuối) của từng chuỗi có bản ghi
// mới, sắp theo chain_partition.
func TenantRoot(ranges []ChainRange) ([32]byte, error) {
	leaves := make([][32]byte, 0, len(ranges))
	for _, r := range ranges {
		tip, err := hex.DecodeString(r.Tip)
		if err != nil || len(tip) != 32 {
			return [32]byte{}, fmt.Errorf("proof: entry_hash cuối của chuỗi %d không hợp lệ", r.Partition)
		}
		leaves = append(leaves, Leaf(tip))
	}
	return Root(leaves)
}
