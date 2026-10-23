// Package connect nối outbox-replay (NEN-022) với PostgreSQL và Kafka thật của cell, cấu hình qua
// biến môi trường để Job do `vtctl outbox replay` (INF-26) dựng nạp từ Secret bằng envFrom.
package connect

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/twmb/franz-go/pkg/kgo"

	"vn.viettel.okrkpi/services-go/outbox-replay/internal/replay"
	"vn.viettel.okrkpi/services-go/vtplatform/db"
)

// Tên biến môi trường. DATABASE_URL và KAFKA_BROKERS theo quy ước của các service Go khác
// (ledger-service); ba biến KAFKA_TLS_* cho mTLS tới listener Strimzi (suy luận).
const (
	EnvDatabaseURL = "DATABASE_URL"
	EnvBrokers     = "KAFKA_BROKERS"
	EnvTLSCA       = "KAFKA_TLS_CA_FILE"
	EnvTLSCert     = "KAFKA_TLS_CERT_FILE"
	EnvTLSKey      = "KAFKA_TLS_KEY_FILE"
)

// Config là thông tin nối của một lần chạy.
type Config struct {
	DatabaseURL string
	Brokers     []string
	CAFile      string
	CertFile    string
	KeyFile     string
}

// FromEnv đọc Config từ getenv (os.Getenv khi nil).
func FromEnv(getenv func(string) string) Config {
	if getenv == nil {
		getenv = os.Getenv
	}
	var brokers []string
	for _, b := range strings.Split(getenv(EnvBrokers), ",") {
		if b = strings.TrimSpace(b); b != "" {
			brokers = append(brokers, b)
		}
	}
	return Config{
		DatabaseURL: strings.TrimSpace(getenv(EnvDatabaseURL)),
		Brokers:     brokers,
		CAFile:      strings.TrimSpace(getenv(EnvTLSCA)),
		CertFile:    strings.TrimSpace(getenv(EnvTLSCert)),
		KeyFile:     strings.TrimSpace(getenv(EnvTLSKey)),
	}
}

// ---------------------------------------------------------------- PostgreSQL

// TenantQuerier chạy mỗi truy vấn trong một giao dịch chỉ đọc riêng, đặt `app.tenant_id` cục bộ
// bằng db.SetLocalTenantSQL trước khi đọc, vì `<schema>.outbox_event` bật FORCE RLS (NEN-032).
// Giao dịch kết thúc khi Rows.Close hoặc Row.Scan xong; không bao giờ đặt tenant ở mức phiên.
type TenantQuerier struct {
	pool     *pgxpool.Pool
	tenantID string
}

// OpenStore mở pool tới DATABASE_URL và trả OutboxStore đọc theo tenant đã cho.
func OpenStore(ctx context.Context, cfg Config, tenantID string) (*replay.PGOutboxStore, func(), error) {
	if cfg.DatabaseURL == "" {
		return nil, nil, fmt.Errorf("connect: chưa đặt %s", EnvDatabaseURL)
	}
	pcfg, err := pgxpool.ParseConfig(cfg.DatabaseURL)
	if err != nil {
		return nil, nil, fmt.Errorf("connect: %s không hợp lệ: %w", EnvDatabaseURL, scrub(err))
	}
	pcfg.MaxConns = 2
	pcfg.ConnConfig.RuntimeParams["application_name"] = "outbox-replay"
	pool, err := pgxpool.NewWithConfig(ctx, pcfg)
	if err != nil {
		return nil, nil, fmt.Errorf("connect: mở pool PostgreSQL: %w", scrub(err))
	}
	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		pool.Close()
		return nil, nil, fmt.Errorf("connect: nối PostgreSQL: %w", scrub(err))
	}
	store, err := replay.NewPGOutboxStore(&TenantQuerier{pool: pool, tenantID: tenantID})
	if err != nil {
		pool.Close()
		return nil, nil, err
	}
	return store, pool.Close, nil
}

func (q *TenantQuerier) begin(ctx context.Context) (pgx.Tx, error) {
	tx, err := q.pool.BeginTx(ctx, pgx.TxOptions{AccessMode: pgx.ReadOnly})
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, db.SetLocalTenantSQL, q.tenantID); err != nil {
		_ = tx.Rollback(ctx)
		return nil, fmt.Errorf("connect: đặt app.tenant_id: %w", err)
	}
	return tx, nil
}

// Query thực hiện replay.Querier.
func (q *TenantQuerier) Query(ctx context.Context, sql string, args ...any) (replay.Rows, error) {
	tx, err := q.begin(ctx)
	if err != nil {
		return nil, err
	}
	rows, err := tx.Query(ctx, sql, args...)
	if err != nil {
		_ = tx.Rollback(ctx)
		return nil, err
	}
	return &txRows{Rows: rows, tx: tx, ctx: ctx}, nil
}

// QueryRow thực hiện replay.Querier.
func (q *TenantQuerier) QueryRow(ctx context.Context, sql string, args ...any) db.Row {
	tx, err := q.begin(ctx)
	if err != nil {
		return errRow{err}
	}
	return &txRow{row: tx.QueryRow(ctx, sql, args...), tx: tx, ctx: ctx}
}

type txRows struct {
	pgx.Rows
	tx  pgx.Tx
	ctx context.Context
}

func (r *txRows) Close() {
	r.Rows.Close()
	_ = r.tx.Rollback(context.WithoutCancel(r.ctx))
}

type txRow struct {
	row pgx.Row
	tx  pgx.Tx
	ctx context.Context
}

func (r *txRow) Scan(dest ...any) error {
	defer func() { _ = r.tx.Rollback(context.WithoutCancel(r.ctx)) }()
	return r.row.Scan(dest...)
}

type errRow struct{ err error }

func (r errRow) Scan(...any) error { return r.err }

// scrub bỏ chuỗi nối (có thể chứa mật khẩu) khỏi lỗi phân tích DSN của pgx.
func scrub(err error) error {
	var pe *pgconn.ParseConfigError
	if errors.As(err, &pe) {
		return errors.New("chuỗi nối không hợp lệ")
	}
	return err
}

// ---------------------------------------------------------------------- Kafka

// KafkaProducer gửi bản ghi bằng franz-go với acks=all và producer lũy đẳng (mặc định của kgo,
// không tắt), đúng điều kiện replay.Run kiểm trước khi phát lại.
type KafkaProducer struct {
	cl *kgo.Client
}

// NewKafkaProducer dựng producer từ Config; extra dùng cho kiểm thử.
func NewKafkaProducer(cfg Config, extra ...kgo.Opt) (*KafkaProducer, error) {
	if len(cfg.Brokers) == 0 {
		return nil, fmt.Errorf("connect: chưa đặt %s", EnvBrokers)
	}
	opts := []kgo.Opt{
		kgo.SeedBrokers(cfg.Brokers...),
		kgo.ClientID("outbox-replay"),
		kgo.RequiredAcks(kgo.AllISRAcks()),
		kgo.ProducerBatchCompression(kgo.NoCompression()),
	}
	if cfg.CAFile != "" || cfg.CertFile != "" {
		tc, err := tlsConfig(cfg)
		if err != nil {
			return nil, err
		}
		opts = append(opts, kgo.DialTLSConfig(tc))
	}
	cl, err := kgo.NewClient(append(opts, extra...)...)
	if err != nil {
		return nil, fmt.Errorf("connect: dựng Kafka client: %w", err)
	}
	return &KafkaProducer{cl: cl}, nil
}

func tlsConfig(cfg Config) (*tls.Config, error) {
	tc := &tls.Config{MinVersion: tls.VersionTLS12}
	if cfg.CAFile != "" {
		pem, err := os.ReadFile(cfg.CAFile)
		if err != nil {
			return nil, fmt.Errorf("connect: đọc %s: %w", EnvTLSCA, err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pem) {
			return nil, fmt.Errorf("connect: %s không chứa chứng thư PEM", EnvTLSCA)
		}
		tc.RootCAs = pool
	}
	if cfg.CertFile != "" || cfg.KeyFile != "" {
		cert, err := tls.LoadX509KeyPair(cfg.CertFile, cfg.KeyFile)
		if err != nil {
			return nil, fmt.Errorf("connect: nạp chứng thư khách Kafka: %w", err)
		}
		tc.Certificates = []tls.Certificate{cert}
	}
	return tc, nil
}

// Config thực hiện replay.Producer.
func (p *KafkaProducer) Config() replay.ProducerConfig {
	return replay.ProducerConfig{EnableIdempotence: true, Acks: "all"}
}

// Publish gửi đồng bộ một bản ghi, giữ thứ tự theo khoá.
func (p *KafkaProducer) Publish(ctx context.Context, rec replay.KafkaRecord) error {
	kr := &kgo.Record{Topic: rec.Topic, Key: rec.Key, Value: rec.Value}
	for _, h := range rec.Headers {
		kr.Headers = append(kr.Headers, kgo.RecordHeader{Key: h.Key, Value: h.Value})
	}
	return p.cl.ProduceSync(ctx, kr).FirstErr()
}

// Close xả bản ghi còn đệm rồi đóng client.
func (p *KafkaProducer) Close() {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = p.cl.Flush(ctx)
	p.cl.Close()
}
