// Package config đọc cấu hình từ biến môi trường (BACKEND.md: cấu hình bằng biến môi trường; bí mật do
// External Secrets nạp từ OpenBao vào biến môi trường hoặc tệp, không nằm trong mã hay chart).
package config

import (
	"encoding/base64"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config là cấu hình của tenant-registry.
type Config struct {
	HTTPAddr        string
	DatabaseURL     string
	ShutdownTimeout time.Duration

	// Xác thực (Keycloak JWKS).
	JWKSURL  string
	Issuers  []string
	Audience string
	// StepUpMaxAge: độ tươi AAL2 cho thao tác có x-mission.aal: 2 (TL-01: 5 phút).
	StepUpMaxAge time.Duration
	// CellReaderClients: client dịch vụ (azp) được đọc danh sách cell (INF-20: dns-sync).
	CellReaderClients []string

	// DV-03-02: khoá HMAC cookie định tuyến (OpenBao → External Secrets → biến môi trường hoặc tệp).
	RoutingKey         []byte
	RoutingKeyPrevious []byte
	CookieDomain       string
	ResolveCacheTTL    time.Duration
	ResolveStaleMax    time.Duration

	// DV-03-03: khoá AES-256 mã hoá ứng dụng dữ liệu C2 (đầu mối quản trị tenant); thay bằng DEK do
	// OpenBao Transit bọc khi có NEN-015.
	C2Key []byte
	// Tham số BR-01-01, BR-01-02 và gợi ý mô hình (mặc định TL-02a QT-01).
	PoolMax, BridgeMax, SiloRequiredAbove int
	SiloSectors, SiloCodes                []string
	CapacityThreshold                     float64
	DomainSuffix                          string
	TenantLimit                           int
	AccountLimit                          int64
	AccountSyncInterval                   time.Duration

	// DV-03-05: saga cấp phát phần 2 và kích hoạt. Mẫu địa chỉ có chỗ {cell} (mã cell chữ thường).
	Provision ProvisionConfig
	// DV-03-07: chuyển tenant giữa các cell.
	Move MoveConfig
}

// MoveConfig là cấu hình chuyển cell. Hai tệp JSON là bí mật (External Secrets gắn tệp): kết nối quản trị sao
// chép PostgreSQL theo cell và khoá S3 của Ceph RGW theo cell. Để trống CellDBFile thì tắt (thao tác /move trả 503).
type MoveConfig struct {
	CellDBFile, ObjectsFile, LedgerURL                         string
	PollEvery, CatchUpTimeout, FreezeTimeout, Retention, Purge time.Duration
}

// ProvisionConfig là cấu hình saga cấp phát (để trống KeycloakURL thì tắt provision/activate, trả 503).
type ProvisionConfig struct {
	KeycloakURL, Realm, TokenURL, CoreURL, OrgURL string
	ClientID                                      string
	ClientSecret                                  string // tài khoản dịch vụ cấp phát (OpenBao → External Secrets)
	DNSURL, DNSZone, DNSServerID, DNSAPIKey       string
	CertAPIServer, CertNamespace, CertIssuer      string
	CertIssuerKind, CertTokenFile, CertCAFile     string
	CertWait                                      time.Duration
	PrometheusURL, LagQuery                       string
	MaxAttempts                                   int
	Backoff, StepTimeout, RPO, ReadinessInterval  time.Duration
	// DV-03-04: bước CSDL và khoá. CellDBFile là tệp JSON bí mật {"<mã cell>": {"adminUrl": "..."}} của tài khoản
	// quản trị hẹp (CREATEDB); để trống thì saga không chạy bước CSDL, khoá (chỉ hợp với dev).
	CellDBFile, BaoURL, BaoTokenFile, TransitMount         string
	MigrationImage, MigrationNamespace, MigrationSecretPfx string
	MigrationJDBCBase                                      string
	MigrationWait                                          time.Duration
}

// Load đọc cấu hình; thiếu biến bắt buộc trả lỗi.
// Hỗ trợ nạp động qua biến môi trường chuẩn (.env): PORT/TENANT_REGISTRY_PORT, DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD,
// KEYCLOAK_ISSUER_URI/KEYCLOAK_URL/KEYCLOAK_REALM, REDIS_ADDR, KAFKA_BOOTSTRAP_SERVERS, OPA_URL.
func Load() (Config, error) {
	httpAddr := os.Getenv("HTTP_ADDR")
	if httpAddr == "" {
		if p := firstNonEmptyEnv("TENANT_REGISTRY_PORT", "PORT"); p != "" {
			if strings.HasPrefix(p, ":") {
				httpAddr = p
			} else {
				httpAddr = ":" + p
			}
		} else {
			httpAddr = ":8080"
		}
	}

	dbURL := os.Getenv("DATABASE_URL")
	if dbURL == "" && os.Getenv("DB_HOST") != "" {
		dbHost := env("DB_HOST", "127.0.0.1")
		dbPort := env("DB_PORT", "35432")
		dbName := env("DB_NAME", "mission")
		dbUser := env("DB_USER", "registry")
		dbPass := os.Getenv("DB_PASSWORD")
		dbURL = fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=disable", dbUser, dbPass, dbHost, dbPort, dbName)
	}

	jwksURL := os.Getenv("OIDC_JWKS_URL")
	if jwksURL == "" {
		if issuer := os.Getenv("KEYCLOAK_ISSUER_URI"); issuer != "" {
			jwksURL = strings.TrimRight(issuer, "/") + "/protocol/openid-connect/certs"
		} else if kcURL := os.Getenv("KEYCLOAK_URL"); kcURL != "" {
			realm := env("KEYCLOAK_REALM", "mission")
			jwksURL = strings.TrimRight(kcURL, "/") + "/realms/" + realm + "/protocol/openid-connect/certs"
		}
	}

	c := Config{
		HTTPAddr:          httpAddr,
		DatabaseURL:       dbURL,
		ShutdownTimeout:   dur("SHUTDOWN_TIMEOUT", 20*time.Second),
		JWKSURL:           jwksURL,
		Issuers:           list("OIDC_ISSUERS"),
		Audience:          os.Getenv("OIDC_AUDIENCE"),
		StepUpMaxAge:      dur("STEP_UP_MAX_AGE", 5*time.Minute),
		CellReaderClients: listDef("CELL_READER_CLIENTS", []string{"dns-sync"}),
		CookieDomain:      os.Getenv("ROUTING_COOKIE_DOMAIN"),
		ResolveCacheTTL:   dur("RESOLVE_CACHE_TTL", 30*time.Second),
		ResolveStaleMax:   dur("RESOLVE_STALE_MAX", 300*time.Second),

		PoolMax:             intv("TENANT_POOL_MAX", 5000),
		BridgeMax:           intv("TENANT_BRIDGE_MAX", 1_000_000),
		SiloRequiredAbove:   intv("TENANT_SILO_REQUIRED_ABOVE", 1_000_000),
		SiloSectors:         listDef("TENANT_SILO_SECTORS", []string{"LLVT"}),
		SiloCodes:           list("TENANT_SILO_CODES"),
		CapacityThreshold:   floatv("CELL_CAPACITY_THRESHOLD", 0.85),
		DomainSuffix:        env("TENANT_DOMAIN_SUFFIX", "okr.viettel.vn"),
		TenantLimit:         intv("TENANT_LIMIT", 5000),
		AccountLimit:        int64(intv("ACCOUNT_LIMIT", 6_500_000)),
		AccountSyncInterval: dur("ACCOUNT_SYNC_INTERVAL", time.Hour),
	}
	c.Provision = ProvisionConfig{
		KeycloakURL: os.Getenv("PROVISION_KEYCLOAK_URL"), Realm: env("PROVISION_KEYCLOAK_REALM", "mission"),
		TokenURL: os.Getenv("PROVISION_TOKEN_URL"), CoreURL: os.Getenv("PROVISION_CORE_URL"), OrgURL: os.Getenv("PROVISION_ORG_URL"),
		ClientID: os.Getenv("PROVISION_CLIENT_ID"),
		DNSURL:   os.Getenv("PDNS_API_URL"), DNSZone: os.Getenv("PDNS_ZONE"), DNSServerID: env("PDNS_SERVER_ID", "localhost"),
		CertAPIServer: env("CERT_K8S_API", "https://kubernetes.default.svc"), CertNamespace: os.Getenv("CERT_NAMESPACE"),
		CertIssuer: os.Getenv("CERT_ISSUER"), CertIssuerKind: env("CERT_ISSUER_KIND", "ClusterIssuer"),
		CertTokenFile:      env("CERT_TOKEN_FILE", "/var/run/secrets/tenant-registry/k8s-token"),
		CertCAFile:         env("CERT_CA_FILE", "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"),
		CertWait:           dur("CERT_WAIT", 3*time.Minute),
		PrometheusURL:      os.Getenv("PROMETHEUS_URL"),
		LagQuery:           env("REPLICA_LAG_QUERY", `max(cnpg_pg_replication_lag{cell="{cell}"})`),
		MaxAttempts:        intv("PROVISION_MAX_ATTEMPTS", 3),
		Backoff:            dur("PROVISION_BACKOFF", 10*time.Second),
		StepTimeout:        dur("PROVISION_STEP_TIMEOUT", 10*time.Minute),
		RPO:                dur("ACTIVATION_RPO", 15*time.Minute),
		ReadinessInterval:  dur("READINESS_INTERVAL", 5*time.Minute),
		CellDBFile:         os.Getenv("PROVISION_CELL_DB_FILE"),
		BaoURL:             os.Getenv("PROVISION_OPENBAO_URL"),
		BaoTokenFile:       env("PROVISION_OPENBAO_TOKEN_FILE", "/var/run/secrets/tenant-registry/openbao-token"),
		TransitMount:       env("PROVISION_TRANSIT_MOUNT", "transit"),
		MigrationImage:     os.Getenv("PROVISION_MIGRATION_IMAGE"),
		MigrationNamespace: os.Getenv("PROVISION_MIGRATION_NAMESPACE"),
		MigrationSecretPfx: env("PROVISION_MIGRATION_SECRET_PREFIX", "td-migrate-"),
		MigrationJDBCBase:  env("PROVISION_MIGRATION_JDBC_BASE", "jdbc:postgresql://pg-biz-rw.{cell}.svc:5432"),
		MigrationWait:      dur("PROVISION_MIGRATION_WAIT", 8*time.Minute),
	}
	c.Move = MoveConfig{CellDBFile: os.Getenv("MOVE_CELL_DB_FILE"), ObjectsFile: os.Getenv("MOVE_OBJECTS_FILE"),
		LedgerURL: os.Getenv("MOVE_LEDGER_URL"), PollEvery: dur("MOVE_POLL_EVERY", time.Second),
		CatchUpTimeout: dur("MOVE_CATCHUP_TIMEOUT", 6*time.Hour), FreezeTimeout: dur("MOVE_FREEZE_TIMEOUT", 15*time.Minute),
		Retention: dur("MOVE_RETENTION", 30*24*time.Hour), Purge: dur("MOVE_PURGE_INTERVAL", 24*time.Hour)}
	var err error
	if c.Provision.ClientSecret, err = plain("PROVISION_CLIENT_SECRET"); err != nil {
		return c, err
	}
	if c.Provision.DNSAPIKey, err = plain("PDNS_API_KEY"); err != nil {
		return c, err
	}
	if c.RoutingKey, err = secret("ROUTING_COOKIE_KEY"); err != nil {
		return c, err
	}
	if c.RoutingKeyPrevious, err = secret("ROUTING_COOKIE_KEY_PREVIOUS"); err != nil {
		return c, err
	}
	if c.C2Key, err = secret("C2_KEY"); err != nil {
		return c, err
	}
	if len(c.C2Key) != 32 {
		return c, fmt.Errorf("config: C2_KEY (base64) phải đúng 32 byte")
	}
	if c.CapacityThreshold <= 0 || c.CapacityThreshold > 1 {
		return c, fmt.Errorf("config: CELL_CAPACITY_THRESHOLD phải trong (0, 1]")
	}
	if len(c.RoutingKey) < 32 {
		return c, fmt.Errorf("config: ROUTING_COOKIE_KEY (base64) phải có tối thiểu 32 byte")
	}
	if c.DatabaseURL == "" {
		return c, fmt.Errorf("config: thiếu DATABASE_URL")
	}
	if c.JWKSURL == "" {
		return c, fmt.Errorf("config: thiếu OIDC_JWKS_URL")
	}
	return c, nil
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func firstNonEmptyEnv(keys ...string) string {
	for _, k := range keys {
		if v := os.Getenv(k); v != "" {
			return v
		}
	}
	return ""
}

func dur(k string, def time.Duration) time.Duration {
	if v := os.Getenv(k); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return def
}

func intv(k string, def int) int {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func floatv(k string, def float64) float64 {
	if v := os.Getenv(k); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			return f
		}
	}
	return def
}

func listDef(k string, def []string) []string {
	if os.Getenv(k) == "" {
		return def
	}
	return list(k)
}

func list(k string) []string {
	var out []string
	for _, s := range strings.Split(os.Getenv(k), ",") {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}

// secret đọc khoá base64 từ biến môi trường K hoặc từ tệp ở K_FILE (CSI/External Secrets gắn tệp).
func secret(k string) ([]byte, error) {
	v := os.Getenv(k)
	if f := os.Getenv(k + "_FILE"); v == "" && f != "" {
		b, err := os.ReadFile(f)
		if err != nil {
			return nil, fmt.Errorf("config: đọc %s_FILE: %w", k, err)
		}
		v = strings.TrimSpace(string(b))
	}
	if v == "" {
		return nil, nil
	}
	b, err := base64.StdEncoding.DecodeString(v)
	if err != nil {
		return nil, fmt.Errorf("config: %s không phải base64", k)
	}
	return b, nil
}

// plain đọc bí mật dạng chuỗi từ biến K hoặc tệp K_FILE.
func plain(k string) (string, error) {
	v := os.Getenv(k)
	if f := os.Getenv(k + "_FILE"); v == "" && f != "" {
		b, err := os.ReadFile(f)
		if err != nil {
			return "", fmt.Errorf("config: đọc %s_FILE: %w", k, err)
		}
		v = strings.TrimSpace(string(b))
	}
	return v, nil
}
