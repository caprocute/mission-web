package provision_test

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/testcontainers/testcontainers-go"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"

	"mission/services-go/tenant-registry/internal/provision"
	"mission/services-go/tenant-registry/internal/testdb"
)

// Kiểm thử tích hợp: PostgreSQL 17 và Keycloak 26.4 thật (Testcontainers); PowerDNS, cert-manager, mission-core,
// org-service, Prometheus là máy chủ giả theo giao thức (provisiontest).

var (
	db *testdb.DB
	kc *keycloakC
)

type keycloakC struct {
	c        testcontainers.Container
	baseURL  string
	tokenURL string
	tokens   *provision.ClientCredentials
}

func TestMain(m *testing.M) {
	ctx := context.Background()
	pgc, err := tcpg.Run(ctx, "postgres:17",
		tcpg.WithDatabase("registry"), tcpg.WithUsername("postgres"), tcpg.WithPassword(testdb.RandomSecret()),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(90*time.Second)))
	if err != nil {
		fmt.Fprintln(os.Stderr, "không khởi động được PostgreSQL:", err)
		os.Exit(1)
	}
	dsn, err := pgc.ConnectionString(ctx, "sslmode=disable")
	if err == nil {
		db, err = testdb.Bootstrap(ctx, dsn, true)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "không chuẩn bị được CSDL:", err)
		_ = pgc.Terminate(ctx)
		os.Exit(1)
	}
	kc, err = startKeycloak(ctx)
	if err != nil {
		fmt.Fprintln(os.Stderr, "không khởi động được Keycloak:", err)
		_ = pgc.Terminate(ctx)
		os.Exit(1)
	}
	code := m.Run()
	_ = kc.c.Terminate(ctx)
	_ = pgc.Terminate(ctx)
	os.Exit(code)
}

// startKeycloak chạy Keycloak 26.4 (start-dev) với client quản trị khởi tạo (bootstrap admin client) và tạo realm
// mission bật Organizations như mẫu realm DV-02-01.
func startKeycloak(ctx context.Context) (*keycloakC, error) {
	secret := testdb.RandomSecret()
	c, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{Started: true,
		ContainerRequest: testcontainers.ContainerRequest{
			Image: "quay.io/keycloak/keycloak:26.4",
			Cmd:   []string{"start-dev"},
			Env: map[string]string{"KC_BOOTSTRAP_ADMIN_CLIENT_ID": "tr-bootstrap", "KC_BOOTSTRAP_ADMIN_CLIENT_SECRET": secret,
				"JAVA_OPTS_KC_HEAP": "-XX:MaxRAMPercentage=50 -Xms256m -Xmx768m"},
			ExposedPorts: []string{"8080/tcp"},
			WaitingFor:   wait.ForHTTP("/realms/master").WithPort("8080/tcp").WithStartupTimeout(4 * time.Minute),
		}})
	if err != nil {
		return nil, err
	}
	host, err := c.Host(ctx)
	if err != nil {
		return nil, err
	}
	port, err := c.MappedPort(ctx, "8080/tcp")
	if err != nil {
		return nil, err
	}
	k := &keycloakC{c: c, baseURL: "http://" + host + ":" + port.Port()}
	k.tokenURL = k.baseURL + "/realms/master/protocol/openid-connect/token"
	k.tokens = &provision.ClientCredentials{ClientID: "tr-bootstrap", ClientSecret: secret}
	tok, err := k.tokens.Token(ctx, k.tokenURL)
	if err != nil {
		return nil, err
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, k.baseURL+"/admin/realms",
		strings.NewReader(`{"realm":"mission","enabled":true,"organizationsEnabled":true}`))
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		return nil, fmt.Errorf("tạo realm: %d %s", resp.StatusCode, b)
	}
	return k, nil
}

// kcGet đọc trực tiếp Admin API (kiểm chứng độc lập với mã đang kiểm).
func kcGet(t testing.TB, path string, q url.Values) (int, string) {
	t.Helper()
	ctx := context.Background()
	tok, err := kc.tokens.Token(ctx, kc.tokenURL)
	if err != nil {
		t.Fatal(err)
	}
	u := kc.baseURL + "/admin/realms/mission" + path
	if q != nil {
		u += "?" + q.Encode()
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

func testLog() *slog.Logger {
	if os.Getenv("TEST_LOG") != "" {
		return slog.New(slog.NewJSONHandler(os.Stderr, nil))
	}
	return slog.New(slog.NewJSONHandler(io.Discard, nil))
}
