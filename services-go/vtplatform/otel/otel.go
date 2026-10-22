// Package otel provides OpenTelemetry resource configuration, W3C Trace Context
// propagation (traceparent), span management, and metric recording for Go microservices
// (SVC-03 tenant-registry, SVC-09 ledger-service, SVC-12 notification-service).
package otel

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	// HeaderTraceparent is the W3C Trace Context header name.
	HeaderTraceparent = "traceparent"
	// HeaderTracestate is the W3C Trace State header name.
	HeaderTracestate = "tracestate"
	// TraceVersion00 is version 00 of W3C Trace Context.
	TraceVersion00 = "00"
)

var traceparentRegex = regexp.MustCompile(`^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$`)

// Config holds OpenTelemetry SDK initialization parameters (TL-01 L2929-2935).
type Config struct {
	ServiceName    string
	ServiceVersion string
	Cell           string
	Environment    string
	OTLPEndpoint   string
}

// Validate checks mandatory resource attributes (service.name and cell).
func (c Config) Validate() error {
	if strings.TrimSpace(c.ServiceName) == "" {
		return errors.New("otel: service.name is required")
	}
	if strings.TrimSpace(c.Cell) == "" {
		return errors.New("otel: cell is required")
	}
	return nil
}

// ResourceAttributes returns the canonical OpenTelemetry resource attribute map.
func (c Config) ResourceAttributes() map[string]string {
	env := strings.TrimSpace(c.Environment)
	if env == "" {
		env = "production"
	}
	ver := strings.TrimSpace(c.ServiceVersion)
	if ver == "" {
		ver = "1.0.0"
	}
	return map[string]string{
		"service.name":           strings.TrimSpace(c.ServiceName),
		"service.version":        ver,
		"cell":                   strings.TrimSpace(c.Cell),
		"deployment.environment": env,
	}
}

// SpanContext represents a W3C Trace Context (trace.id, span.id, flags).
type SpanContext struct {
	TraceID    string
	SpanID     string
	ParentID   string
	TraceFlags string
	TraceState string
}

// IsValid reports whether TraceID (32 hex non-zero) and SpanID (16 hex non-zero) are valid.
func (sc SpanContext) IsValid() bool {
	return len(sc.TraceID) == 32 && sc.TraceID != "00000000000000000000000000000000" &&
		len(sc.SpanID) == 16 && sc.SpanID != "0000000000000000"
}

// Traceparent formats the SpanContext as a W3C traceparent string ("00-<traceId>-<spanId>-<flags>").
func (sc SpanContext) Traceparent() string {
	if !sc.IsValid() {
		return ""
	}
	flags := sc.TraceFlags
	if len(flags) != 2 {
		flags = "01"
	}
	return fmt.Sprintf("%s-%s-%s-%s", TraceVersion00, sc.TraceID, sc.SpanID, flags)
}

// ParseTraceparent parses and validates a W3C traceparent header string.
func ParseTraceparent(header string) (SpanContext, error) {
	trimmed := strings.ToLower(strings.TrimSpace(header))
	matches := traceparentRegex.FindStringSubmatch(trimmed)
	if matches == nil {
		return SpanContext{}, fmt.Errorf("otel: invalid traceparent format %q", header)
	}
	version, traceID, spanID, flags := matches[1], matches[2], matches[3], matches[4]
	if version == "ff" {
		return SpanContext{}, errors.New("otel: invalid traceparent version ff")
	}
	if traceID == "00000000000000000000000000000000" {
		return SpanContext{}, errors.New("otel: traceId cannot be all zeros")
	}
	if spanID == "0000000000000000" {
		return SpanContext{}, errors.New("otel: spanId cannot be all zeros")
	}
	return SpanContext{
		TraceID:    traceID,
		SpanID:     spanID,
		TraceFlags: flags,
	}, nil
}

// NewSpanContext generates a new root or child SpanContext.
func NewSpanContext(parent SpanContext) SpanContext {
	traceID := parent.TraceID
	parentSpanID := ""
	if parent.IsValid() {
		parentSpanID = parent.SpanID
	} else {
		traceID = randomHex(16)
	}
	flags := parent.TraceFlags
	if len(flags) != 2 {
		flags = "01"
	}
	return SpanContext{
		TraceID:    traceID,
		SpanID:     randomHex(8),
		ParentID:   parentSpanID,
		TraceFlags: flags,
		TraceState: parent.TraceState,
	}
}

func randomHex(nBytes int) string {
	buf := make([]byte, nBytes)
	if _, err := rand.Read(buf); err != nil {
		// Deterministic fallback using timestamp if crypto/rand fails
		now := uint64(time.Now().UnixNano())
		for i := range buf {
			buf[i] = byte(now >> ((i % 8) * 8))
		}
	}
	return hex.EncodeToString(buf)
}

type spanContextKey struct{}
type providerContextKey struct{}

// WithSpanContext stores a SpanContext in ctx.
func WithSpanContext(ctx context.Context, sc SpanContext) context.Context {
	return context.WithValue(ctx, spanContextKey{}, sc)
}

// SpanFromContext extracts the SpanContext from ctx, if present.
func SpanFromContext(ctx context.Context) (SpanContext, bool) {
	if ctx == nil {
		return SpanContext{}, false
	}
	sc, ok := ctx.Value(spanContextKey{}).(SpanContext)
	if !ok || !sc.IsValid() {
		return SpanContext{}, false
	}
	return sc, true
}

// TraceIDFromContext returns the 32-hex trace.id from ctx, or empty string if absent.
func TraceIDFromContext(ctx context.Context) string {
	if sc, ok := SpanFromContext(ctx); ok {
		return sc.TraceID
	}
	return ""
}

// SpanIDFromContext returns the 16-hex span.id from ctx, or empty string if absent.
func SpanIDFromContext(ctx context.Context) string {
	if sc, ok := SpanFromContext(ctx); ok {
		return sc.SpanID
	}
	return ""
}

// TraceparentFromContext returns the W3C traceparent string from ctx, or empty string if absent.
func TraceparentFromContext(ctx context.Context) string {
	if sc, ok := SpanFromContext(ctx); ok {
		return sc.Traceparent()
	}
	return ""
}

// ExtractHTTPHeaders extracts W3C Trace Context from http.Header into ctx, or creates a new root span context.
func ExtractHTTPHeaders(ctx context.Context, headers http.Header) (context.Context, SpanContext) {
	if headers != nil {
		raw := headers.Get(HeaderTraceparent)
		if raw != "" {
			if parsed, err := ParseTraceparent(raw); err == nil {
				child := NewSpanContext(parsed)
				child.TraceState = headers.Get(HeaderTracestate)
				return WithSpanContext(ctx, child), child
			}
		}
	}
	root := NewSpanContext(SpanContext{})
	return WithSpanContext(ctx, root), root
}

// InjectHTTPHeaders writes W3C traceparent (and optional tracestate) from ctx into http.Header.
func InjectHTTPHeaders(ctx context.Context, headers http.Header) {
	if headers == nil {
		return
	}
	if sc, ok := SpanFromContext(ctx); ok {
		headers.Set(HeaderTraceparent, sc.Traceparent())
		if sc.TraceState != "" {
			headers.Set(HeaderTracestate, sc.TraceState)
		}
	}
}

// RecordedSpan represents a completed span recorded by Provider.
type RecordedSpan struct {
	Name       string
	Context    SpanContext
	StartTime  time.Time
	EndTime    time.Time
	Attributes map[string]string
	Err        error
}

// Span is an active trace span.
type Span struct {
	provider   *Provider
	name       string
	sc         SpanContext
	start      time.Time
	mu         sync.Mutex
	attributes map[string]string
	err        error
	ended      bool
}

// Context returns the SpanContext of this Span.
func (s *Span) Context() SpanContext {
	return s.sc
}

// SetAttribute sets a key-value attribute on the span.
func (s *Span) SetAttribute(k, v string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.attributes == nil {
		s.attributes = make(map[string]string)
	}
	s.attributes[k] = v
}

// RecordError records an error on the span.
func (s *Span) RecordError(err error) {
	if err == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.err = err
}

// End completes the span and records it in the Provider.
func (s *Span) End() {
	s.mu.Lock()
	if s.ended {
		s.mu.Unlock()
		return
	}
	s.ended = true
	attrsCopy := make(map[string]string, len(s.attributes))
	for k, v := range s.attributes {
		attrsCopy[k] = v
	}
	rec := RecordedSpan{
		Name:       s.name,
		Context:    s.sc,
		StartTime:  s.start,
		EndTime:    time.Now().UTC(),
		Attributes: attrsCopy,
		Err:        s.err,
	}
	s.mu.Unlock()
	if s.provider != nil {
		s.provider.recordSpan(rec)
	}
}

// Provider manages tracing and metrics for a Go service instance.
type Provider struct {
	cfg      Config
	resAttrs map[string]string
	mu       sync.RWMutex
	spans    []RecordedSpan
	counters map[string]int64
}

// Init initializes a new OpenTelemetry Provider and validates resource attributes.
func Init(cfg Config) (*Provider, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return &Provider{
		cfg:      cfg,
		resAttrs: cfg.ResourceAttributes(),
		counters: make(map[string]int64),
	}, nil
}

// WithProvider attaches p to ctx.
func WithProvider(ctx context.Context, p *Provider) context.Context {
	return context.WithValue(ctx, providerContextKey{}, p)
}

// ProviderFromContext retrieves the Provider from ctx, if any.
func ProviderFromContext(ctx context.Context) (*Provider, bool) {
	if ctx == nil {
		return nil, false
	}
	p, ok := ctx.Value(providerContextKey{}).(*Provider)
	return p, ok && p != nil
}

// ResourceAttributes returns a copy of the resource attributes (`service.name`, `cell`, etc.).
func (p *Provider) ResourceAttributes() map[string]string {
	out := make(map[string]string, len(p.resAttrs))
	for k, v := range p.resAttrs {
		out[k] = v
	}
	return out
}

// StartSpan starts a new Span, linking to any parent SpanContext in ctx.
func (p *Provider) StartSpan(ctx context.Context, name string) (context.Context, *Span) {
	parent, _ := SpanFromContext(ctx)
	sc := NewSpanContext(parent)
	attrs := p.ResourceAttributes()
	sp := &Span{
		provider:   p,
		name:       name,
		sc:         sc,
		start:      time.Now().UTC(),
		attributes: attrs,
	}
	ctx = WithSpanContext(ctx, sc)
	ctx = WithProvider(ctx, p)
	return ctx, sp
}

// AddCounter increments a named metric counter by delta.
func (p *Provider) AddCounter(name string, delta int64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.counters[name] += delta
}

// CounterValue returns the current value of a named metric counter.
func (p *Provider) CounterValue(name string) int64 {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.counters[name]
}

// RecordedSpans returns a snapshot of completed spans (useful for testing and local exporting).
func (p *Provider) RecordedSpans() []RecordedSpan {
	p.mu.RLock()
	defer p.mu.RUnlock()
	out := make([]RecordedSpan, len(p.spans))
	copy(out, p.spans)
	return out
}

// Shutdown flushes recorded telemetry on service shutdown.
func (p *Provider) Shutdown(_ context.Context) error {
	return nil
}

// maxRecordedSpans giới hạn số span giữ trong bộ nhớ để tiến trình chạy lâu không tăng bộ nhớ theo số yêu cầu.
const maxRecordedSpans = 4096

func (p *Provider) recordSpan(rec RecordedSpan) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.spans) >= maxRecordedSpans {
		n := copy(p.spans, p.spans[len(p.spans)-maxRecordedSpans/2:])
		p.spans = p.spans[:n]
	}
	p.spans = append(p.spans, rec)
}

// HTTPMiddleware extracts/creates W3C traceparent on incoming HTTP requests, starts a span,
// echoes the traceparent response header, and increments the http.server.requests counter.
func (p *Provider) HTTPMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx, sc := ExtractHTTPHeaders(r.Context(), r.Header)
		ctx = WithProvider(ctx, p)
		span := &Span{
			provider:   p,
			name:       r.Method + " " + r.URL.Path,
			sc:         sc,
			start:      time.Now().UTC(),
			attributes: p.ResourceAttributes(),
		}
		span.SetAttribute("http.request.method", r.Method)
		span.SetAttribute("url.path", r.URL.Path)
		defer span.End()

		w.Header().Set(HeaderTraceparent, sc.Traceparent())
		p.AddCounter("http.server.requests", 1)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}
