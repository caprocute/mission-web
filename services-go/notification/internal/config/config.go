// Package config nạp cấu hình môi trường cho SVC-12 notification-service.
package config

import (
	"fmt"
	"os"
	"strings"
	"time"
)

// Config chứa tham số khởi chạy SVC-12.
type Config struct {
	ServiceName         string
	CellCode            string
	HTTPAddr            string
	DatabaseURL         string
	ReadOnlyDatabaseURL string
	KafkaBrokers        []string
	MaintainInterval    time.Duration
	// CryptoLocalKey là khoá gốc base64 (≥ 32 byte) của LocalKeyring, chỉ cho dev; rỗng thì tắt API thiết bị.
	CryptoLocalKey  string
	ShutdownTimeout time.Duration
}

// Load đọc cấu hình từ biến môi trường.
func Load() (*Config, error) {
	dbURL := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	if dbURL == "" {
		return nil, fmt.Errorf("thiếu biến môi trường bắt buộc DATABASE_URL")
	}
	roURL := strings.TrimSpace(os.Getenv("READONLY_DATABASE_URL"))
	if roURL == "" {
		roURL = dbURL
	}
	var brokers []string
	for _, p := range strings.Split(os.Getenv("KAFKA_BROKERS"), ",") {
		if b := strings.TrimSpace(p); b != "" {
			brokers = append(brokers, b)
		}
	}
	maintain, err := time.ParseDuration(envOr("PARTITION_MAINTAIN_INTERVAL", "6h"))
	if err != nil || maintain <= 0 {
		return nil, fmt.Errorf("PARTITION_MAINTAIN_INTERVAL không hợp lệ")
	}
	shutdown, err := time.ParseDuration(envOr("SHUTDOWN_TIMEOUT", "15s"))
	if err != nil || shutdown <= 0 {
		return nil, fmt.Errorf("SHUTDOWN_TIMEOUT không hợp lệ")
	}
	return &Config{
		ServiceName:         "notification-service",
		CellCode:            envOr("CELL_CODE", "CELL-SHARED-01"),
		HTTPAddr:            envOr("HTTP_ADDR", ":8080"),
		DatabaseURL:         dbURL,
		ReadOnlyDatabaseURL: roURL,
		KafkaBrokers:        brokers,
		MaintainInterval:    maintain,
		CryptoLocalKey:      strings.TrimSpace(os.Getenv("CRYPTO_LOCAL_KEY")),
		ShutdownTimeout:     shutdown,
	}, nil
}

func envOr(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}
