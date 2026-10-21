// Package problem implements RFC 9457 Problem Details (application/problem+json)
// backed by the shared error catalog from NEN-009 (error-codes.json).
//
//go:generate go run ../cmd/sync-error-codes -src ../../../backend/libs/vt-kernel/src/main/resources/error-codes.json -dst error-codes.json
package problem

import (
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
)

//go:embed error-codes.json
var embeddedCatalogBytes []byte

const (
	// ContentType is the standard RFC 9457 media type.
	ContentType = "application/problem+json"

	// ProblemTypeBaseURI matches ErrorCode.PROBLEM_TYPE_BASE_URI in vt-kernel.
	ProblemTypeBaseURI = "https://errors.okrkpi.viettel.vn/"

	// SafeInternalErrorDetail is the fallback Vietnamese message when an internal error or technical leak occurs.
	SafeInternalErrorDetail = "Hệ thống đang bận hoặc gặp sự cố nội bộ. Vui lòng thử lại sau."
)

var technicalLeakPattern = regexp.MustCompile(
	`(?i)(\bjava\.[a-z0-9_$.]+|\bjakarta\.[a-z0-9_$.]+|\borg\.springframework\.[a-z0-9_$.]+` +
		`|\borg\.hibernate\.[a-z0-9_$.]+|\borg\.postgresql\.[a-z0-9_$.]+|\bpgx\.[a-z0-9_$.]+|\bpgconn\.[a-z0-9_$.]+` +
		`|\bruntime\.gopanic\b|\bgoroutine\s+\d+\s+\[|\bpanic:\s+` +
		`|\b[a-z0-9_]+Exception\b|\bselect\s+.+\s+from\s+|\binsert\s+into\s+` +
		`|\bupdate\s+[a-z0-9_.]+\s+set\s+|\bdelete\s+from\s+|\bsql\s*\[|\n\s*at\s+|\bSQLSTATE\s+[0-9A-Z]{5}\b)`,
)

// ErrorCode represents an entry in error-codes.json (OpenAPI error code or MSG-xx-yy).
type ErrorCode struct {
	Code           string  `json:"code"`
	HTTPStatus     int     `json:"httpStatus"`
	Slug           string  `json:"slug"`
	DefaultTitleVi string  `json:"defaultTitleVi"`
	Msg            *string `json:"msg"`
	Rule           *string `json:"rule"`
}

// ProblemTypeURI returns the canonical RFC 9457 problem type URI for this error code.
func (e ErrorCode) ProblemTypeURI() string {
	return ProblemTypeBaseURI + e.Slug
}

// IsMsgCode reports whether the code itself is a MSG-xx-yy identifier.
func (e ErrorCode) IsMsgCode() bool {
	return strings.HasPrefix(e.Code, "MSG-")
}

// EffectiveMsg returns the MSG-xx-yy identifier associated with this error code, if any.
func (e ErrorCode) EffectiveMsg() string {
	if e.Msg != nil && strings.TrimSpace(*e.Msg) != "" {
		return strings.TrimSpace(*e.Msg)
	}
	if e.IsMsgCode() {
		return e.Code
	}
	return ""
}

// FieldErrorItem represents a single field-level validation violation in ProblemDetails.Errors.
type FieldErrorItem struct {
	Field   string `json:"field"`
	Message string `json:"message"`
	Rule    string `json:"rule,omitempty"`
}

// ProblemDetails represents the RFC 9457 response payload (contracts/openapi/common/common.yaml).
type ProblemDetails struct {
	Type     string           `json:"type"`
	Title    string           `json:"title"`
	Status   int              `json:"status"`
	Detail   string           `json:"detail,omitempty"`
	Instance string           `json:"instance,omitempty"`
	Code     string           `json:"code"`
	Msg      string           `json:"msg,omitempty"`
	Errors   []FieldErrorItem `json:"errors,omitempty"`
	TraceID  string           `json:"traceId,omitempty"`
}

// Error implements the standard Go error interface so ProblemDetails can be returned or wrapped as an error.
func (p ProblemDetails) Error() string {
	if p.Detail != "" {
		return fmt.Sprintf("%s (%d): %s", p.Code, p.Status, p.Detail)
	}
	return fmt.Sprintf("%s (%d): %s", p.Code, p.Status, p.Title)
}

type rawCatalog struct {
	SchemaVersion  string      `json:"schemaVersion"`
	ProblemBaseURI string      `json:"problemBaseUri"`
	Codes          []ErrorCode `json:"codes"`
}

var (
	catalogOnce sync.Once
	catalogMap  map[string]ErrorCode
	catalogList []ErrorCode
	catalogErr  error
)

func initCatalog() {
	catalogOnce.Do(func() {
		var raw rawCatalog
		if err := json.Unmarshal(embeddedCatalogBytes, &raw); err != nil {
			catalogErr = fmt.Errorf("problem: failed to parse embedded error-codes.json: %w", err)
			return
		}
		catalogMap = make(map[string]ErrorCode, len(raw.Codes))
		catalogList = make([]ErrorCode, 0, len(raw.Codes))
		for _, entry := range raw.Codes {
			catalogMap[entry.Code] = entry
			catalogList = append(catalogList, entry)
		}
	})
}

// Lookup finds an ErrorCode by its code (e.g. "COMMON-403-FORBIDDEN" or "MSG-01-04").
func Lookup(code string) (ErrorCode, bool) {
	initCatalog()
	if catalogErr != nil {
		return ErrorCode{}, false
	}
	ec, ok := catalogMap[strings.TrimSpace(code)]
	return ec, ok
}

// Require returns the ErrorCode for code or falls back to COMMON-500-INTERNAL-ERROR if unknown.
func Require(code string) (ErrorCode, error) {
	if ec, ok := Lookup(code); ok {
		return ec, nil
	}
	return ErrorCode{}, fmt.Errorf("problem: unknown error code %q", code)
}

// AllCodes returns a defensive copy of all ErrorCode entries in the catalog.
func AllCodes() []ErrorCode {
	initCatalog()
	out := make([]ErrorCode, len(catalogList))
	copy(out, catalogList)
	return out
}

// SanitizeDetail ensures SQL statements, exception class names, and stack traces never leak to clients.
func SanitizeDetail(candidate, fallback string) string {
	trimmed := strings.TrimSpace(candidate)
	if trimmed == "" {
		return fallback
	}
	if technicalLeakPattern.MatchString(trimmed) {
		return fallback
	}
	return trimmed
}

// SafeInstanceURI normalizes an HTTP request path into a safe relative URI.
func SafeInstanceURI(instancePath string) string {
	trimmed := strings.TrimSpace(instancePath)
	if trimmed == "" {
		return "/"
	}
	parsed, err := url.Parse(trimmed)
	if err != nil || parsed.Path == "" {
		return "/"
	}
	return parsed.Path
}

// FromErrorCode constructs a ProblemDetails from a catalog ErrorCode.
func FromErrorCode(ec ErrorCode, detail string, fieldErrors []FieldErrorItem, instancePath, traceID string) ProblemDetails {
	safeDetail := SanitizeDetail(detail, ec.DefaultTitleVi)
	var errsCopy []FieldErrorItem
	if len(fieldErrors) > 0 {
		errsCopy = make([]FieldErrorItem, len(fieldErrors))
		copy(errsCopy, fieldErrors)
	}
	return ProblemDetails{
		Type:     ec.ProblemTypeURI(),
		Title:    ec.DefaultTitleVi,
		Status:   ec.HTTPStatus,
		Detail:   safeDetail,
		Instance: SafeInstanceURI(instancePath),
		Code:     ec.Code,
		Msg:      ec.EffectiveMsg(),
		Errors:   errsCopy,
		TraceID:  strings.TrimSpace(traceID),
	}
}

// FromCode constructs a ProblemDetails by looking up code in error-codes.json.
// If code is not found in the catalog, it falls back to COMMON-500-INTERNAL-ERROR.
func FromCode(code, detail string, fieldErrors []FieldErrorItem, instancePath, traceID string) ProblemDetails {
	ec, ok := Lookup(code)
	if !ok {
		return InternalServerError(instancePath, traceID)
	}
	return FromErrorCode(ec, detail, fieldErrors, instancePath, traceID)
}

// ValidationError creates a 422 COMMON-422-VALIDATION ProblemDetails response.
func ValidationError(fieldErrors []FieldErrorItem, instancePath, traceID string) ProblemDetails {
	return FromCode(
		"COMMON-422-VALIDATION",
		"Dữ liệu gửi lên không hợp lệ. Vui lòng kiểm tra lại các trường thông tin.",
		fieldErrors,
		instancePath,
		traceID,
	)
}

// BadRequest creates a 400 COMMON-400-BAD-REQUEST ProblemDetails response.
func BadRequest(detail string, fieldErrors []FieldErrorItem, instancePath, traceID string) ProblemDetails {
	return FromCode("COMMON-400-BAD-REQUEST", detail, fieldErrors, instancePath, traceID)
}

// Unauthorized creates a 401 COMMON-401-UNAUTHORIZED ProblemDetails response.
func Unauthorized(detail, instancePath, traceID string) ProblemDetails {
	return FromCode("COMMON-401-UNAUTHORIZED", detail, nil, instancePath, traceID)
}

// Forbidden creates a 403 COMMON-403-FORBIDDEN ProblemDetails response.
func Forbidden(detail, instancePath, traceID string) ProblemDetails {
	return FromCode("COMMON-403-FORBIDDEN", detail, nil, instancePath, traceID)
}

// OutOfScope creates a 403 COMMON-403-OUT-OF-SCOPE ProblemDetails response.
func OutOfScope(detail, instancePath, traceID string) ProblemDetails {
	return FromCode("COMMON-403-OUT-OF-SCOPE", detail, nil, instancePath, traceID)
}

// NotFound creates a 404 COMMON-404-NOT-FOUND ProblemDetails response.
func NotFound(detail, instancePath, traceID string) ProblemDetails {
	return FromCode("COMMON-404-NOT-FOUND", detail, nil, instancePath, traceID)
}

// MethodNotAllowed creates a 405 COMMON-405-METHOD-NOT-ALLOWED ProblemDetails response.
func MethodNotAllowed(detail, instancePath, traceID string) ProblemDetails {
	return FromCode("COMMON-405-METHOD-NOT-ALLOWED", detail, nil, instancePath, traceID)
}

// ConcurrencyConflict creates a 409 COMMON-409-CONCURRENT-MODIFICATION ProblemDetails response (If-Match mismatch).
func ConcurrencyConflict(detail, instancePath, traceID string) ProblemDetails {
	return FromCode("COMMON-409-CONCURRENT-MODIFICATION", detail, nil, instancePath, traceID)
}

// PreconditionRequired creates a 428 COMMON-428-PRECONDITION-REQUIRED ProblemDetails response (missing If-Match).
func PreconditionRequired(detail, instancePath, traceID string) ProblemDetails {
	return FromCode("COMMON-428-PRECONDITION-REQUIRED", detail, nil, instancePath, traceID)
}

// UnsupportedMediaType creates a 415 COMMON-415-UNSUPPORTED-MEDIA-TYPE ProblemDetails response.
func UnsupportedMediaType(detail, instancePath, traceID string) ProblemDetails {
	return FromCode("COMMON-415-UNSUPPORTED-MEDIA-TYPE", detail, nil, instancePath, traceID)
}

// InternalServerError creates a safe 500 COMMON-500-INTERNAL-ERROR ProblemDetails response.
func InternalServerError(instancePath, traceID string) ProblemDetails {
	ec, ok := Lookup("COMMON-500-INTERNAL-ERROR")
	if !ok {
		ec = ErrorCode{
			Code:           "COMMON-500-INTERNAL-ERROR",
			HTTPStatus:     http.StatusInternalServerError,
			Slug:           "internal-error",
			DefaultTitleVi: "Hệ thống chưa xử lý được yêu cầu, vui lòng thử lại sau",
		}
	}
	return FromErrorCode(ec, SafeInternalErrorDetail, nil, instancePath, traceID)
}

// FromError converts any Go error into a safe ProblemDetails.
// If err is already a ProblemDetails (or wraps one), it is preserved after sanitizing Detail.
func FromError(err error, instancePath, traceID string) ProblemDetails {
	if err == nil {
		return InternalServerError(instancePath, traceID)
	}
	var pd ProblemDetails
	if errors.As(err, &pd) {
		pd.Detail = SanitizeDetail(pd.Detail, pd.Title)
		if pd.Instance == "" {
			pd.Instance = SafeInstanceURI(instancePath)
		}
		if pd.TraceID == "" {
			pd.TraceID = strings.TrimSpace(traceID)
		}
		return pd
	}
	return InternalServerError(instancePath, traceID)
}

// Write serializes ProblemDetails as application/problem+json to http.ResponseWriter.
func Write(w http.ResponseWriter, p ProblemDetails) {
	status := p.Status
	if status < 100 || status > 599 {
		status = http.StatusInternalServerError
	}
	w.Header().Set("Content-Type", ContentType)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(p)
}
