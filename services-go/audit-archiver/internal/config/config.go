// Package config đọc cấu hình audit-archiver từ biến môi trường. Bí mật (khoá S3, token OpenBao) chỉ đọc từ tệp
// do External Secrets hay CSI gắn vào, hoặc từ biến môi trường ở dev; không bao giờ ghi ra nhật ký.
package config

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"

	"mission/services-go/audit-archiver/internal/objstore"
	"mission/services-go/audit-archiver/internal/transit"
)

// Config là cấu hình đầy đủ.
type Config struct {
	Cell           string
	Brokers        []string
	KafkaTLSDir    string
	Group          string
	Topics         []string
	Window         time.Duration
	MaxRecords     int
	MaxBytes       int
	Store          objstore.Config
	Transit        *transit.Client
	MgmtAddr       string
	PushgatewayURL string
}

func env(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}

// secret đọc giá trị từ <K>_FILE nếu có, nếu không từ <K>.
func secret(k string) (string, error) {
	if f := strings.TrimSpace(os.Getenv(k + "_FILE")); f != "" {
		b, err := os.ReadFile(f)
		if err != nil {
			return "", fmt.Errorf("config: đọc %s_FILE: %w", k, err)
		}
		return strings.TrimSpace(string(b)), nil
	}
	return strings.TrimSpace(os.Getenv(k)), nil
}

func list(k, def string) []string {
	var out []string
	for _, p := range strings.Split(env(k, def), ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// Load đọc cấu hình. needKafka, needSigner cho phép công cụ chỉ đọc bỏ qua phần không dùng.
func Load(needKafka, needSigner bool) (*Config, error) {
	c := &Config{
		Cell:           env("CELL", ""),
		Brokers:        list("KAFKA_BROKERS", ""),
		KafkaTLSDir:    env("KAFKA_TLS_DIR", ""),
		Group:          env("KAFKA_GROUP", "audit-archiver"),
		Topics:         list("TOPICS", "sys.audit.v1"),
		MgmtAddr:       env("MGMT_ADDR", ":8081"),
		PushgatewayURL: env("PUSHGATEWAY_URL", ""),
	}
	if c.Cell == "" {
		return nil, errors.New("config: thiếu CELL")
	}
	var err error
	if c.Window, err = time.ParseDuration(env("BATCH_WINDOW", "4m")); err != nil || c.Window <= 0 {
		return nil, errors.New("config: BATCH_WINDOW không hợp lệ")
	}
	if c.MaxRecords, err = strconv.Atoi(env("BATCH_MAX_RECORDS", "200000")); err != nil || c.MaxRecords <= 0 {
		return nil, errors.New("config: BATCH_MAX_RECORDS không hợp lệ")
	}
	if c.MaxBytes, err = strconv.Atoi(env("BATCH_MAX_BYTES", strconv.Itoa(64<<20))); err != nil || c.MaxBytes <= 0 {
		return nil, errors.New("config: BATCH_MAX_BYTES không hợp lệ")
	}
	years, err := strconv.Atoi(env("LOCK_RETENTION_YEARS", "10"))
	if err != nil || years <= 0 {
		return nil, errors.New("config: LOCK_RETENTION_YEARS không hợp lệ")
	}
	// Hạn giữ tính theo năm 365,25 ngày để mười năm luôn phủ đủ ngày nhuận.
	ret := time.Duration(float64(years) * 365.25 * 24 * float64(time.Hour))
	if d := env("LOCK_RETENTION", ""); d != "" {
		// Chỉ dùng ở kiểm thử (hạn giữ ngắn); production để trống.
		if ret, err = time.ParseDuration(d); err != nil || ret <= 0 {
			return nil, errors.New("config: LOCK_RETENTION không hợp lệ")
		}
	}
	ak, err := secret("S3_ACCESS_KEY")
	if err != nil {
		return nil, err
	}
	sk, err := secret("S3_SECRET_KEY")
	if err != nil {
		return nil, err
	}
	c.Store = objstore.Config{
		Endpoint:  env("S3_ENDPOINT", ""),
		Secure:    env("S3_TLS", "true") == "true",
		Region:    env("S3_REGION", ""),
		Bucket:    env("S3_BUCKET", ""),
		AccessKey: ak,
		SecretKey: sk,
		Mode:      env("LOCK_MODE", "COMPLIANCE"),
		Retention: ret,
	}
	if c.Store.Endpoint == "" || c.Store.Bucket == "" || ak == "" || sk == "" {
		return nil, errors.New("config: thiếu S3_ENDPOINT, S3_BUCKET hoặc khoá S3")
	}
	if needKafka && len(c.Brokers) == 0 {
		return nil, errors.New("config: thiếu KAFKA_BROKERS")
	}
	if needSigner {
		tok, err := secret("BAO_TOKEN")
		if err != nil {
			return nil, err
		}
		c.Transit = &transit.Client{
			Addr:      env("BAO_ADDR", ""),
			Mount:     env("BAO_TRANSIT_MOUNT", "transit"),
			Key:       env("BAO_SIGN_KEY", "audit-archiver"),
			TokenFile: env("BAO_TOKEN_PATH", ""),
			Token:     tok,
			K8sRole:   env("BAO_K8S_ROLE", ""),
			K8sMount:  env("BAO_K8S_MOUNT", "kubernetes"),
			K8sJWT:    env("BAO_K8S_JWT_PATH", ""),
		}
		if c.Transit.Addr == "" {
			return nil, errors.New("config: thiếu BAO_ADDR")
		}
	}
	return c, nil
}

// KafkaOpts là tuỳ chọn kết nối Kafka: mTLS bằng chứng chỉ KafkaUser của Strimzi (ca.crt, user.crt, user.key).
func (c *Config) KafkaOpts() ([]kgo.Opt, error) {
	if c.KafkaTLSDir == "" {
		return nil, nil
	}
	ca, err := os.ReadFile(filepath.Join(c.KafkaTLSDir, "ca.crt"))
	if err != nil {
		return nil, fmt.Errorf("config: đọc ca.crt: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(ca) {
		return nil, errors.New("config: ca.crt hỏng")
	}
	cert, err := tls.LoadX509KeyPair(filepath.Join(c.KafkaTLSDir, "user.crt"), filepath.Join(c.KafkaTLSDir, "user.key"))
	if err != nil {
		return nil, fmt.Errorf("config: đọc chứng chỉ Kafka: %w", err)
	}
	return []kgo.Opt{kgo.DialTLSConfig(&tls.Config{MinVersion: tls.VersionTLS12, RootCAs: pool, Certificates: []tls.Certificate{cert}})}, nil
}
