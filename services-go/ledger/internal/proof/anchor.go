package proof

import (
	"time"

	"github.com/google/uuid"
)

// AnchorDocument là bản neo của một ngày: gốc cell cuối ngày kèm chữ ký và token TSA, đủ để bên nhận
// kiểm độc lập (TL-01 "Niêm phong Merkle, dấu thời gian và bản neo").
type AnchorDocument struct {
	Day              string    `json:"day"`
	CellCode         string    `json:"cellCode"`
	CellSealID       uuid.UUID `json:"cellSealId"`
	WindowStart      time.Time `json:"windowStart"`
	WindowEnd        time.Time `json:"windowEnd"`
	CellRoot         string    `json:"cellRoot"`
	PrevCellRoot     string    `json:"prevCellRoot"`
	SignerKeyID      string    `json:"signerKeyId"`
	Signature        string    `json:"signature"`
	SignerCertSHA256 string    `json:"signerCertSha256"`
	TSAToken         string    `json:"tsaToken"`
}
