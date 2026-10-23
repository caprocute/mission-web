package server

import (
	"errors"
	"strings"

	"github.com/google/uuid"
)

// jobPrefix là tiền tố mã tác vụ dài của ledger-service: cổng cell định tuyến /api/v1/jobs/ledger-… về đây
// (wiki 14 mục DV-01-04). Job.id trong hợp đồng là chuỗi nên tiền tố không đổi hợp đồng.
const jobPrefix = "ledger-"

// jobID trả ledger-<uuid>.
func jobID(id uuid.UUID) string { return jobPrefix + id.String() }

// parseJobID đọc ledger-<uuid>; vẫn nhận UUID trần của tác vụ tạo trước khi có tiền tố.
func parseJobID(s string) (uuid.UUID, error) {
	raw := strings.TrimPrefix(s, jobPrefix)
	if len(raw) != 36 {
		return uuid.Nil, errors.New("mã tác vụ không hợp lệ")
	}
	return uuid.Parse(raw)
}
