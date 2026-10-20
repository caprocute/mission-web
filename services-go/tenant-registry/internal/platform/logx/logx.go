// Package logx dựng nhật ký JSON có cấu trúc (log/slog) kèm traceparent W3C, tenant và mã tác nhân AC,
// cùng middleware HTTP tạo span và số đo qua OpenTelemetry API (BACKEND.md mục 3.9, 3.10).
// Không ghi token, dữ liệu C2/C3 hay thân yêu cầu vào nhật ký.
// Điểm chờ nền móng: NEN-017 (vtplatform/logx, vtplatform/otel) cài SDK, exporter OTLP và bộ che dữ liệu;
// ở đây provider toàn cục của OpenTelemetry mặc định là noop nên span và số đo không đi đâu cho tới khi
// NEN-017 đăng ký provider thật.
package logx

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"log/slog"
	"net/http"
	"os"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

const instrumentation = "mission/services-go/tenant-registry"

type ctxKey int

const (
	keyTrace ctxKey = iota
	keyFields
)

// TraceInfo là ngữ cảnh truy vết của một request.
type TraceInfo struct {
	TraceID     string
	SpanID      string
	TraceParent string
}

// Fields là các trường nhật ký được tầng sau bổ sung (tenant, tác nhân) sau khi xác thực.
type Fields struct {
	TenantID string
	Actor    string // mã AC (AC-01...) hoặc "service_client"
}

// New tạo logger JSON ghi ra stdout, mức lấy từ biến môi trường LOG_LEVEL (debug, info, warn, error).
func New(service string) *slog.Logger {
	level := slog.LevelInfo
	_ = level.UnmarshalText([]byte(os.Getenv("LOG_LEVEL")))
	h := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level})
	return slog.New(h).With("service.name", service)
}

// Trace trả ngữ cảnh truy vết của request (rỗng nếu ngoài request).
func Trace(ctx context.Context) TraceInfo {
	t, _ := ctx.Value(keyTrace).(TraceInfo)
	return t
}

// SetTenant ghi tenant và tác nhân vào trường nhật ký của request hiện hành.
func SetTenant(ctx context.Context, tenantID, actor string) {
	if f, ok := ctx.Value(keyFields).(*Fields); ok {
		f.TenantID = tenantID
		f.Actor = actor
	}
}

// Attrs trả thuộc tính nhật ký chuẩn của request để tầng nghiệp vụ dùng với logger.
func Attrs(ctx context.Context) []any {
	t := Trace(ctx)
	out := []any{"trace.id", t.TraceID, "span.id", t.SpanID, "traceparent", t.TraceParent}
	if f, ok := ctx.Value(keyFields).(*Fields); ok {
		if f.TenantID != "" {
			out = append(out, "tenant.id", f.TenantID)
		}
		if f.Actor != "" {
			out = append(out, "actor.ac", f.Actor)
		}
	}
	return out
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.ResponseWriter.Write(b)
}

// Middleware đọc traceparent (W3C), mở span, ghi số đo thời gian xử lý và một dòng nhật ký mỗi request.
// Probe /healthz, /readyz không ghi nhật ký để tránh nhiễu.
func Middleware(log *slog.Logger, next http.Handler) http.Handler {
	prop := propagation.TraceContext{}
	tracer := otel.Tracer(instrumentation)
	meter := otel.Meter(instrumentation)
	hist, _ := meter.Float64Histogram("http.server.request.duration", metric.WithUnit("s"),
		metric.WithDescription("Thời gian xử lý request HTTP"))

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ctx := prop.Extract(r.Context(), propagation.HeaderCarrier(r.Header))
		ctx, span := tracer.Start(ctx, r.Method+" "+r.URL.Path, trace.WithSpanKind(trace.SpanKindServer))
		defer span.End()

		sc := span.SpanContext()
		ti := TraceInfo{}
		if sc.IsValid() {
			ti.TraceID = sc.TraceID().String()
			ti.SpanID = sc.SpanID().String()
		} else if parent := trace.SpanContextFromContext(ctx); parent.IsValid() {
			ti.TraceID = parent.TraceID().String()
			ti.SpanID = randomHex(8)
		} else {
			ti.TraceID = randomHex(16)
			ti.SpanID = randomHex(8)
		}
		ti.TraceParent = "00-" + ti.TraceID + "-" + ti.SpanID + "-01"
		fields := &Fields{}
		ctx = context.WithValue(ctx, keyTrace, ti)
		ctx = context.WithValue(ctx, keyFields, fields)
		w.Header().Set("traceparent", ti.TraceParent)

		sw := &statusWriter{ResponseWriter: w}
		next.ServeHTTP(sw, r.WithContext(ctx))
		if sw.status == 0 {
			sw.status = http.StatusOK
		}
		elapsed := time.Since(start)
		route := r.Pattern
		if route == "" {
			route = "unmatched"
		}
		hist.Record(ctx, elapsed.Seconds(), metric.WithAttributes(
			attribute.String("http.request.method", r.Method),
			attribute.String("http.route", route),
			attribute.Int("http.response.status_code", sw.status)))
		if r.URL.Path == "/healthz" || r.URL.Path == "/readyz" {
			return
		}
		attrs := append(Attrs(ctx),
			"http.request.method", r.Method,
			"http.route", route,
			"http.response.status_code", sw.status,
			"duration_ms", float64(elapsed.Microseconds())/1000)
		lvl := slog.LevelInfo
		if sw.status >= 500 {
			lvl = slog.LevelError
		}
		log.Log(ctx, lvl, "http request", attrs...)
	})
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
