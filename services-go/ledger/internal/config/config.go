// Package config nạp cấu hình môi trường cho SVC-09 Ledger Service.
package config

import (
	"fmt"
	"os"
	"strings"
	"time"
)

// Config chứa toàn bộ tham số khởi chạy SVC-09.
type Config struct {
	ServiceName           string
	CellCode              string
	HTTPAddr              string
	DatabaseURL           string
	ReadOnlyDatabaseURL   string
	KafkaBrokers          []string
	KafkaTopic            string
	KafkaGroupID          string
	IngestBatchMax        int
	IngestFlushTimeout    time.Duration
	PartitionSyncInterval time.Duration
	ShutdownTimeout       time.Duration
}

// Load đọc cấu hình từ biến môi trường với giá trị mặc định an toàn.
func Load() (*Config, error) {
	dbURL := strings.TrimSpace(os.Getenv("DATABASE_URL"))
	if dbURL == "" {
		return nil, fmt.Errorf("thiếu biến môi trường bắt buộc DATABASE_URL")
	}
	roURL := strings.TrimSpace(os.Getenv("READONLY_DATABASE_URL"))
	if roURL == "" {
		roURL = dbURL
	}

	httpAddr := envOrDefault("HTTP_ADDR", ":8080")
	cellCode := envOrDefault("CELL_CODE", "CELL-SHARED-01")
	kafkaTopic := envOrDefault("KAFKA_LEDGER_TOPIC", "sys.ledger.append.v1")
	kafkaGroup := envOrDefault("KAFKA_GROUP_ID", "ledger-ingester")

	var brokers []string
	if raw := strings.TrimSpace(os.Getenv("KAFKA_BROKERS")); raw != "" {
		for _, p := range strings.Split(raw, ",") {
			if b := strings.TrimSpace(p); b != "" {
				brokers = append(brokers, b)
			}
		}
	}

	partInterval, err := time.ParseDuration(envOrDefault("PARTITION_SYNC_INTERVAL", "6h"))
	if err != nil || partInterval <= 0 {
		return nil, fmt.Errorf("PARTITION_SYNC_INTERVAL không hợp lệ: %w", err)
	}
	shutdownTimeout, err := time.ParseDuration(envOrDefault("SHUTDOWN_TIMEOUT", "15s"))
	if err != nil || shutdownTimeout <= 0 {
		return nil, fmt.Errorf("SHUTDOWN_TIMEOUT không hợp lệ: %w", err)
	}
	flushTimeout, err := time.ParseDuration(envOrDefault("INGEST_FLUSH_TIMEOUT", "10ms"))
	if err != nil || flushTimeout <= 0 {
		return nil, fmt.Errorf("INGEST_FLUSH_TIMEOUT không hợp lệ: %w", err)
	}

	return &Config{
		ServiceName:           "ledger",
		CellCode:              cellCode,
		HTTPAddr:              httpAddr,
		DatabaseURL:           dbURL,
		ReadOnlyDatabaseURL:   roURL,
		KafkaBrokers:          brokers,
		KafkaTopic:            kafkaTopic,
		KafkaGroupID:          kafkaGroup,
		IngestBatchMax:        200,
		IngestFlushTimeout:    flushTimeout,
		PartitionSyncInterval: partInterval,
		ShutdownTimeout:       shutdownTimeout,
	}, nil
}

func envOrDefault(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}
