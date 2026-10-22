package consume

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"

	"vn.viettel.okrkpi/services-go/vtplatform/inbox"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

// Tên bộ đếm vận hành của consumer.
const (
	MetricCreated   = "notify_notifications_created_total"
	MetricDuplicate = "notify_events_duplicate_total"
	MetricDLQ       = "notify_events_dlq_total"
)

// MetricNames là các bộ đếm /metrics hiển thị.
var MetricNames = []string{MetricCreated, MetricDuplicate, MetricDLQ}

// MaxAttempts là số lần thử trong tiến trình trước khi chuyển bản ghi sang thư chết (wiki trang 18).
const MaxAttempts = 4

// DLQTopic trả topic thư chết của consumer cho topic nguồn (`<topic>.dlq.<consumer>`).
func DLQTopic(topic string) string { return topic + ".dlq." + ConsumerName }

// Consumer đọc các topic của SVC-12 theo consumer group notification-service, xử lý từng bản ghi rồi
// mới xác nhận offset (at-least-once; inbox chống trùng).
type Consumer struct {
	client *kgo.Client
	proc   *Processor
	log    *slog.Logger
	// OTel nhận bộ đếm vận hành (tuỳ chọn).
	OTel *otel.Provider
}

func (c *Consumer) count(name string, n int64) {
	if c.OTel != nil && n > 0 {
		c.OTel.AddCounter(name, n)
	}
}

// NewConsumer tạo consumer franz-go với tự xác nhận offset tắt.
func NewConsumer(brokers []string, proc *Processor, log *slog.Logger, opts ...kgo.Opt) (*Consumer, error) {
	base := []kgo.Opt{
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(ConsumerName),
		kgo.ConsumeTopics(Topics...),
		kgo.DisableAutoCommit(),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.AllowAutoTopicCreation(),
	}
	cl, err := kgo.NewClient(append(base, opts...)...)
	if err != nil {
		return nil, err
	}
	if log == nil {
		log = slog.Default()
	}
	return &Consumer{client: cl, proc: proc, log: log}, nil
}

// Close đóng client.
func (c *Consumer) Close() { c.client.Close() }

// Run đọc tới khi ctx kết thúc.
func (c *Consumer) Run(ctx context.Context) error {
	for {
		if err := c.PollOnce(ctx); err != nil {
			if ctx.Err() != nil {
				return nil
			}
			c.log.Error("lỗi đọc Kafka", "err", err.Error())
			time.Sleep(time.Second)
		}
	}
}

// PollOnce đọc một lô, xử lý tuần tự từng bản ghi và xác nhận offset đã xử lý.
func (c *Consumer) PollOnce(ctx context.Context) error {
	fetches := c.client.PollFetches(ctx)
	if fetches.IsClientClosed() {
		return errors.New("consume: client đã đóng")
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	var firstErr error
	fetches.EachError(func(t string, p int32, err error) {
		if firstErr == nil {
			firstErr = err
		}
	})
	// Dừng ở bản ghi đầu tiên chưa xử lý được để không xác nhận vượt qua nó; các bản ghi bị bỏ qua được
	// đọc lại nhờ đặt lại vị trí đọc của phân vùng về offset đầu tiên chưa xử lý.
	var done []*kgo.Record
	stopped := false
	rewind := map[string]map[int32]kgo.EpochOffset{}
	fetches.EachRecord(func(r *kgo.Record) {
		if !stopped && c.handle(ctx, r) {
			done = append(done, r)
			return
		}
		stopped = true
		if rewind[r.Topic] == nil {
			rewind[r.Topic] = map[int32]kgo.EpochOffset{}
		}
		if _, ok := rewind[r.Topic][r.Partition]; !ok {
			rewind[r.Topic][r.Partition] = kgo.EpochOffset{Epoch: -1, Offset: r.Offset}
		}
	})
	if len(rewind) > 0 {
		c.client.SetOffsets(rewind)
	}
	if len(done) > 0 {
		if err := c.client.CommitRecords(ctx, done...); err != nil {
			return err
		}
	}
	return firstErr
}

// handle trả false khi ctx kết thúc giữa chừng: bản ghi không được xác nhận và sẽ đọc lại.
func (c *Consumer) handle(ctx context.Context, r *kgo.Record) bool {
	m := Message{Topic: r.Topic, Partition: r.Partition, Offset: r.Offset, Value: r.Value,
		Timestamp: r.Timestamp, Headers: map[string]string{}}
	for _, h := range r.Headers {
		m.Headers[h.Key] = string(h.Value)
	}
	var err error
	for attempt := 1; attempt <= MaxAttempts; attempt++ {
		var res Result
		res, err = c.proc.Process(ctx, m)
		if err == nil {
			c.log.Debug("đã xử lý sự kiện", "topic", r.Topic, "outcome", string(res.Outcome), "created", res.Created)
			c.count(MetricCreated, int64(res.Created))
			if res.Outcome == inbox.OutcomeDuplicateEvent {
				c.count(MetricDuplicate, 1)
			}
			return true
		}
		if errors.Is(err, ErrPermanent) || ctx.Err() != nil {
			break
		}
		time.Sleep(time.Duration(attempt*attempt) * 200 * time.Millisecond)
	}
	if ctx.Err() != nil {
		return false
	}
	c.log.Error("chuyển sự kiện sang thư chết", "topic", r.Topic, "offset", r.Offset, "err", err.Error())
	dlq := &kgo.Record{Topic: DLQTopic(r.Topic), Key: r.Key, Value: r.Value,
		Headers: append(append([]kgo.RecordHeader{}, r.Headers...), kgo.RecordHeader{Key: "dlq_reason", Value: []byte(err.Error())})}
	if perr := c.client.ProduceSync(ctx, dlq).FirstErr(); perr != nil {
		c.log.Error("không ghi được thư chết", "topic", dlq.Topic, "err", perr.Error())
		return false
	}
	c.count(MetricDLQ, 1)
	return true
}
