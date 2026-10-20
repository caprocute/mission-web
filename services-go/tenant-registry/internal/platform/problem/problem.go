// Package problem ghi lỗi theo RFC 9457 (application/problem+json) đúng lược đồ ProblemDetails của
// contracts/openapi/common/common.yaml: type, title, status, detail, instance, code, msg, errors[], traceId.
// Không bao giờ đưa stack trace hay câu SQL vào phản hồi (BACKEND.md mục 3.2).
// Điểm chờ nền móng: thay bằng vtplatform/problem của NEN-017.
package problem

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
)

// Gốc URI của trường type, theo ví dụ trong common.yaml (https://okrkpi.vn/problems/<loại>).
const typeBase = "https://okrkpi.vn/problems/"

// FieldError là một lỗi theo trường (FieldError trong common.yaml).
type FieldError struct {
	Field   string `json:"field"`
	Message string `json:"message"`
	Rule    string `json:"rule,omitempty"`
}

// Problem là thân phản hồi lỗi; đồng thời là kiểu error để tầng nghiệp vụ trả lên.
type Problem struct {
	Type     string       `json:"type"`
	Title    string       `json:"title"`
	Status   int          `json:"status"`
	Detail   string       `json:"detail,omitempty"`
	Instance string       `json:"instance,omitempty"`
	Code     string       `json:"code,omitempty"`
	Msg      string       `json:"msg,omitempty"`
	Errors   []FieldError `json:"errors,omitempty"`
	TraceID  string       `json:"traceId,omitempty"`

	// Header bổ sung (Location cho 421, Retry-After cho 503, WWW-Authenticate cho 401). Không tuần tự hoá.
	Headers http.Header `json:"-"`
}

func (p *Problem) Error() string {
	return fmt.Sprintf("%d %s: %s", p.Status, p.Code, p.Detail)
}

// New dựng Problem; slug là phần cuối của type URI (ví dụ "br-01-02", "concurrent-modification").
func New(status int, slug, title, code string) *Problem {
	return &Problem{Type: typeBase + slug, Title: title, Status: status, Code: code}
}

// WithDetail gắn mô tả cho người dùng (tiếng Việt, không chứa thông tin nội bộ).
func (p *Problem) WithDetail(format string, args ...any) *Problem {
	p.Detail = fmt.Sprintf(format, args...)
	return p
}

// WithMsg gắn mã thông điệp TL-02 (MSG-xx-yy).
func (p *Problem) WithMsg(msg string) *Problem { p.Msg = msg; return p }

// WithErrors gắn lỗi theo trường.
func (p *Problem) WithErrors(errs ...FieldError) *Problem {
	p.Errors = append(p.Errors, errs...)
	return p
}

// WithHeader gắn header phản hồi.
func (p *Problem) WithHeader(k, v string) *Problem {
	if p.Headers == nil {
		p.Headers = http.Header{}
	}
	p.Headers.Set(k, v)
	return p
}

// Các lỗi chung (mã COMMON- theo common.yaml).

func Validation(errs ...FieldError) *Problem {
	return New(http.StatusUnprocessableEntity, "validation", "Dữ liệu chưa hợp lệ", "COMMON-422-VALIDATION").WithErrors(errs...)
}

func BadRequest(detail string) *Problem {
	return New(http.StatusBadRequest, "bad-request", "Yêu cầu không hợp lệ", "COMMON-400-BAD-REQUEST").WithDetail("%s", detail)
}

func Unauthorized() *Problem {
	return New(http.StatusUnauthorized, "unauthorized", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn", "COMMON-401-UNAUTHORIZED").
		WithHeader("WWW-Authenticate", `Bearer realm="mission"`)
}

// StepUpRequired là 401 theo RFC 9470 khi thao tác cần acr=aal2 và auth_time không quá maxAge giây.
func StepUpRequired(maxAge int) *Problem {
	return New(http.StatusUnauthorized, "insufficient-user-authentication", "Cần xác thực bổ sung để thực hiện thao tác này", "COMMON-401-STEP-UP").
		WithHeader("WWW-Authenticate", `Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=`+strconv.Itoa(maxAge))
}

func Forbidden() *Problem {
	return New(http.StatusForbidden, "forbidden", "Không có quyền thực hiện thao tác này", "COMMON-403-OUT-OF-SCOPE")
}

func NotFound() *Problem {
	return New(http.StatusNotFound, "not-found", "Không tìm thấy dữ liệu", "COMMON-404-NOT-FOUND")
}

func PreconditionRequired() *Problem {
	return New(http.StatusPreconditionRequired, "precondition-required", "Thiếu phiên bản dữ liệu khi cập nhật", "COMMON-428-IF-MATCH-REQUIRED").
		WithDetail("Yêu cầu cập nhật phải gửi kèm phiên bản dữ liệu đã đọc.")
}

func IdempotencyMismatch() *Problem {
	return New(http.StatusUnprocessableEntity, "idempotency-mismatch", "Khoá chống ghi trùng đã dùng cho nội dung khác", "COMMON-422-IDEMPOTENCY-MISMATCH")
}

func IdempotencyKeyRequired() *Problem {
	return Validation(FieldError{Field: "Idempotency-Key", Message: "Thao tác ghi phải gửi kèm khoá chống ghi trùng"})
}

func Unsupported(detail string) *Problem {
	return New(http.StatusUnsupportedMediaType, "unsupported-media-type", "Định dạng dữ liệu gửi lên không được hỗ trợ", "COMMON-415-UNSUPPORTED").WithDetail("%s", detail)
}

func Internal() *Problem {
	return New(http.StatusInternalServerError, "internal", "Hệ thống đang gặp sự cố, vui lòng thử lại sau", "COMMON-500-INTERNAL")
}

// Unavailable là 503 kèm Retry-After (giây).
func Unavailable(retryAfter int) *Problem {
	return New(http.StatusServiceUnavailable, "service-unavailable", "Dịch vụ tạm thời gián đoạn, vui lòng thử lại sau", "COMMON-503-UNAVAILABLE").
		WithHeader("Retry-After", strconv.Itoa(retryAfter))
}

// VersionConflict là 409 khi If-Match lệch row_version (quyết định HD-01: 409, không dùng 412).
func VersionConflict(module string, sent, current int) *Problem {
	return New(http.StatusConflict, "concurrent-modification", "Dữ liệu đã được người khác cập nhật", module+"-409-VERSION-CONFLICT").
		WithDetail("Phiên bản bạn sửa là %d, phiên bản hiện tại là %d.", sent, current)
}

// From chuyển một error bất kỳ thành Problem; lỗi không phải Problem thành 500 chung (không lộ nội dung).
func From(err error) *Problem {
	var p *Problem
	if errors.As(err, &p) {
		return p
	}
	return Internal()
}

// Write ghi Problem ra phản hồi. traceID lấy từ ngữ cảnh request do logx đặt.
func Write(w http.ResponseWriter, traceID string, p *Problem) {
	for k, vs := range p.Headers {
		for _, v := range vs {
			w.Header().Add(k, v)
		}
	}
	out := *p
	if out.TraceID == "" {
		out.TraceID = traceID
	}
	w.Header().Set("Content-Type", "application/problem+json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(out.Status)
	_ = json.NewEncoder(w).Encode(out)
}
