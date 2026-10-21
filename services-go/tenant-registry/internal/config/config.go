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
		HTTPAddr:        httpAddr,
		DatabaseURL:     dbURL,
		ShutdownTimeout: dur("SHUTDOWN_TIMEOUT", 20*time.Second),
		JWKSURL:         jwksURL,
		Issuers:         list("OIDC_ISSUERS"),
		Audience:        os.Getenv("OIDC_AUDIENCE"),
		StepUpMaxAge:    dur("STEP_UP_MAX_AGE", 5*time.Minute),
		CookieDomain:    os.Getenv("ROUTING_COOKIE_DOMAIN"),
		ResolveCacheTTL: dur("RESOLVE_CACHE_TTL", 30*time.Second),
		ResolveStaleMax: dur("RESOLVE_STALE_MAX", 300*time.Second),

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
	var err error
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
