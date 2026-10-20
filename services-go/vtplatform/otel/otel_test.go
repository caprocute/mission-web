package otel_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

func TestInitAndConfigValidation(t *testing.T) {
	if _, err := otel.Init(otel.Config{}); err == nil {
		t.Fatalf("expected error when service.name is empty")
	}
	if _, err := otel.Init(otel.Config{ServiceName: "ledger-service"}); err == nil {
		t.Fatalf("expected error when cell is empty")
	}

	p, err := otel.Init(otel.Config{
		ServiceName: "ledger-service",
		Cell:        "cell-hn",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	attrs := p.ResourceAttributes()
	if attrs["service.name"] != "ledger-service" || attrs["cell"] != "cell-hn" {
		t.Fatalf("unexpected resource attributes: %+v", attrs)
	}
	if err := p.Shutdown(context.Background()); err != nil {
		t.Fatalf("unexpected shutdown error: %v", err)
	}
}

func TestParseAndPropagateTraceparent(t *testing.T) {
	valid := "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
	sc, err := otel.ParseTraceparent(valid)
	if err != nil {
		t.Fatalf("unexpected error parsing valid traceparent: %v", err)
	}
	if sc.TraceID != "4bf92f3577b34da6a3ce929d0e0e4736" || sc.SpanID != "00f067aa0ba902b7" {
		t.Fatalf("unexpected parsed SpanContext: %+v", sc)
	}
	if sc.Traceparent() != valid {
		t.Fatalf("expected round-trip %q, got %q", valid, sc.Traceparent())
	}

	invalids := []string{
		"",
		"invalid",
		"ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
		"00-00000000000000000000000000000000-00f067aa0ba902b7-01",
		"00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
	}
	for _, inv := range invalids {
		if _, err := otel.ParseTraceparent(inv); err == nil {
			t.Errorf("expected error for invalid traceparent %q", inv)
		}
	}
}

func TestSpanAndHTTPMiddleware(t *testing.T) {
	p, err := otel.Init(otel.Config{
		ServiceName:    "tenant-registry",
		ServiceVersion: "1.2.0",
		Cell:           "cell-hcm",
		Environment:    "staging",
	})
	if err != nil {
		t.Fatalf("failed to init provider: %v", err)
	}

	var capturedTraceID, capturedSpanID string
	handler := p.HTTPMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx, childSpan := p.StartSpan(r.Context(), "db.query")
		childSpan.SetAttribute("db.system", "postgresql")
		childSpan.RecordError(errors.New("transient warning"))
		childSpan.End()
		childSpan.End() // idempotent second call

		capturedTraceID = otel.TraceIDFromContext(ctx)
		capturedSpanID = otel.SpanIDFromContext(ctx)
		outHdr := http.Header{}
		otel.InjectHTTPHeaders(ctx, outHdr)
		if outHdr.Get(otel.HeaderTraceparent) == "" {
			t.Errorf("expected injected traceparent header")
		}
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/tenants", nil)
	req.Header.Set(otel.HeaderTraceparent, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
	req.Header.Set(otel.HeaderTracestate, "vt=cell-hcm")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if capturedTraceID != "4bf92f3577b34da6a3ce929d0e0e4736" {
		t.Fatalf("expected propagated trace.id, got %q", capturedTraceID)
	}
	if capturedSpanID == "" || capturedSpanID == "00f067aa0ba902b7" {
		t.Fatalf("expected new child span.id, got %q", capturedSpanID)
	}
	if p.CounterValue("http.server.requests") != 1 {
		t.Fatalf("expected request counter = 1, got %d", p.CounterValue("http.server.requests"))
	}
	spans := p.RecordedSpans()
	if len(spans) != 2 {
		t.Fatalf("expected 2 recorded spans (child + server), got %d", len(spans))
	}
}
