// Package logx provides a structured JSON log/slog.Handler that automatically injects
// OpenTelemetry trace context (trace.id, span.id) and multi-tenant context (tenant.id),
// while scrubbing sensitive PII and credentials (JWT, 12-digit CCCD, phone numbers, emails).
package logx

import (
	"context"
	"io"
	"log/slog"
	"os"
	"regexp"
	"strings"

	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

const (
	RedactedJWT    = "[REDACTED_JWT]"
	RedactedCCCD   = "[REDACTED_CCCD]"
	RedactedPhone  = "[REDACTED_PHONE]"
	RedactedEmail  = "[REDACTED_EMAIL]"
	RedactedSecret = "[REDACTED_SECRET]"
)

var (
	// JWT pattern: 3 base64url segments starting with eyJ...
	jwtRegex = regexp.MustCompile(`(?i)(?:bearer\s+)?eyJ[A-Za-z0-9_-]{4,}\.eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*`)
	// Email address pattern
	emailRegex = regexp.MustCompile(`[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}`)
	// 12-digit Vietnamese citizen ID (CCCD)
	cccdRegex = regexp.MustCompile(`\b\d{12}\b`)
	// Vietnamese mobile/landline phone number (+84..., 84..., 03/05/07/08/09...)
	phoneRegex = regexp.MustCompile(`(?:\+84|84|0)(?:3|5|7|8|9)\d{8}\b`)

	sensitiveKeyNames = map[string]string{
		"authorization": RedactedJWT,
		"access_token":  RedactedJWT,
		"refresh_token": RedactedJWT,
		"id_token":      RedactedJWT,
		"token":         RedactedJWT,
		"password":      RedactedSecret,
		"secret":        RedactedSecret,
		"client_secret": RedactedSecret,
		"api_key":       RedactedSecret,
		"cccd":          RedactedCCCD,
		"national_id":   RedactedCCCD,
		"citizen_id":    RedactedCCCD,
		"phone":         RedactedPhone,
		"phone_number":  RedactedPhone,
		"so_dien_thoai": RedactedPhone,
		"email":         RedactedEmail,
	}
)

// Scrub replaces JWT tokens, emails, 12-digit CCCD numbers, and Vietnamese phone numbers in s.
func Scrub(s string) string {
	if s == "" {
		return ""
	}
	out := jwtRegex.ReplaceAllString(s, RedactedJWT)
	out = emailRegex.ReplaceAllString(out, RedactedEmail)
	out = cccdRegex.ReplaceAllString(out, RedactedCCCD)
	out = phoneRegex.ReplaceAllString(out, RedactedPhone)
	return out
}

// Options configures the JSON slog handler.
type Options struct {
	Level       slog.Leveler
	ServiceName string
	Cell        string
	Writer      io.Writer
}

// Handler wraps slog.JSONHandler to inject trace.id, span.id, tenant.id and scrub PII.
type Handler struct {
	inner       slog.Handler
	serviceName string
	cell        string
	groups      []string
}

// NewHandler creates a new scrubbing JSON slog.Handler.
func NewHandler(opts Options) *Handler {
	w := opts.Writer
	if w == nil {
		w = os.Stdout
	}
	lvl := opts.Level
	if lvl == nil {
		lvl = slog.LevelInfo
	}
	jsonHandler := slog.NewJSONHandler(w, &slog.HandlerOptions{
		Level:       lvl,
		ReplaceAttr: replaceAndScrubAttr,
	})
	return &Handler{
		inner:       jsonHandler,
		serviceName: strings.TrimSpace(opts.ServiceName),
		cell:        strings.TrimSpace(opts.Cell),
	}
}

// NewLogger creates a *slog.Logger configured with NewHandler.
func NewLogger(opts Options) *slog.Logger {
	return slog.New(NewHandler(opts))
}

// Enabled implements slog.Handler.
func (h *Handler) Enabled(ctx context.Context, level slog.Level) bool {
	return h.inner.Enabled(ctx, level)
}

// Handle implements slog.Handler, injecting trace.id, span.id, tenant.id, service.name, and cell at top level.
func (h *Handler) Handle(ctx context.Context, r slog.Record) error {
	cloned := slog.NewRecord(r.Time, r.Level, Scrub(r.Message), r.PC)

	if h.serviceName != "" {
		cloned.AddAttrs(slog.String("service.name", h.serviceName))
	}
	if h.cell != "" {
		cloned.AddAttrs(slog.String("cell", h.cell))
	}
	if ctx != nil {
		if sc, ok := otel.SpanFromContext(ctx); ok {
			cloned.AddAttrs(
				slog.String("trace.id", sc.TraceID),
				slog.String("span.id", sc.SpanID),
			)
		}
		if tid, ok := tenant.FromContext(ctx); ok {
			cloned.AddAttrs(slog.String("tenant.id", tid))
		}
		if actor, ok := tenant.ActorFromContext(ctx); ok {
			cloned.AddAttrs(slog.String("actor.id", actor))
		}
	}

	var userAttrs []slog.Attr
	r.Attrs(func(a slog.Attr) bool {
		userAttrs = append(userAttrs, scrubAttr(a))
		return true
	})
	for _, a := range h.wrapInGroups(userAttrs) {
		cloned.AddAttrs(a)
	}

	return h.inner.Handle(ctx, cloned)
}

func (h *Handler) wrapInGroups(attrs []slog.Attr) []slog.Attr {
	if len(attrs) == 0 || len(h.groups) == 0 {
		return attrs
	}
	cur := attrs
	for i := len(h.groups) - 1; i >= 0; i-- {
		anyAttrs := make([]any, len(cur))
		for j, a := range cur {
			anyAttrs[j] = a
		}
		cur = []slog.Attr{slog.Group(h.groups[i], anyAttrs...)}
	}
	return cur
}

// WithAttrs implements slog.Handler.
func (h *Handler) WithAttrs(attrs []slog.Attr) slog.Handler {
	scrubbed := make([]slog.Attr, len(attrs))
	for i, a := range attrs {
		scrubbed[i] = scrubAttr(a)
	}
	groupsCopy := append([]string(nil), h.groups...)
	return &Handler{
		inner:       h.inner.WithAttrs(h.wrapInGroups(scrubbed)),
		serviceName: h.serviceName,
		cell:        h.cell,
		groups:      groupsCopy,
	}
}

// WithGroup implements slog.Handler.
func (h *Handler) WithGroup(name string) slog.Handler {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		return h
	}
	groupsCopy := make([]string, len(h.groups)+1)
	copy(groupsCopy, h.groups)
	groupsCopy[len(h.groups)] = trimmed
	return &Handler{
		inner:       h.inner,
		serviceName: h.serviceName,
		cell:        h.cell,
		groups:      groupsCopy,
	}
}

func replaceAndScrubAttr(_ []string, a slog.Attr) slog.Attr {
	return scrubAttr(a)
}

func scrubAttr(a slog.Attr) slog.Attr {
	val := a.Value.Resolve()
	lowerKey := strings.ToLower(strings.TrimSpace(a.Key))
	if mask, isSensitiveKey := sensitiveKeyNames[lowerKey]; isSensitiveKey && val.Kind() != slog.KindGroup {
		return slog.String(a.Key, mask)
	}

	switch val.Kind() {
	case slog.KindString:
		return slog.String(a.Key, Scrub(val.String()))
	case slog.KindGroup:
		groupAttrs := val.Group()
		out := make([]any, len(groupAttrs))
		for i, ga := range groupAttrs {
			out[i] = scrubAttr(ga)
		}
		return slog.Group(a.Key, out...)
	case slog.KindAny:
		anyVal := val.Any()
		switch v := anyVal.(type) {
		case error:
			if v != nil {
				return slog.String(a.Key, Scrub(v.Error()))
			}
		case []string:
			out := make([]string, len(v))
			for i, item := range v {
				out[i] = Scrub(item)
			}
			return slog.Any(a.Key, out)
		case map[string]string:
			out := make(map[string]string, len(v))
			for mk, mv := range v {
				if mask, ok := sensitiveKeyNames[strings.ToLower(strings.TrimSpace(mk))]; ok {
					out[mk] = mask
				} else {
					out[mk] = Scrub(mv)
				}
			}
			return slog.Any(a.Key, out)
		case map[string]any:
			out := make(map[string]any, len(v))
			for mk, mv := range v {
				if mask, ok := sensitiveKeyNames[strings.ToLower(strings.TrimSpace(mk))]; ok {
					out[mk] = mask
				} else if sv, isStr := mv.(string); isStr {
					out[mk] = Scrub(sv)
				} else {
					out[mk] = mv
				}
			}
			return slog.Any(a.Key, out)
		}
	}
	return a
}
