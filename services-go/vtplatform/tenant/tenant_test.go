package tenant_test

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"vn.viettel.okrkpi/services-go/vtplatform/problem"
	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

func makeTestJWT(claims map[string]any) string {
	hdr := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256","typ":"JWT"}`))
	bodyBytes, _ := json.Marshal(claims)
	body := base64.RawURLEncoding.EncodeToString(bodyBytes)
	return hdr + "." + body + ".sig"
}

func TestContextHelpers(t *testing.T) {
	ctx := context.Background()
	if _, ok := tenant.FromContext(ctx); ok {
		t.Fatalf("expected no tenant in empty context")
	}
	if _, err := tenant.MustFromContext(ctx); err == nil {
		t.Fatalf("expected error from MustFromContext on empty context")
	}
	if _, err := tenant.WithTenantID(ctx, "not-a-uuid"); err == nil {
		t.Fatalf("expected error for invalid UUID")
	}

	validUUID := "01928C3E-7A51-7C2E-9B0F-1F2A3B4C5D6E"
	ctx = tenant.MustWithTenantID(ctx, validUUID)
	got, err := tenant.MustFromContext(ctx)
	if err != nil || got != "01928c3e-7a51-7c2e-9b0f-1f2a3b4c5d6e" {
		t.Fatalf("expected normalized UUID, got %q (err=%v)", got, err)
	}

	ctx = tenant.WithActorID(ctx, "01928c3e-0000-7000-8000-000000000001")
	actor, ok := tenant.ActorFromContext(ctx)
	if !ok || actor != "01928c3e-0000-7000-8000-000000000001" {
		t.Fatalf("unexpected actor: %q", actor)
	}
}

func TestMiddlewareExtractsConfigurableClaimOrReturns403ProblemDetails(t *testing.T) {
	var capturedTenant, capturedActor string
	h := tenant.Middleware(tenant.MiddlewareConfig{
		ClaimName: "custom_tenant_id",
	})(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedTenant, _ = tenant.FromContext(r.Context())
		capturedActor, _ = tenant.ActorFromContext(r.Context())
		w.WriteHeader(http.StatusOK)
	}))

	// 1. Missing Authorization header -> 403 Problem Details
	req1 := httptest.NewRequest(http.MethodGet, "/api/v1/ledger/entries", nil)
	rec1 := httptest.NewRecorder()
	h.ServeHTTP(rec1, req1)
	if rec1.Code != http.StatusForbidden {
		t.Fatalf("expected 403 when missing Authorization, got %d", rec1.Code)
	}
	if ct := rec1.Header().Get("Content-Type"); ct != problem.ContentType {
		t.Fatalf("expected %s, got %s", problem.ContentType, ct)
	}
	var pd problem.ProblemDetails
	if err := json.NewDecoder(rec1.Body).Decode(&pd); err != nil {
		t.Fatalf("failed to decode ProblemDetails: %v", err)
	}
	if pd.Code != "COMMON-403-FORBIDDEN" || pd.Status != 403 {
		t.Fatalf("unexpected ProblemDetails: %+v", pd)
	}

	// 2. Malformed Bearer token -> 403 Problem Details
	req2 := httptest.NewRequest(http.MethodGet, "/api/v1/ledger/entries", nil)
	req2.Header.Set("Authorization", "Bearer malformed-token")
	rec2 := httptest.NewRecorder()
	h.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for malformed JWT, got %d", rec2.Code)
	}

	// 3. JWT missing tenant claim -> 403 Problem Details
	req3 := httptest.NewRequest(http.MethodGet, "/api/v1/ledger/entries", nil)
	req3.Header.Set("Authorization", "Bearer "+makeTestJWT(map[string]any{"sub": "user-1"}))
	rec3 := httptest.NewRecorder()
	h.ServeHTTP(rec3, req3)
	if rec3.Code != http.StatusForbidden {
		t.Fatalf("expected 403 when tenant claim is missing, got %d", rec3.Code)
	}

	// 4. JWT with invalid non-UUID tenant claim -> 403 Problem Details
	req4 := httptest.NewRequest(http.MethodGet, "/api/v1/ledger/entries", nil)
	req4.Header.Set("Authorization", "Bearer "+makeTestJWT(map[string]any{"custom_tenant_id": "bad-uuid"}))
	rec4 := httptest.NewRecorder()
	h.ServeHTTP(rec4, req4)
	if rec4.Code != http.StatusForbidden {
		t.Fatalf("expected 403 when tenant claim is not a UUID, got %d", rec4.Code)
	}

	// 5. Valid custom_tenant_id claim -> 200 OK and context populated
	validTid := "01928c3e-7a51-7c2e-9b0f-1f2a3b4c5d6e"
	req5 := httptest.NewRequest(http.MethodGet, "/api/v1/ledger/entries", nil)
	req5.Header.Set("Authorization", "Bearer "+makeTestJWT(map[string]any{
		"custom_tenant_id": validTid,
		"sub":              "01928c3e-1111-7000-8000-222222222222",
	}))
	rec5 := httptest.NewRecorder()
	h.ServeHTTP(rec5, req5)
	if rec5.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", rec5.Code)
	}
	if capturedTenant != validTid {
		t.Fatalf("expected tenant %q, got %q", validTid, capturedTenant)
	}
	if capturedActor != "01928c3e-1111-7000-8000-222222222222" {
		t.Fatalf("expected actor propagated, got %q", capturedActor)
	}

	// 6. Pre-populated JWTClaims in context (from upstream auth verifier)
	req6 := httptest.NewRequest(http.MethodGet, "/api/v1/ledger/entries", nil)
	ctx6 := tenant.WithJWTClaims(req6.Context(), map[string]any{
		"tid": validTid,
	})
	rec6 := httptest.NewRecorder()
	h.ServeHTTP(rec6, req6.WithContext(ctx6))
	if rec6.Code != http.StatusOK {
		t.Fatalf("expected 200 OK via fallback tid claim, got %d", rec6.Code)
	}
}
