package dlq_test

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kfake"
	"github.com/twmb/franz-go/pkg/kgo"

	tckafka "github.com/testcontainers/testcontainers-go/modules/kafka"

	"vn.viettel.okrkpi/services-go/vtctl/internal/dlq"
)

const (
	topic    = "task.task.assigned.v1"
	consumer = "scoring-service"
)

// Bootstrap trả địa chỉ Kafka cho kiểm thử: Kafka thật qua Testcontainers khi VTCTL_KAFKA_IT=1 (cần Docker),
// ngược lại cụm giả lập trong tiến trình kfake (cùng giao thức Kafka).
func Bootstrap(t *testing.T) []string {
	t.Helper()
	if os.Getenv("VTCTL_KAFKA_IT") == "1" {
		ctx := context.Background()
		kc, err := tckafka.Run(ctx, "confluentinc/confluent-local:7.5.0", tckafka.WithClusterID("vtctl-it"))
		if err != nil {
			t.Fatalf("khởi động Kafka: %v", err)
		}
		t.Cleanup(func() { _ = kc.Terminate(context.Background()) })
		b, err := kc.Brokers(ctx)
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
	c, err := kfake.NewCluster(kfake.NumBrokers(1), kfake.SeedTopics(-1))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(c.Close)
	return c.ListenAddrs()
}

func setup(t *testing.T) (*dlq.Ops, *kgo.Client) {
	t.Helper()
	ctx := context.Background()
	seeds := Bootstrap(t)
	ops, err := dlq.New(kgo.SeedBrokers(seeds...), kgo.RequiredAcks(kgo.AllISRAcks()))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(ops.Close)
	ops.IdleTimeout = 3 * time.Second
	adm := ops.Admin
	for name, parts := range map[string]int32{dlq.DLQTopic(topic, consumer): 3, dlq.ReplayTopic(topic, consumer): 6, topic + ".dlq.ledger-forwarder": 1} {
		if _, err := adm.CreateTopic(ctx, parts, 1, nil, name); err != nil {
			t.Fatal(err)
		}
	}
	p, err := kgo.NewClient(kgo.SeedBrokers(seeds...), kgo.DefaultProduceTopic(dlq.DLQTopic(topic, consumer)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.Close)
	// 10 bản ghi vào phân vùng 0, 2 bản ghi vào phân vùng 1; header lỗi theo danh mục và theo thư viện inbox.
	var recs []*kgo.Record
	for i := 0; i < 12; i++ {
		part := int32(0)
		if i >= 10 {
			part = 1
		}
		recs = append(recs, &kgo.Record{Partition: part, Key: []byte(fmt.Sprintf("tenant-a:agg-%d", i)), Value: []byte(fmt.Sprintf("avro-%d", i)),
			Headers: []kgo.RecordHeader{{Key: "ce_id", Value: []byte(fmt.Sprintf("ev-%d", i))}, {Key: "ce_type", Value: []byte(topic)},
				{Key: "apicurio.value.globalId", Value: []byte{0, 0, 0, 0, 0, 0, 0, 7}},
				{Key: "x-dlq-error-code", Value: []byte("SCORE-422")}, {Key: "x-inbox-dlq-reason", Value: []byte("lỗi")}}})
	}
	p2, err := kgo.NewClient(kgo.SeedBrokers(seeds...), kgo.RecordPartitioner(kgo.ManualPartitioner()))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p2.Close)
	for _, r := range recs {
		r.Topic = dlq.DLQTopic(topic, consumer)
	}
	if err := p2.ProduceSync(ctx, recs...).FirstErr(); err != nil {
		t.Fatal(err)
	}
	return ops, p
}

func readAll(t *testing.T, seeds []string, name string, n int) []*kgo.Record {
	t.Helper()
	c, err := kgo.NewClient(kgo.SeedBrokers(seeds...), kgo.ConsumeTopics(name), kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var out []*kgo.Record
	for len(out) < n {
		fs := c.PollFetches(ctx)
		if ctx.Err() != nil {
			break
		}
		fs.EachRecord(func(r *kgo.Record) { out = append(out, r) })
	}
	return out
}

func TestPhatLaiDungDaiVaDungTopicTrungGian(t *testing.T) {
	ops, _ := setup(t)
	ctx := context.Background()
	seeds := ops.Client.OptValue(kgo.SeedBrokers).([]string)

	qs, err := ops.List(ctx, consumer)
	if err != nil || len(qs) != 1 || qs[0].Pending != 12 || qs[0].Source != topic || qs[0].Consumer != consumer {
		t.Fatalf("List: %+v %v", qs, err)
	}

	dry, err := ops.Replay(ctx, topic, consumer, dlq.Range{Partition: 0, From: 2, To: 4}, true)
	if err != nil || dry.Count != 3 || !dry.DryRun {
		t.Fatalf("chạy thử: %+v %v", dry, err)
	}
	if got := readAll(t, seeds, dlq.ReplayTopic(topic, consumer), 1); len(got) != 0 {
		t.Fatalf("chạy thử không được ghi gì, có %d bản ghi", len(got))
	}

	res, err := ops.Replay(ctx, topic, consumer, dlq.Range{Partition: 0, From: 2, To: 4}, false)
	if err != nil || res.Count != 3 || res.First[0] != 2 || res.Last[0] != 4 {
		t.Fatalf("phát lại: %+v %v", res, err)
	}
	got := readAll(t, seeds, dlq.ReplayTopic(topic, consumer), 3)
	if len(got) != 3 {
		t.Fatalf("topic trung gian có %d bản ghi, muốn 3", len(got))
	}
	seen := map[string]bool{}
	for _, r := range got {
		seen[string(r.Value)] = true
		hs := map[string]string{}
		for _, h := range r.Headers {
			hs[h.Key] = string(h.Value)
		}
		if hs["ce_replayed"] != "true" || hs["x-dlq-error-code"] != "" || hs["x-inbox-dlq-reason"] != "" || hs["ce_type"] != topic {
			t.Fatalf("header sai: %v", hs)
		}
		if hs["apicurio.value.globalId"] != string([]byte{0, 0, 0, 0, 0, 0, 0, 7}) {
			t.Fatal("phải giữ nguyên global id lược đồ")
		}
	}
	if !seen["avro-2"] || !seen["avro-3"] || !seen["avro-4"] {
		t.Fatalf("sai dải: %v", seen)
	}
	// Topic gốc không nhận gì: phát lại chỉ qua topic trung gian của consumer.
	topics, _ := ops.Admin.ListTopics(ctx)
	if topics.Has(topic) {
		if n := readAll(t, seeds, topic, 1); len(n) != 0 {
			t.Fatal("không được ghi topic gốc")
		}
	}
	// Vị trí dlq-replayer của phân vùng 0 tới 5: còn 0..1 (trước vị trí bị bỏ qua), 5..9 và 2 bản ghi phân vùng 1.
	off, err := ops.Admin.FetchOffsetsForTopics(ctx, dlq.Group, dlq.DLQTopic(topic, consumer))
	if err != nil {
		t.Fatal(err)
	}
	if o, ok := off.Lookup(dlq.DLQTopic(topic, consumer), 0); !ok || o.At != 5 {
		t.Fatalf("vị trí phân vùng 0 = %+v", o)
	}
	qs, _ = ops.List(ctx, consumer)
	if qs[0].Pending != 7 {
		t.Fatalf("còn chưa xử lý %d, muốn 7", qs[0].Pending)
	}
	// Phát lại dải cũ hơn không lùi vị trí đã commit.
	if _, err := ops.Replay(ctx, topic, consumer, dlq.Range{Partition: 0, From: 0, To: 0}, false); err != nil {
		t.Fatal(err)
	}
	off, _ = ops.Admin.FetchOffsetsForTopics(ctx, dlq.Group, dlq.DLQTopic(topic, consumer))
	if o, _ := off.Lookup(dlq.DLQTopic(topic, consumer), 0); o.At != 5 {
		t.Fatalf("vị trí bị lùi về %d", o.At)
	}
}

func TestDaiMoiPhanVungVaGioiHan(t *testing.T) {
	ops, _ := setup(t)
	ctx := context.Background()
	ms, err := ops.Read(ctx, topic, consumer, dlq.Range{Partition: -1, From: 0, To: 100}, 0)
	if err != nil || len(ms) != 12 {
		t.Fatalf("đọc mọi phân vùng: %d %v", len(ms), err)
	}
	if ms[0].Headers["apicurio.value.globalId"] != "0000000000000007" || ms[0].ValueBytes != len("avro-0") || len(ms[0].ValueSHA256) != 64 {
		t.Fatalf("mô tả bản ghi: %+v", ms[0])
	}
	if _, err := ops.Read(ctx, topic, consumer, dlq.Range{Partition: 0, From: 5, To: 2}, 0); err == nil {
		t.Fatal("dải ngược phải bị từ chối")
	}
	if _, err := ops.Read(ctx, topic+".dlq.x", consumer, dlq.Range{Partition: 0, From: 0, To: 1}, 0); err == nil {
		t.Fatal("truyền topic .dlq. phải bị từ chối")
	}
	if _, err := ops.Read(ctx, topic, consumer, dlq.Range{Partition: 7, From: 0, To: 1}, 0); err == nil {
		t.Fatal("phân vùng không có phải báo lỗi")
	}
}

func TestBoQuaChiDayViTri(t *testing.T) {
	ops, _ := setup(t)
	ctx := context.Background()
	seeds := ops.Client.OptValue(kgo.SeedBrokers).([]string)
	if next, err := ops.Skip(ctx, topic, consumer, 1, 1, true); err != nil || next != 2 {
		t.Fatalf("chạy thử bỏ qua: %d %v", next, err)
	}
	off, _ := ops.Admin.FetchOffsetsForTopics(ctx, dlq.Group, dlq.DLQTopic(topic, consumer))
	if o, ok := off.Lookup(dlq.DLQTopic(topic, consumer), 1); ok && o.At >= 0 {
		t.Fatalf("chạy thử không được commit: %+v", o)
	}
	if _, err := ops.Skip(ctx, topic, consumer, 1, 0, false); err != nil {
		t.Fatal(err)
	}
	qs, _ := ops.List(ctx, consumer)
	if qs[0].Pending != 11 {
		t.Fatalf("còn %d, muốn 11", qs[0].Pending)
	}
	if _, err := ops.Skip(ctx, topic, consumer, 1, 5, false); err == nil {
		t.Fatal("offset ngoài DLQ phải bị từ chối")
	}
	if n := readAll(t, seeds, dlq.ReplayTopic(topic, consumer), 1); len(n) != 0 {
		t.Fatal("bỏ qua không được phát lại")
	}
}

func TestDoTreConsumer(t *testing.T) {
	ops, _ := setup(t)
	ctx := context.Background()
	// Nhóm consumer đã commit 4/10 bản ghi phân vùng 0, 0/2 phân vùng 1.
	set := kadm.Offsets{}
	set.AddOffset(dlq.DLQTopic(topic, consumer), 0, 4, -1)
	set.AddOffset(dlq.DLQTopic(topic, consumer), 1, 0, -1)
	if _, err := ops.Admin.CommitOffsets(ctx, "ops-alert", set); err != nil {
		t.Fatal(err)
	}
	ls, err := ops.Lags(ctx, "ops-alert")
	if err != nil || len(ls) != 1 || ls[0].Total != 8 {
		t.Fatalf("độ trễ: %+v %v", ls, err)
	}
}
