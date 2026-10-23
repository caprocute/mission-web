package ingest

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

// DLQTopic là topic thư chết của luồng ghi theo quy ước `<topic>.dlq.<consumer>` (wiki trang 18).
const DLQTopic = TopicLedgerAppend + ".dlq." + ConsumerGroupLedgerIngester

type chainKey struct {
	tenant uuid.UUID
	chain  int16
}

type kafkaPartition struct {
	topic     string
	partition int32
}

// Consumer là vòng tiêu thụ của consumer group ledger-ingester. Mỗi Kafka partition chứa trọn các
// chuỗi có khoá tenant:partition rơi vào nó, nên mỗi consumer sở hữu một tập chuỗi.
type Consumer struct {
	client *kgo.Client
	ing    *Ingester
	log    *slog.Logger
	topic  string

	mu     sync.Mutex
	paused map[kafkaPartition]map[chainKey]bool
}

// NewConsumer tạo consumer; opts bổ sung tuỳ chọn franz-go (kiểm thử).
func NewConsumer(brokers []string, topic, group string, ing *Ingester, log *slog.Logger, opts ...kgo.Opt) (*Consumer, error) {
	if log == nil {
		log = slog.Default()
	}
	if topic == "" {
		topic = TopicLedgerAppend
	}
	if group == "" {
		group = ConsumerGroupLedgerIngester
	}
	base := []kgo.Opt{
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(group),
		kgo.ConsumeTopics(topic),
		kgo.DisableAutoCommit(),
		kgo.FetchMaxWait(50 * time.Millisecond),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.BlockRebalanceOnPoll(),
	}
	cl, err := kgo.NewClient(append(base, opts...)...)
	if err != nil {
		return nil, fmt.Errorf("ingest: tạo client Kafka: %w", err)
	}
	return &Consumer{client: cl, ing: ing, log: log, topic: topic, paused: map[kafkaPartition]map[chainKey]bool{}}, nil
}

// Close rời group và đóng kết nối.
func (c *Consumer) Close() { c.client.Close() }

type bucket struct {
	events  []EventInput
	records []*kgo.Record
}

// PollOnce đọc tối đa max bản ghi, chuyển bản ghi hỏng sang thư chết, ghi các lô theo chuỗi và chỉ xác
// nhận offset khi mọi lô đã cam kết. Có lỗi thì không xác nhận gì và tua các partition về bản ghi đầu
// của lượt đọc: lượt sau ghi lại, bản ghi đã cam kết bị bỏ qua theo event_id nên không trùng, không lỗ.
func (c *Consumer) PollOnce(ctx context.Context, max int) (int, error) {
	fetches := c.client.PollRecords(ctx, max)
	defer c.client.AllowRebalance()
	if fetches.IsClientClosed() {
		return 0, context.Canceled
	}
	for _, fe := range fetches.Errors() {
		if errors.Is(fe.Err, context.Canceled) || errors.Is(fe.Err, context.DeadlineExceeded) {
			return 0, fe.Err
		}
		c.log.Error("lỗi đọc Kafka", "topic", fe.Topic, "partition", fe.Partition, "err", fe.Err)
	}
	records := fetches.Records()
	if len(records) == 0 {
		return 0, nil
	}

	buckets := map[chainKey]*bucket{}
	var order []chainKey
	var dead []*kgo.Record
	for _, r := range records {
		ev, err := DecodeRecord(r)
		if err != nil {
			c.log.Error("bản ghi sổ cái không hợp lệ, chuyển thư chết", "partition", r.Partition, "offset", r.Offset, "err", err)
			dead = append(dead, deadLetter(r, err))
			if errors.Is(err, ErrC2CleartextInMetadata) {
				c.ing.count("ledger.ingest.rejected_c2_total", 1)
			}
			continue
		}
		k := chainKey{ev.TenantID, ev.ChainPartition()}
		b := buckets[k]
		if b == nil {
			b = &bucket{}
			buckets[k] = b
			order = append(order, k)
		}
		b.events = append(b.events, ev)
		b.records = append(b.records, r)
	}

	if len(dead) > 0 {
		if err := c.client.ProduceSync(ctx, dead...).FirstErr(); err != nil {
			c.rewind(records)
			return 0, fmt.Errorf("ingest: ghi thư chết: %w", err)
		}
	}
	total := 0
	for _, k := range order {
		b := buckets[k]
		for i := 0; i < len(b.events); i += MaxBatchSize {
			j := min(i+MaxBatchSize, len(b.events))
			res, err := c.ing.AppendBatch(ctx, k.tenant, k.chain, b.events[i:j])
			if err != nil {
				if errors.Is(err, ErrPartitionLocked) {
					c.pause(k, b.records)
				}
				c.rewind(records)
				return total, err
			}
			total += len(res.Appended)
		}
	}
	if err := c.client.CommitRecords(ctx, records...); err != nil {
		return total, fmt.Errorf("ingest: xác nhận offset sau khi ghi: %w", err)
	}
	return total, nil
}

func deadLetter(r *kgo.Record, cause error) *kgo.Record {
	h := append([]kgo.RecordHeader(nil), r.Headers...)
	h = append(h,
		kgo.RecordHeader{Key: "x-inbox-dlq-reason", Value: []byte(cause.Error())},
		kgo.RecordHeader{Key: "x-inbox-dlq-consumer", Value: []byte(ConsumerGroupLedgerIngester)},
		kgo.RecordHeader{Key: "x-inbox-dlq-original-topic", Value: []byte(r.Topic)},
		kgo.RecordHeader{Key: "x-inbox-dlq-failed-at", Value: []byte(time.Now().UTC().Format(time.RFC3339))},
	)
	return &kgo.Record{Topic: DLQTopic, Key: r.Key, Value: r.Value, Headers: h}
}

// rewind đặt lại vị trí đọc của mọi partition về bản ghi đầu của lượt đọc.
func (c *Consumer) rewind(records []*kgo.Record) {
	first := map[string]map[int32]kgo.EpochOffset{}
	for _, r := range records {
		p := first[r.Topic]
		if p == nil {
			p = map[int32]kgo.EpochOffset{}
			first[r.Topic] = p
		}
		if cur, ok := p[r.Partition]; !ok || r.Offset < cur.Offset {
			p[r.Partition] = kgo.EpochOffset{Epoch: r.LeaderEpoch, Offset: r.Offset}
		}
	}
	c.client.SetOffsets(first)
}

// pause dừng đọc các Kafka partition chứa chuỗi bị khoá ghi (BR-13-11).
func (c *Consumer) pause(k chainKey, records []*kgo.Record) {
	c.mu.Lock()
	defer c.mu.Unlock()
	topics := map[string][]int32{}
	for _, r := range records {
		kp := kafkaPartition{r.Topic, r.Partition}
		if c.paused[kp] == nil {
			c.paused[kp] = map[chainKey]bool{}
			topics[r.Topic] = append(topics[r.Topic], r.Partition)
		}
		c.paused[kp][k] = true
	}
	c.client.PauseFetchPartitions(topics)
	c.log.Error("dừng ghi chuỗi bị khoá do phá chuỗi (BR-13-11)", "tenant_id", k.tenant, "chain_partition", k.chain)
}

// resumeUnlocked đọc lại Kafka partition khi mọi chuỗi bị khoá trên đó đã mở (sau DANH_DAU_SU_CO).
func (c *Consumer) resumeUnlocked(ctx context.Context) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for kp, chains := range c.paused {
		for k := range chains {
			locked, err := c.ing.Locked(ctx, k.tenant, k.chain)
			if err == nil && !locked {
				delete(chains, k)
			}
		}
		if len(chains) == 0 {
			delete(c.paused, kp)
			c.client.ResumeFetchPartitions(map[string][]int32{kp.topic: {kp.partition}})
			c.log.Info("đọc lại partition sau khi chuỗi được mở khoá", "partition", kp.partition)
		}
	}
}

// Paused trả số Kafka partition đang dừng.
func (c *Consumer) Paused() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.paused)
}

// Run tiêu thụ tới khi ctx bị huỷ; lỗi lô được ghi nhật ký rồi thử lại sau một nhịp.
func (c *Consumer) Run(ctx context.Context) error {
	last := time.Now()
	for ctx.Err() == nil {
		if _, err := c.PollOnce(ctx, 2000); err != nil {
			if ctx.Err() != nil || errors.Is(err, context.Canceled) {
				return nil
			}
			c.log.Error("lỗi ghi lô sổ cái", "err", err)
			select {
			case <-ctx.Done():
			case <-time.After(time.Second):
			}
		}
		if time.Since(last) > 30*time.Second {
			c.resumeUnlocked(ctx)
			last = time.Now()
		}
	}
	return nil
}
