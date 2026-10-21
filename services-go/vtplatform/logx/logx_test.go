package logx_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"strings"
	"testing"

	"vn.viettel.okrkpi/services-go/vtplatform/logx"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

func TestScrubMasksJWTEmailCCCDAndPhone(t *testing.T) {
	raw := "Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c " +
		"CCCD 001090012345 SĐT 0987654321 và +84912345678 email canbo.test@viettel.com.vn"
	got := logx.Scrub(raw)
	if strings.Contains(got, "eyJhbGci") {
		t.Fatalf("JWT was not scrubbed: %s", got)
	}
	if strings.Contains(got, "001090012345") {
		t.Fatalf("CCCD was not scrubbed: %s", got)
	}
	if strings.Contains(got, "0987654321") || strings.Contains(got, "+84912345678") {
		t.Fatalf("Phone was not scrubbed: %s", got)
	}
	if strings.Contains(got, "canbo.test@viettel.com.vn") {
		t.Fatalf("Email was not scrubbed: %s", got)
	}
	if !strings.Contains(got, logx.RedactedJWT) ||
		!strings.Contains(got, logx.RedactedCCCD) ||
		!strings.Contains(got, logx.RedactedPhone) ||
		!strings.Contains(got, logx.RedactedEmail) {
		t.Fatalf("expected redaction placeholders in %s", got)
	}
}

func TestJSONHandlerInjectsTraceSpanTenantAndScrubsAttrs(t *testing.T) {
	var buf bytes.Buffer
	logger := logx.NewLogger(logx.Options{
		Level:       slog.LevelDebug,
		ServiceName: "notification-service",
		Cell:        "cell-hn",
		Writer:      &buf,
	})

	ctx := context.Background()
	ctx = otel.WithSpanContext(ctx, otel.SpanContext{
		TraceID:    "4bf92f3577b34da6a3ce929d0e0e4736",
		SpanID:     "00f067aa0ba902b7",
		TraceFlags: "01",
	})
	ctx = tenant.MustWithTenantID(ctx, "01928c3e-7a51-7c2e-9b0f-1f2a3b4c5d6e")
	ctx = tenant.WithActorID(ctx, "01928c3e-1111-7000-8000-222222222222")

	childLogger := logger.With(slog.String("password", "super-secret-123")).WithGroup("req")
	childLogger.InfoContext(ctx,
		"Gửi thông báo tới 0912345678 (a@b.vn)",
		slog.String("detail", "CCCD=038095001122 token=eyJhbGciOiJIUzI1NiJ9.eyJ0ZW5hbnQiOiJ0MSJ9.abc"),
		slog.Group("user", slog.String("email", "nguyen.van.a@gov.vn")),
		slog.Any("err", errors.New("failed for 0988112233")),
		slog.Any("tags", []string{"cccd:001200012345"}),
		slog.Any("meta", map[string]string{"authorization": "Bearer xyz", "note": "mail x@y.vn"}),
	)

	var entry map[string]any
	if err := json.Unmarshal(buf.Bytes(), &entry); err != nil {
		t.Fatalf("failed to parse JSON log line: %v\nraw: %s", err, buf.String())
	}

	if entry["trace.id"] != "4bf92f3577b34da6a3ce929d0e0e4736" {
		t.Fatalf("expected trace.id in JSON log, got %v", entry["trace.id"])
	}
	if entry["span.id"] != "00f067aa0ba902b7" {
		t.Fatalf("expected span.id in JSON log, got %v", entry["span.id"])
	}
	if entry["tenant.id"] != "01928c3e-7a51-7c2e-9b0f-1f2a3b4c5d6e" {
		t.Fatalf("expected tenant.id in JSON log, got %v", entry["tenant.id"])
	}
	if entry["service.name"] != "notification-service" || entry["cell"] != "cell-hn" {
		t.Fatalf("expected service.name and cell, got %v / %v", entry["service.name"], entry["cell"])
	}
	if entry["password"] != logx.RedactedSecret {
		t.Fatalf("expected password redacted, got %v", entry["password"])
	}

	rawJSON := buf.String()
	for _, forbidden := range []string{
		"super-secret-123",
		"0912345678",
		"a@b.vn",
		"038095001122",
		"eyJhbGciOiJIUzI1NiJ9",
		"nguyen.van.a@gov.vn",
		"0988112233",
		"001200012345",
		"x@y.vn",
	} {
		if strings.Contains(rawJSON, forbidden) {
			t.Errorf("log output leaked sensitive value %q: %s", forbidden, rawJSON)
		}
	}
}
