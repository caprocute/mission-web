// Package tenant provides multi-tenant context propagation and net/http middleware
// that extracts the tenant identifier from verified JWT claims (default claim "tenant_id")
// and returns an RFC 9457 403 Problem Details response when missing or invalid.
package tenant

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"

	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/problem"
)

const (
	// DefaultClaimName is the default JWT claim key holding the tenant UUID (NEN-010).
	DefaultClaimName = "tenant_id"
)

var (
	// ErrMissingTenant is returned when no tenant_id is found in context or JWT claims.
	ErrMissingTenant = errors.New("tenant: missing tenant_id in context")
	// ErrInvalidTenantUUID is returned when tenant_id is not a valid canonical UUID.
	ErrInvalidTenantUUID = errors.New("tenant: tenant_id must be a valid UUID")

	uuidRegex = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
)

type tenantContextKey struct{}
type actorContextKey struct{}
type claimsContextKey struct{}

// IsValidUUID reports whether s is a valid 36-character canonical UUID string.
func IsValidUUID(s string) bool {
	return uuidRegex.MatchString(strings.TrimSpace(s))
}

// NormalizeUUID validates and lowercases a canonical UUID string.
func NormalizeUUID(s string) (string, error) {
	trimmed := strings.ToLower(strings.TrimSpace(s))
	if !uuidRegex.MatchString(trimmed) {
		return "", fmt.Errorf("%w: %q", ErrInvalidTenantUUID, s)
	}
	return trimmed, nil
}

// WithTenantID returns a new context carrying the validated, lowercased tenant UUID.
func WithTenantID(ctx context.Context, tenantID string) (context.Context, error) {
	norm, err := NormalizeUUID(tenantID)
	if err != nil {
		return ctx, err
	}
	return context.WithValue(ctx, tenantContextKey{}, norm), nil
}

// MustWithTenantID stores tenantID in ctx or panics if tenantID is not a valid UUID (for tests).
func MustWithTenantID(ctx context.Context, tenantID string) context.Context {
	next, err := WithTenantID(ctx, tenantID)
	if err != nil {
		panic(err)
	}
	return next
}

// FromContext extracts the validated tenant UUID from ctx.
func FromContext(ctx context.Context) (string, bool) {
	if ctx == nil {
		return "", false
	}
	val, ok := ctx.Value(tenantContextKey{}).(string)
	if !ok || !IsValidUUID(val) {
		return "", false
	}
	return val, true
}

// MustFromContext extracts the tenant UUID from ctx or returns ErrMissingTenant.
func MustFromContext(ctx context.Context) (string, error) {
	tid, ok := FromContext(ctx)
	if !ok {
		return "", ErrMissingTenant
	}
	return tid, nil
}

// WithActorID stores the authenticated user/service actor ID (`sub`) in ctx.
func WithActorID(ctx context.Context, actorID string) context.Context {
	return context.WithValue(ctx, actorContextKey{}, strings.TrimSpace(actorID))
}

// ActorFromContext extracts the actor ID from ctx, if present.
func ActorFromContext(ctx context.Context) (string, bool) {
	if ctx == nil {
		return "", false
	}
	val, ok := ctx.Value(actorContextKey{}).(string)
	return val, ok && val != ""
}

// WithJWTClaims attaches a map of verified JWT claims to ctx.
func WithJWTClaims(ctx context.Context, claims map[string]any) context.Context {
	return context.WithValue(ctx, claimsContextKey{}, claims)
}

// JWTClaimsFromContext retrieves verified JWT claims from ctx, if present.
func JWTClaimsFromContext(ctx context.Context) (map[string]any, bool) {
	if ctx == nil {
		return nil, false
	}
	c, ok := ctx.Value(claimsContextKey{}).(map[string]any)
	return c, ok && c != nil
}

// MiddlewareConfig configures the net/http tenant extraction middleware.
type MiddlewareConfig struct {
	// ClaimName is the primary JWT claim name for tenant ID (defaults to "tenant_id").
	ClaimName string
	// FallbackClaimNames are optional secondary JWT claim names (defaults to ["tenant", "tid"]).
	FallbackClaimNames []string
	// TokenVerifier optionally verifies a raw Bearer token and returns its claims.
	// If nil, the middleware parses the payload of the pre-verified Bearer JWT from the API Gateway / Envoy.
	TokenVerifier func(ctx context.Context, rawToken string) (map[string]any, error)
}

func (cfg MiddlewareConfig) effectiveClaimNames() []string {
	primary := strings.TrimSpace(cfg.ClaimName)
	if primary == "" {
		primary = DefaultClaimName
	}
	names := []string{primary}
	if len(cfg.FallbackClaimNames) > 0 {
		for _, fb := range cfg.FallbackClaimNames {
			if trimmed := strings.TrimSpace(fb); trimmed != "" && trimmed != primary {
				names = append(names, trimmed)
			}
		}
	} else {
		for _, fb := range []string{"tenant", "tid"} {
			if fb != primary {
				names = append(names, fb)
			}
		}
	}
	return names
}

// ParseVerifiedJWTPayload decodes the JSON payload segment of a JWT that has already been
// signature-verified by the upstream gateway or auth middleware.
func ParseVerifiedJWTPayload(rawToken string) (map[string]any, error) {
	parts := strings.Split(strings.TrimSpace(rawToken), ".")
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" {
		return nil, errors.New("tenant: malformed JWT token")
	}
	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("tenant: invalid base64url JWT payload: %w", err)
	}
	var claims map[string]any
	if err := json.Unmarshal(payloadBytes, &claims); err != nil {
		return nil, fmt.Errorf("tenant: invalid JSON in JWT payload: %w", err)
	}
	return claims, nil
}

// ExtractTenantFromClaims looks up the configured tenant claim in claims and validates its UUID format.
func ExtractTenantFromClaims(claims map[string]any, claimNames []string) (string, error) {
	if len(claims) == 0 {
		return "", ErrMissingTenant
	}
	if len(claimNames) == 0 {
		claimNames = []string{DefaultClaimName, "tenant", "tid"}
	}
	for _, name := range claimNames {
		raw, exists := claims[name]
		if !exists || raw == nil {
			continue
		}
		strVal, ok := raw.(string)
		if !ok || strings.TrimSpace(strVal) == "" {
			return "", ErrInvalidTenantUUID
		}
		return NormalizeUUID(strVal)
	}
	return "", ErrMissingTenant
}

// Middleware returns a net/http middleware that extracts the tenant claim from the verified JWT,
// stores it in r.Context(), or responds with 403 Problem Details (COMMON-403-FORBIDDEN) when missing/invalid.
func Middleware(cfg MiddlewareConfig) func(http.Handler) http.Handler {
	claimNames := cfg.effectiveClaimNames()
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ctx := r.Context()
			traceID := otel.TraceIDFromContext(ctx)

			claims, ok := JWTClaimsFromContext(ctx)
			if !ok {
				authHeader := strings.TrimSpace(r.Header.Get("Authorization"))
				if !strings.HasPrefix(strings.ToLower(authHeader), "bearer ") {
					problem.Write(w, problem.Forbidden(
						"Thiếu thông tin định danh đơn vị thuê bao (tenant_id) trong phiên xác thực.",
						r.URL.Path,
						traceID,
					))
					return
				}
				rawToken := strings.TrimSpace(authHeader[len("Bearer "):])
				var err error
				if cfg.TokenVerifier != nil {
					claims, err = cfg.TokenVerifier(ctx, rawToken)
				} else {
					claims, err = ParseVerifiedJWTPayload(rawToken)
				}
				if err != nil {
					problem.Write(w, problem.Forbidden(
						"Không thể trích xuất thông tin đơn vị thuê bao từ phiên xác thực.",
						r.URL.Path,
						traceID,
					))
					return
				}
				ctx = WithJWTClaims(ctx, claims)
			}

			tenantID, err := ExtractTenantFromClaims(claims, claimNames)
			if err != nil {
				problem.Write(w, problem.Forbidden(
					"Phiên xác thực không chứa định danh đơn vị thuê bao (tenant_id) hợp lệ.",
					r.URL.Path,
					traceID,
				))
				return
			}

			ctx, _ = WithTenantID(ctx, tenantID)
			if sub, ok := claims["sub"].(string); ok && strings.TrimSpace(sub) != "" {
				ctx = WithActorID(ctx, sub)
			}
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}
