package problem_test

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"vn.viettel.okrkpi/services-go/vtplatform/problem"
)

func TestCatalogMatchesCanonicalErrorCodes(t *testing.T) {
	canonicalBytes, err := os.ReadFile("../../../backend/libs/vt-platform-starter/src/main/resources/vn/viettel/okrkpi/shared/error/error-codes.json")
	if err != nil {
		t.Fatalf("failed to read canonical error-codes.json: %v", err)
	}
	embeddedBytes, err := os.ReadFile("error-codes.json")
	if err != nil {
		t.Fatalf("failed to read problem/error-codes.json: %v", err)
	}
	if !bytes.Equal(canonicalBytes, embeddedBytes) {
		t.Fatalf("problem/error-codes.json differs from canonical error-codes.json")
	}

	codes := problem.AllCodes()
	if len(codes) < 100 {
		t.Fatalf("expected at least 100 error codes in catalog, got %d", len(codes))
	}

	ec, err := problem.Require("COMMON-403-FORBIDDEN")
	if err != nil {
		t.Fatalf("unexpected error looking up COMMON-403-FORBIDDEN: %v", err)
	}
	if ec.HTTPStatus != http.StatusForbidden {
		t.Fatalf("expected HTTP 403, got %d", ec.HTTPStatus)
	}
	if ec.ProblemTypeURI() != "https://okrkpi.vn/problems/forbidden" {
		t.Fatalf("unexpected ProblemTypeURI: %s", ec.ProblemTypeURI())
	}

	// Catalog entries extracted from contracts carry status/type/msg (current NEN-009 format).
	msgCode, ok := problem.Lookup("AI-422-UNREADABLE")
	if !ok {
		t.Fatalf("expected AI-422-UNREADABLE in catalog")
	}
	if msgCode.HTTPStatus != http.StatusUnprocessableEntity || msgCode.EffectiveMsg() != "MSG-07-06" ||
		msgCode.ProblemTypeURI() != "https://okrkpi.vn/problems/unreadable" || msgCode.DefaultTitleVi == "" {
		t.Fatalf("unexpected catalog entry: %+v", msgCode)
	}

	if _, err := problem.Require("NON-EXISTENT-CODE"); err == nil {
		t.Fatalf("expected error for unknown code")
	}
}

func TestSanitizeDetailAndHelpers(t *testing.T) {
	leaks := []string{
		"SELECT * FROM task.task WHERE tenant_id = '123'",
		"INSERT INTO ledger.outbox_event VALUES (1)",
		"UPDATE okr.objective SET status = 'A'",
		"DELETE FROM config.role_binding",
		"org.postgresql.util.PSQLException: ERROR: permission denied",
		"pgconn.PgError: SQLSTATE 42501",
		"goroutine 18 [running]:\nruntime.gopanic()",
		"NullPointerException\n  at vn.viettel.Main.run",
	}
	for _, leak := range leaks {
		got := problem.SanitizeDetail(leak, problem.SafeInternalErrorDetail)
		if got != problem.SafeInternalErrorDetail {
			t.Errorf("expected leak %q to be sanitized, got %q", leak, got)
		}
	}

	clean := "Hồ sơ đã được người khác cập nhật, vui lòng tải lại"
	if got := problem.SanitizeDetail(clean, "fallback"); got != clean {
		t.Errorf("expected clean message preserved, got %q", got)
	}

	v := problem.ValidationError(
		[]problem.FieldErrorItem{{Field: "title", Message: "Không được để trống", Rule: "BR-01-01"}},
		"/api/v1/tasks",
		"4bf92f3577b34da6a3ce929d0e0e4736",
	)
	if v.Status != http.StatusUnprocessableEntity || v.Code != "COMMON-422-VALIDATION" || len(v.Errors) != 1 {
		t.Fatalf("unexpected validation problem: %+v", v)
	}

	_ = problem.BadRequest("", nil, "", "t1")
	_ = problem.Unauthorized("", "://bad-uri", "t1")
	_ = problem.Forbidden("Thiếu claim tenant_id", "/api/v1/tenants", "t1")
	_ = problem.OutOfScope("", "/api/v1/tasks", "t1")
	_ = problem.NotFound("", "/api/v1/tasks/1", "t1")
	_ = problem.MethodNotAllowed("", "/api/v1/tasks", "t1")
	_ = problem.ConcurrencyConflict("", "/api/v1/tasks/1", "t1")
	_ = problem.PreconditionRequired("", "/api/v1/tasks/1", "t1")
	_ = problem.UnsupportedMediaType("", "/api/v1/tasks", "t1")

	unknown := problem.FromCode("UNKNOWN-999", "detail", nil, "/test", "t1")
	if unknown.Status != http.StatusInternalServerError || unknown.Code != "COMMON-500-INTERNAL-ERROR" {
		t.Fatalf("expected fallback 500 for unknown code, got %+v", unknown)
	}
}

func TestFromErrorAndWriteHTTP(t *testing.T) {
	wrapped := fmt.Errorf("context: %w", problem.Forbidden("SELECT secret FROM users", "/api/v1/ledger", "trace-123"))
	pd := problem.FromError(wrapped, "/api/v1/ledger", "trace-123")
	if pd.Status != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", pd.Status)
	}
	if pd.Detail != pd.Title {
		t.Fatalf("expected SQL leak inside wrapped ProblemDetails to be sanitized to Title, got %q", pd.Detail)
	}
	if pd.Error() == "" {
		t.Fatalf("expected non-empty Error() string")
	}

	generic := problem.FromError(errors.New("pgx: connection refused"), "/api/v1/ledger", "trace-456")
	if generic.Status != http.StatusInternalServerError || generic.Detail != problem.SafeInternalErrorDetail {
		t.Fatalf("expected generic error to map to safe 500, got %+v", generic)
	}

	rec := httptest.NewRecorder()
	problem.Write(rec, pd)
	res := rec.Result()
	if res.StatusCode != http.StatusForbidden {
		t.Fatalf("expected status 403, got %d", res.StatusCode)
	}
	if ct := res.Header.Get("Content-Type"); ct != problem.ContentType {
		t.Fatalf("expected Content-Type %s, got %s", problem.ContentType, ct)
	}
	var decoded problem.ProblemDetails
	if err := json.NewDecoder(res.Body).Decode(&decoded); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}
	if decoded.Code != "COMMON-403-FORBIDDEN" || decoded.TraceID != "trace-123" {
		t.Fatalf("unexpected decoded ProblemDetails: %+v", decoded)
	}
}
