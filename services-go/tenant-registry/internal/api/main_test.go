package api_test

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/api"
	"mission/services-go/tenant-registry/internal/contract"
	"mission/services-go/tenant-registry/internal/platform/auth"
	"mission/services-go/tenant-registry/internal/platform/auth/authtest"
	"mission/services-go/tenant-registry/internal/resolve"
	"mission/services-go/tenant-registry/internal/testdb"
	"mission/services-go/tenant-registry/pkg/routecookie"
)

var (
	db    *pg
	keys  *authtest.Keys
	specs *contract.Spec
)

func TestMain(m *testing.M) {
	ctx := context.Background()
	var err error
	if db, err = startPG(ctx, true); err != nil {
		fmt.Fprintln(os.Stderr, "không khởi động được PostgreSQL kiểm thử:", err)
		os.Exit(1)
	}
	keys = authtest.New()
	if specs, err = contract.Load(filepath.Join(testdb.RepoRoot(), "contracts", "openapi")); err != nil {
		fmt.Fprintln(os.Stderr, "không nạp được hợp đồng:", err)
		os.Exit(1)
	}
	code := m.Run()
	keys.Close()
	db.Terminate(ctx)
	os.Exit(code)
}

// fixture là một Server dựng trên CSDL kiểm thử.
type fixture struct {
	srv      *api.Server
	h        http.Handler
	pool     *pgxpool.Pool
	resolver *resolve.Service
	cookies  *routecookie.Signer
}

type fixtureOpt func(*api.Deps, *resolve.Options)

func newFixture(t testing.TB, dsn string, opts ...fixtureOpt) *fixture {
	t.Helper()
	ctx := context.Background()
	pool := testdb.Pool(ctx, t, dsn)
	signer, err := routecookie.NewSigner(bytes.Repeat([]byte{7}, 32))
	if err != nil {
		t.Fatal(err)
	}
	ro := resolve.Options{}
	d := api.Deps{
		Pool: pool, Log: slog.New(slog.NewJSONHandler(io.Discard, nil)),
		Verifier: auth.NewJWKSVerifier(auth.JWKSConfig{JWKSURL: keys.JWKSURL(), Issuers: []string{authtest.Issuer}}),
		Cookies:  signer,
	}
	for _, o := range opts {
		o(&d, &ro)
	}
	d.Resolver = resolve.New(&resolve.DBLoader{Pool: pool}, ro)
	s := api.New(d)
	return &fixture{srv: s, h: s.Handler(), pool: pool, resolver: d.Resolver, cookies: signer}
}

// do gửi request tới handler.
func (f *fixture) do(t testing.TB, method, target, token string, body []byte, hdr map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	var rd io.Reader
	if body != nil {
		rd = bytes.NewReader(body)
	}
	req := httptest.NewRequest(method, target, rd)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	f.h.ServeHTTP(rec, req)
	return rec
}

// conform kiểm phản hồi với hợp đồng.
func conform(t testing.TB, file, path, method string, rec *httptest.ResponseRecorder) {
	t.Helper()
	if err := specs.Validate(file, path, method, rec.Code, rec.Header().Get("Content-Type"), rec.Body.Bytes()); err != nil {
		t.Errorf("lệch hợp đồng: %v\nthân: %s", err, rec.Body.String())
	}
}

func userToken(tenant, cell string, roles ...string) string {
	return keys.Token(authtest.Claims{Subject: "0192f1a2-1111-7000-8000-000000000001", Tenant: tenant, Cell: cell, Roles: roles, ACR: "aal1", AuthTime: time.Now()})
}

func serviceToken() string {
	return keys.Token(authtest.Claims{Subject: "0192f1a2-2222-7000-8000-000000000002", Roles: []string{"service_client"}})
}

func adminToken() string { return keys.PlatformAdmin("0192f1a2-3333-7000-8000-000000000003") }

type apiDeps = api.Deps

func slogDiscard() *slog.Logger { return slog.New(slog.NewJSONHandler(io.Discard, nil)) }
