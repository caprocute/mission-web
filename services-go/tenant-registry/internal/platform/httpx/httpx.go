// Package httpx gom tiện ích HTTP dùng chung: ghi JSON, đọc thân yêu cầu có giới hạn, ETag và If-Match
// theo baseline mục 13 (ETag = row_version trong dấu nháy kép; thiếu If-Match trả 428, lệch trả 409).
package httpx

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strconv"
	"strings"

	"mission/services-go/tenant-registry/internal/platform/problem"
)

// MaxBody là giới hạn thân yêu cầu (64 KiB đủ cho mọi lược đồ của registry).
const MaxBody = 64 << 10

// WriteJSON ghi v dạng application/json với mã trạng thái.
func WriteJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if v != nil {
		_ = json.NewEncoder(w).Encode(v)
	}
}

// ReadBody đọc thân yêu cầu, kiểm Content-Type thuộc danh sách cho phép.
func ReadBody(r *http.Request, allowed ...string) ([]byte, error) {
	ct, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil {
		return nil, problem.Unsupported("Thiếu hoặc sai kiểu nội dung.")
	}
	ok := false
	for _, a := range allowed {
		if ct == a {
			ok = true
		}
	}
	if !ok {
		return nil, problem.Unsupported("Kiểu nội dung " + ct + " không được hỗ trợ.")
	}
	b, err := io.ReadAll(io.LimitReader(r.Body, MaxBody+1))
	if err != nil {
		return nil, problem.BadRequest("Không đọc được thân yêu cầu.")
	}
	if len(b) > MaxBody {
		return nil, problem.New(http.StatusRequestEntityTooLarge, "payload-too-large", "Dữ liệu gửi lên quá lớn", "COMMON-413-PAYLOAD-TOO-LARGE")
	}
	return b, nil
}

// DecodeStrict giải mã JSON và từ chối trường lạ (422).
func DecodeStrict(b []byte, v any) error {
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		var ute *json.UnmarshalTypeError
		if errors.As(err, &ute) {
			return problem.Validation(problem.FieldError{Field: ute.Field, Message: "Kiểu dữ liệu không đúng"})
		}
		msg := err.Error()
		if strings.HasPrefix(msg, "json: unknown field ") {
			f := strings.Trim(strings.TrimPrefix(msg, "json: unknown field "), `"`)
			return problem.Validation(problem.FieldError{Field: f, Message: "Trường không được hỗ trợ"})
		}
		return problem.BadRequest("Thân yêu cầu không phải JSON hợp lệ.")
	}
	if dec.More() {
		return problem.BadRequest("Thân yêu cầu không phải JSON hợp lệ.")
	}
	return nil
}

// ETag trả giá trị ETag cho row_version.
func ETag(v int) string { return `"` + strconv.Itoa(v) + `"` }

// IfMatch đọc If-Match: thiếu → 428, sai định dạng → 409 (không khớp phiên bản nào).
func IfMatch(r *http.Request, module string) (int, error) {
	raw := strings.TrimSpace(r.Header.Get("If-Match"))
	if raw == "" {
		return 0, problem.PreconditionRequired()
	}
	raw = strings.TrimPrefix(raw, "W/")
	raw = strings.Trim(raw, `"`)
	v, err := strconv.Atoi(raw)
	if err != nil || v < 0 {
		return 0, problem.VersionConflict(module, -1, -1).WithDetail("Phiên bản gửi kèm không hợp lệ.")
	}
	return v, nil
}
