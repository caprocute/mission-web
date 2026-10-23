package main

import (
	"bytes"
	"context"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/twmb/franz-go/pkg/kfake"
	"github.com/twmb/franz-go/pkg/kgo"

	"vn.viettel.okrkpi/services-go/outbox-replay/internal/connect"
	"vn.viettel.okrkpi/services-go/outbox-replay/internal/connect/connecttest"
	"vn.viettel.okrkpi/services-go/outbox-replay/internal/replay"
)

func withEnv(t *testing.T, env map[string]string) {
	t.Helper()
	old := getenv
	getenv = func(k string) string { return env[k] }
	t.Cleanup(func() { getenv = old })
}

func contractArgs() []string {
	dir := filepath.Join("..", "..", "..", "..", "contracts", "events")
	return []string{"--sources", filepath.Join(dir, "outbox-sources.yaml"), "--catalog", filepath.Join(dir, "catalog.yaml")}
}

// Không tiêm store, producer: công cụ phải đọc cấu hình môi trường và báo rõ khi thiếu,
// không còn lặng lẽ chạy trên kho bộ nhớ rỗng.
func TestCLIThieuBienMoiTruong(t *testing.T) {
	withEnv(t, map[string]string{})
	var stdout, stderr bytes.Buffer
	args := append(contractArgs(), "--schema", "task", "--tenant", connecttest.TenantA)
	if code := runCLI(context.Background(), args, &stdout, &stderr, nil, nil); code != 1 || !strings.Contains(stderr.String(), connect.EnvDatabaseURL) {
		t.Fatalf("thiếu DATABASE_URL: code=%d stderr=%s", code, stderr.String())
	}

	stderr.Reset()
	args = append(args, "--confirm", "--actor", "ops", "--reason", "thu")
	if code := runCLI(context.Background(), args, &stdout, &stderr, replay.NewInMemoryOutboxStore(), nil); code != 1 || !strings.Contains(stderr.String(), connect.EnvBrokers) {
		t.Fatalf("thiếu KAFKA_BROKERS khi --confirm: code=%d stderr=%s", code, stderr.String())
	}
}

// Đầu cuối với PostgreSQL thật (FORCE RLS) và cụm Kafka kfake: dry-run đếm đúng, --confirm gửi
// đúng ba dòng của tenant A theo thứ tự lên topic phát lại của consumer, kèm một bản ghi kiểm toán.
func TestCLIPostgresVaKafkaThat(t *testing.T) {
	dsn := connecttest.PGFixture(t)
	replayTopic := "task.task.assigned.v1.replay.svc-scoring"
	c, err := kfake.NewCluster(kfake.SeedTopics(1, replayTopic, replay.AuditTopic))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	withEnv(t, map[string]string{
		connect.EnvDatabaseURL: dsn,
		connect.EnvBrokers:     strings.Join(c.ListenAddrs(), ","),
	})

	base := append(contractArgs(), "--schema", "task", "--tenant", connecttest.TenantA, "--consumer", "svc-scoring", "--batch-size", "2")
	var stdout, stderr bytes.Buffer
	if code := runCLI(context.Background(), base, &stdout, &stderr, nil, nil); code != 0 {
		t.Fatalf("dry-run: code=%d stderr=%s", code, stderr.String())
	}
	var dry replay.Result
	if err := json.Unmarshal(stdout.Bytes(), &dry); err != nil {
		t.Fatal(err)
	}
	if !dry.DryRun || dry.TotalMatched != 3 || dry.TotalPublished != 0 || dry.RetentionPolicy != "72 hours" {
		t.Fatalf("dry-run sai: %+v", dry)
	}

	stdout.Reset()
	stderr.Reset()
	args := append(base, "--confirm", "--actor", "ops-user", "--reason", "DR-10-drill", "--rate-limit", "0")
	if code := runCLI(context.Background(), args, &stdout, &stderr, nil, nil); code != 0 {
		t.Fatalf("confirm: code=%d stderr=%s", code, stderr.String())
	}
	var res replay.Result
	if err := json.Unmarshal(stdout.Bytes(), &res); err != nil {
		t.Fatal(err)
	}
	if res.DryRun || res.TotalPublished != 3 || res.AuditEventID == "" {
		t.Fatalf("confirm sai: %+v", res)
	}

	got := consume(t, c.ListenAddrs(), replayTopic, 3)
	wantIDs := []string{"0195b000-0000-7000-8000-000000000001", "0195b000-0000-7000-8000-000000000002", "0195b000-0000-7000-8000-000000000004"}
	for i, r := range got {
		if id, _ := r.HeaderValue("ce_id"); id != wantIDs[i] {
			t.Fatalf("bản ghi %d: ce_id=%q, muốn %q", i, id, wantIDs[i])
		}
		if tn, _ := r.HeaderValue("ce_tenantid"); tn != connecttest.TenantA {
			t.Fatalf("lọt bản ghi tenant khác: %q", tn)
		}
	}
	audit := consume(t, c.ListenAddrs(), replay.AuditTopic, 1)[0]
	var ap replay.AuditPayload
	if err := json.Unmarshal(audit.Value, &ap); err != nil {
		t.Fatal(err)
	}
	if ap.Action != "PHAT_LAI_OUTBOX" || ap.PublishedCount != 3 || ap.ActorID != "ops-user" || ap.Consumer != "svc-scoring" {
		t.Fatalf("bản ghi kiểm toán sai: %+v", ap)
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
		if ctx.Err() != nil {
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
