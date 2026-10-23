package connect

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/twmb/franz-go/pkg/kfake"
	"github.com/twmb/franz-go/pkg/kgo"

	"vn.viettel.okrkpi/services-go/outbox-replay/internal/connect/connecttest"
	"vn.viettel.okrkpi/services-go/outbox-replay/internal/replay"
)

const tenantA = connecttest.TenantA

func TestFromEnv(t *testing.T) {
	env := map[string]string{
		EnvDatabaseURL: " postgres://ro@db.invalid/td_pool ",
		EnvBrokers:     "k1.invalid:9093, ,k2.invalid:9093",
		EnvTLSCA:       "/etc/kafka/ca.crt",
	}
	cfg := FromEnv(func(k string) string { return env[k] })
	if cfg.DatabaseURL != "postgres://ro@db.invalid/td_pool" || len(cfg.Brokers) != 2 || cfg.Brokers[1] != "k2.invalid:9093" || cfg.CAFile != "/etc/kafka/ca.crt" {
		t.Fatalf("cấu hình đọc sai: %+v", cfg)
	}
}

func TestThieuCauHinhThiBaoLoi(t *testing.T) {
	if _, _, err := OpenStore(context.Background(), Config{}, tenantA); err == nil || !strings.Contains(err.Error(), EnvDatabaseURL) {
		t.Fatalf("thiếu DATABASE_URL phải báo lỗi, nhận %v", err)
	}
	if _, err := NewKafkaProducer(Config{}); err == nil || !strings.Contains(err.Error(), EnvBrokers) {
		t.Fatalf("thiếu KAFKA_BROKERS phải báo lỗi, nhận %v", err)
	}
	_, _, err := OpenStore(context.Background(), Config{DatabaseURL: "postgres://u:matkhau-bi-mat@[::1"}, tenantA)
	if err == nil || strings.Contains(err.Error(), "matkhau-bi-mat") {
		t.Fatalf("lỗi DSN không được lộ mật khẩu: %v", err)
	}
}

func TestKafkaProducerGuiDungBanGhi(t *testing.T) {
	c, err := kfake.NewCluster(kfake.SeedTopics(1, "task.task.assigned.v1.replay.svc-scoring"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	p, err := NewKafkaProducer(Config{Brokers: c.ListenAddrs()})
	if err != nil {
		t.Fatal(err)
	}
	if cfg := p.Config(); !cfg.EnableIdempotence || cfg.Acks != "all" {
		t.Fatalf("producer phải lũy đẳng và acks=all: %+v", cfg)
	}
	want := replay.KafkaRecord{
		Topic: "task.task.assigned.v1.replay.svc-scoring",
		Key:   []byte(tenantA + ":22222222-2222-4222-8222-000000000001"),
		Value: []byte{0x00, 0x01, 0xff},
		Headers: []replay.KafkaHeader{
			{Key: "ce_id", Value: []byte("0195b000-0000-7000-8000-000000000001")},
			{Key: "apicurio.value.globalId", Value: []byte("42")},
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := p.Publish(ctx, want); err != nil {
		t.Fatal(err)
	}
	p.Close()

	got := consume(t, c.ListenAddrs(), want.Topic, 1)[0]
	if !want.EqualBytes(got) {
		t.Fatalf("bản ghi nhận khác bản ghi gửi:\n gửi %+v\n nhận %+v", want, got)
	}
}

func consume(t *testing.T, brokers []string, topic string, n int) []replay.KafkaRecord {
	t.Helper()
	cl, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.ConsumeTopics(topic), kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()))
	if err != nil {
		t.Fatal(err)
	}
	defer cl.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var out []replay.KafkaRecord
	for len(out) < n {
		fs := cl.PollFetches(ctx)
		if err := ctx.Err(); err != nil {
			t.Fatalf("chỉ nhận %d/%d bản ghi từ %s", len(out), n, topic)
		}
		fs.EachRecord(func(r *kgo.Record) {
			kr := replay.KafkaRecord{Topic: r.Topic, Key: r.Key, Value: r.Value}
			for _, h := range r.Headers {
				kr.Headers = append(kr.Headers, replay.KafkaHeader{Key: h.Key, Value: h.Value})
			}
			out = append(out, kr)
		})
	}
	return out
}

func TestPGStoreDocTheoTenantVaThuTu(t *testing.T) {
	dsn := connecttest.PGFixture(t)
	ctx := context.Background()
	store, closeFn, err := OpenStore(ctx, Config{DatabaseURL: dsn}, tenantA)
	if err != nil {
		t.Fatal(err)
	}
	defer closeFn()

	ret, err := store.RetentionPolicy(ctx, "task")
	if err != nil || ret != "72 hours" {
		t.Fatalf("thời hạn giữ phải đọc từ partman.part_config: %q %v", ret, err)
	}
	f := replay.Filter{Schema: "task", TenantID: tenantA}
	got, err := store.QueryBatch(ctx, f, nil, "", 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0].ID != "0195b000-0000-7000-8000-000000000001" || got[1].ID != "0195b000-0000-7000-8000-000000000002" {
		t.Fatalf("lô đầu sai thứ tự (created_at, id): %+v", got)
	}
	r := got[0]
	if r.TenantID != tenantA || r.AggregateVersion != 1 || r.EventKey != tenantA+":22222222-2222-4222-8222-000000000001" ||
		string(r.Payload) != string([]byte{0x02, 0x01}) || r.Headers["classification"] != "C1" || !r.CreatedAt.Equal(time.Date(2026, 10, 20, 10, 0, 0, 0, time.UTC)) {
		t.Fatalf("đọc cột sai: %+v", r)
	}
	next, err := store.QueryBatch(ctx, f, &got[1].CreatedAt, got[1].ID, 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(next) != 1 || next[0].ID != "0195b000-0000-7000-8000-000000000004" {
		t.Fatalf("phân trang khoá sai hoặc lọt dòng tenant khác: %+v", next)
	}

	// Không đặt app.tenant_id thì RLS che hết: bảo đảm kiểm thử thật sự đi qua RLS.
	raw, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer raw.Close(ctx)
	var n int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM task.outbox_event`).Scan(&n); err != nil || n != 0 {
		t.Fatalf("vai trò ro_task không đặt tenant phải thấy 0 dòng, thấy %d (%v)", n, err)
	}
}
