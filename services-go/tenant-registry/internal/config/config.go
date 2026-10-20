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
}

// Load đọc cấu hình; thiếu biến bắt buộc trả lỗi.
func Load() (Config, error) {
	c := Config{
		HTTPAddr:        env("HTTP_ADDR", ":8080"),
		DatabaseURL:     os.Getenv("DATABASE_URL"),
		ShutdownTimeout: dur("SHUTDOWN_TIMEOUT", 20*time.Second),
		JWKSURL:         os.Getenv("OIDC_JWKS_URL"),
		Issuers:         list("OIDC_ISSUERS"),
		Audience:        os.Getenv("OIDC_AUDIENCE"),
		StepUpMaxAge:    dur("STEP_UP_MAX_AGE", 5*time.Minute),
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

