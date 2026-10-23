package forward_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"math/big"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/linkedin/goavro/v2"
	"github.com/testcontainers/testcontainers-go"
	tckafka "github.com/testcontainers/testcontainers-go/modules/kafka"
	tcpg "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kerr"
	"github.com/twmb/franz-go/pkg/kgo"

	"mission/services-go/ledger/internal/forward"
	"mission/services-go/ledger/internal/ingest"
	"mission/services-go/ledger/internal/testdb"
)

var (
	db      *testdb.DB
	brokers []string
)

// TestMain dựng PostgreSQL 17 (migration ledger thật) và Kafka KRaft cho chuỗi đầy đủ topic nghiệp vụ →
// bộ chuyển tiếp → sys.ledger.append.v1 → luồng ghi → ledger_entry.
func TestMain(m *testing.M) {
	if os.Getenv("LEDGER_SKIP_IT") != "" {
		os.Exit(m.Run())
	}
	ctx := context.Background()
	pg, err := tcpg.Run(ctx, "postgres:17",
		tcpg.WithDatabase("ledger"), tcpg.WithUsername("postgres"), tcpg.WithPassword(testdb.RandomSecret()),
		testcontainers.WithWaitStrategy(wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).WithStartupTimeout(90*time.Second)))
	if err != nil {
		fmt.Fprintln(os.Stderr, "không khởi động được PostgreSQL:", err)
		os.Exit(1)
	}
	dsn, _ := pg.ConnectionString(ctx, "sslmode=disable")
	if db, err = testdb.Bootstrap(ctx, dsn, true); err != nil {
		fmt.Fprintln(os.Stderr, "bootstrap ledger:", err)
		os.Exit(1)
	}
	kc, err := tckafka.Run(ctx, "confluentinc/confluent-local:7.5.0", tckafka.WithClusterID("forward-it"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "không khởi động được Kafka:", err)
		os.Exit(1)
	}
	brokers, _ = kc.Brokers(ctx)
	code := m.Run()
	_ = kc.Terminate(ctx)
	_ = pg.Terminate(ctx)
	os.Exit(code)
}

const topic = "task.task.assigned.v1"

// assigned dựng thân Avro EVT-04 tất định theo mã nhiệm vụ (tiêu đề tiếng Việt để băm đi qua NFC).
func assigned(t *testing.T, task uuid.UUID) []byte {
	t.Helper()
	raw, err := os.ReadFile("schemas/NhiemVuDaGiao.avsc")
	if err != nil {
		t.Fatal(err)
	}
	c, err := goavro.NewCodec(string(raw))
	if err != nil {
		t.Fatal(err)
	}
	bin, err := c.BinaryFromNative(nil, map[string]any{
		"taskId": task.String(), "planItemId": nil, "title": "Rà soát hồ sơ " + task.String()[:8],
		"leadAssigneeId": "0190a3f4-4444-7000-8000-000000000004", "assigneeIds": []any{},
		"dueDate": time.Date(2026, 12, 31, 0, 0, 0, 0, time.UTC),
		"weight":  goavro.Union("bytes.decimal", big.NewRat(1, 4)), "isKeyTask": true, "isSecret": false,
		"aggregateVersion": int32(1),
	})
	if err != nil {
		t.Fatal(err)
	}
	return bin
}

func TestForwardEndToEnd(t *testing.T) {
	if db == nil {
		t.Skip("LEDGER_SKIP_IT")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	cl, err := kgo.NewClient(kgo.SeedBrokers(brokers...))
	if err != nil {
		t.Fatal(err)
	}
	defer cl.Close()
	adm := kadm.NewClient(cl)
	for _, name := range []string{topic, ingest.TopicLedgerAppend, ingest.DLQTopic, forward.DLQTopic(topic)} {
		res, err := adm.CreateTopic(ctx, 4, 1, nil, name)
		if err == nil {
			err = res.Err
		}
		if err != nil && !errors.Is(err, kerr.TopicAlreadyExists) {
			t.Fatalf("tạo topic %s: %v", name, err)
		}
	}

	tenant, actor := uuid.New(), uuid.New()
	const n = 30
	var tasks []uuid.UUID
	recs := make([]*kgo.Record, 0, n+1)
	for i := 0; i < n; i++ {
		task := uuid.New()
		tasks = append(tasks, task)
		recs = append(recs, &kgo.Record{Topic: topic, Key: []byte(tenant.String() + ":" + task.String()),
			Value: assigned(t, task), Headers: []kgo.RecordHeader{
				{Key: "id", Value: []byte(uuid.Must(uuid.NewV7()).String())},
				{Key: "tenantid", Value: []byte(tenant.String())},
				{Key: "actorid", Value: []byte(actor.String())},
				{Key: "classification", Value: []byte("C1")},
				{Key: "occurredat", Value: []byte(time.Now().UTC().Format(time.RFC3339Nano))},
			}})
	}
	// Một bản ghi hỏng (thân Avro cụt) phải sang thư chết, không chặn các bản ghi khác.
	recs = append(recs, &kgo.Record{Topic: topic, Key: []byte(tenant.String() + ":" + uuid.NewString()),
		Value: []byte{0x02}, Headers: []kgo.RecordHeader{{Key: "id", Value: []byte(uuid.NewString())}}})
	if err := cl.ProduceSync(ctx, recs...).FirstErr(); err != nil {
		t.Fatal(err)
	}

	fw, err := forward.New(brokers, []string{topic}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer fw.Close()
	forwarded := 0
	for i := 0; i < 20 && forwarded < n; i++ {
		pctx, c := context.WithTimeout(ctx, 2*time.Second)
		got, err := fw.PollOnce(pctx, 2000)
		c()
		if err != nil && !errors.Is(err, context.DeadlineExceeded) {
			t.Fatal(err)
		}
		forwarded += got
	}
	if forwarded != n {
		t.Fatalf("chuyển tiếp %d, muốn %d", forwarded, n)
	}

	pool := testdb.Pool(ctx, t, db.SvcDSN)
	ing := ingest.NewIngester(pool, nil, nil)
	consumer, err := ingest.NewConsumer(brokers, "", "", ing, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer consumer.Close()
	written := 0
	for i := 0; i < 20 && written < n; i++ {
		pctx, c := context.WithTimeout(ctx, 2*time.Second)
		got, err := consumer.PollOnce(pctx, 2000)
		c()
		if err != nil && !errors.Is(err, context.DeadlineExceeded) {
			t.Fatal(err)
		}
		written += got
	}
	if written != n {
		t.Fatalf("luồng ghi ghi %d, muốn %d", written, n)
	}

	// payload_hash trong sổ cái phải bằng băm tính lại từ cùng thân sự kiện (cơ sở của đối soát R2).
	admin := testdb.Pool(ctx, t, db.AdminDSN)
	for i, task := range tasks[:3] {
		var stored []byte
		var typ, kind string
		var actorID uuid.UUID
		if err := admin.QueryRow(ctx, `SELECT payload_hash, event_type, actor_kind, actor_id FROM ledger.ledger_entry
			WHERE tenant_id = $1 AND aggregate_id = $2`, tenant, task).Scan(&stored, &typ, &kind, &actorID); err != nil {
			t.Fatalf("bản ghi %d: %v", i, err)
		}
		want, err := forward.PayloadHash(topic, assigned(t, task))
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(stored, want) || typ != topic || kind != "NGUOI" || actorID != actor {
			t.Fatalf("bản ghi %d lệch: hash %x/%x type %s kind %s", i, stored, want, typ, kind)
		}
	}

	dlq, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.ConsumeTopics(forward.DLQTopic(topic)),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()))
	if err != nil {
		t.Fatal(err)
	}
	defer dlq.Close()
	pctx, c := context.WithTimeout(ctx, 10*time.Second)
	defer c()
	if f := dlq.PollRecords(pctx, 10); len(f.Records()) != 1 {
		t.Fatalf("thư chết có %d bản ghi, muốn 1", len(f.Records()))
	}
}
