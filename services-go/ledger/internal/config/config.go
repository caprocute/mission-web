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
	// Niêm phong Merkle (DV-09-03).
	SealEnabled    bool
	SealKeyFile    string
	SealCertFile   string
	SealKeyID      string
	TSAURL         string
	SealPeriodEnds []time.Time
	SealGrace      time.Duration
	AnchorDir      string
	// Xác thực API kiểm tra toàn vẹn (Keycloak JWKS, DV-09-04); rỗng thì không mở API.
	JWKSURL      string
	Issuers      []string
	Audience     string
	VerifyWorker int
	// ForwardEnabled bật bộ chuyển tiếp topic nghiệp vụ → sys.ledger.append.v1; ForwardTopics rỗng là cả danh mục.
	ForwardEnabled bool
	ForwardTopics  []string
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

	var periodEnds []time.Time
	if raw := strings.TrimSpace(os.Getenv("SEAL_PERIOD_ENDS")); raw != "" {
		for _, p := range strings.Split(raw, ",") {
			p = strings.TrimSpace(p)
			t, err := time.Parse(time.RFC3339, p)
			if err != nil {
				d, derr := time.Parse(time.DateOnly, p)
				if derr != nil {
					return nil, fmt.Errorf("SEAL_PERIOD_ENDS không hợp lệ (%q): cần RFC 3339 hoặc YYYY-MM-DD", p)
				}
				// Ngày cuối kỳ tính hết 24 giờ giờ Việt Nam.
				t = time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, time.FixedZone("ICT", 7*3600)).AddDate(0, 0, 1)
			}
			periodEnds = append(periodEnds, t)
		}
	}
	sealGrace, err := time.ParseDuration(envOrDefault("SEAL_GRACE", "5s"))
	if err != nil || sealGrace < 0 {
		return nil, fmt.Errorf("SEAL_GRACE không hợp lệ: %w", err)
	}

	jwksURL := strings.TrimSpace(os.Getenv("OIDC_JWKS_URL"))
	if jwksURL == "" {
		if issuer := strings.TrimSpace(os.Getenv("KEYCLOAK_ISSUER_URI")); issuer != "" {
			jwksURL = strings.TrimRight(issuer, "/") + "/protocol/openid-connect/certs"
		}
	}
	var issuers []string
	for _, p := range strings.Split(os.Getenv("OIDC_ISSUERS"), ",") {
		if p = strings.TrimSpace(p); p != "" {
			issuers = append(issuers, p)
		}
	}
	workers := 8
	if v := strings.TrimSpace(os.Getenv("VERIFY_WORKERS")); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &workers); err != nil || workers < 1 || workers > 64 {
			return nil, fmt.Errorf("VERIFY_WORKERS không hợp lệ: %q", v)
		}
	}

	var fwdTopics []string
	for _, p := range strings.Split(os.Getenv("FORWARD_TOPICS"), ",") {
		if p = strings.TrimSpace(p); p != "" {
			fwdTopics = append(fwdTopics, p)
		}
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
		SealEnabled:           envOrDefault("SEAL_ENABLED", "true") == "true",
		SealKeyFile:           strings.TrimSpace(os.Getenv("SEAL_KEY_FILE")),
		SealCertFile:          strings.TrimSpace(os.Getenv("SEAL_CERT_FILE")),
		SealKeyID:             envOrDefault("SEAL_KEY_ID", "seal-"+strings.ToLower(cellCode)),
		TSAURL:                strings.TrimSpace(os.Getenv("TSA_URL")),
		SealPeriodEnds:        periodEnds,
		SealGrace:             sealGrace,
		AnchorDir:             strings.TrimSpace(os.Getenv("ANCHOR_DIR")),
		JWKSURL:               jwksURL,
		Issuers:               issuers,
		Audience:              strings.TrimSpace(os.Getenv("OIDC_AUDIENCE")),
		VerifyWorker:          workers,
		ForwardEnabled:        envOrDefault("FORWARD_ENABLED", "true") == "true",
		ForwardTopics:         fwdTopics,
	}, nil
}

func envOrDefault(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}
