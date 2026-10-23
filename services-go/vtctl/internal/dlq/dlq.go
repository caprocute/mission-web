// Package dlq thao tác hàng thư chết theo OPS-24: mỗi consumer có `<topic>.dlq.<consumer>`; phát lại là đọc một
// dải offset của DLQ và ghi nguyên khoá, thân Avro và header CloudEvents sang topic trung gian
// `<topic>.replay.<consumer>` (bỏ header lỗi, thêm `ce_replayed=true`), chỉ consumer đó đọc lại. Vị trí đã xử lý
// của từng DLQ là offset đã commit của nhóm `dlq-replayer` (danh mục topic: consumer của DLQ là dlq-replayer,
// ops-alert); bỏ qua có duyệt chỉ đẩy vị trí này qua bản ghi, không xoá gì (suy luận).
package dlq

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kerr"
	"github.com/twmb/franz-go/pkg/kgo"
)

// Group là nhóm consumer giữ vị trí đã xử lý của DLQ.
const Group = "dlq-replayer"

var (
	topicRe    = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,200}$`)
	consumerRe = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,62}$`)
	// Header lỗi do consumer gắn khi chuyển DLQ (danh mục: x-dlq-*; thư viện inbox: x-inbox-dlq-*).
	errorHeader = regexp.MustCompile(`^x-(inbox-)?dlq-`)
)

// DLQTopic, ReplayTopic theo quy ước TL-01, OPS-24.
func DLQTopic(topic, consumer string) string    { return topic + ".dlq." + consumer }
func ReplayTopic(topic, consumer string) string { return topic + ".replay." + consumer }

// Validate kiểm tên topic gốc và consumer.
func Validate(topic, consumer string) error {
	if !topicRe.MatchString(topic) || strings.Contains(topic, ".dlq.") || strings.Contains(topic, ".replay.") {
		return fmt.Errorf("topic gốc %q không hợp lệ (truyền topic gốc, không phải topic .dlq./.replay.)", topic)
	}
	if !consumerRe.MatchString(consumer) {
		return fmt.Errorf("consumer %q không hợp lệ", consumer)
	}
	return nil
}

// Ops thao tác Kafka của một cell.
type Ops struct {
	Client *kgo.Client
	Admin  *kadm.Client
	// IdleTimeout là thời gian chờ tối đa không có bản ghi mới khi đọc dải (mặc định 10 giây).
	IdleTimeout time.Duration
}

func (o *Ops) idle() time.Duration {
	if o.IdleTimeout > 0 {
		return o.IdleTimeout
	}
	return 10 * time.Second
}

// New tạo Ops từ tuỳ chọn kgo (địa chỉ, mTLS).
func New(opts ...kgo.Opt) (*Ops, error) {
	c, err := kgo.NewClient(opts...)
	if err != nil {
		return nil, err
	}
	return &Ops{Client: c, Admin: kadm.NewClient(c)}, nil
}

// Close đóng kết nối.
func (o *Ops) Close() { o.Client.Close() }

// Partition là trạng thái một phân vùng DLQ.
type Partition struct {
	Partition int32 `json:"partition"`
	Start     int64 `json:"start"`
	End       int64 `json:"end"`
	Committed int64 `json:"committed"`
	Pending   int64 `json:"pending"`
}

// Queue là một hàng thư chết.
type Queue struct {
	Topic      string      `json:"topic"`
	Source     string      `json:"sourceTopic"`
	Consumer   string      `json:"consumer"`
	Pending    int64       `json:"pending"`
	Partitions []Partition `json:"partitions"`
}

// List liệt kê mọi topic `.dlq.` cùng số bản ghi chưa xử lý (sau offset đã commit của dlq-replayer).
func (o *Ops) List(ctx context.Context, filter string) ([]Queue, error) {
	topics, err := o.Admin.ListTopics(ctx)
	if err != nil {
		return nil, fmt.Errorf("liệt kê topic: %w", err)
	}
	var names []string
	for _, t := range topics.Sorted() {
		if i := strings.Index(t.Topic, ".dlq."); i > 0 && (filter == "" || strings.Contains(t.Topic, filter)) {
			names = append(names, t.Topic)
		}
	}
	if len(names) == 0 {
		return nil, nil
	}
	starts, err := o.Admin.ListStartOffsets(ctx, names...)
	if err != nil {
		return nil, err
	}
	ends, err := o.Admin.ListEndOffsets(ctx, names...)
	if err != nil {
		return nil, err
	}
	committed, err := o.committed(ctx, names...)
	if err != nil {
		return nil, fmt.Errorf("đọc vị trí nhóm %s: %w", Group, err)
	}
	var out []Queue
	for _, n := range names {
		i := strings.Index(n, ".dlq.")
		q := Queue{Topic: n, Source: n[:i], Consumer: n[i+len(".dlq."):]}
		for p, e := range ends[n] {
			s := starts[n][p]
			pp := Partition{Partition: p, Start: s.Offset, End: e.Offset, Committed: -1}
			from := s.Offset
			if c, ok := committed.Lookup(n, p); ok && c.At >= 0 {
				pp.Committed = c.At
				from = max(from, c.At)
			}
			pp.Pending = max(e.Offset-from, 0)
			q.Pending += pp.Pending
			q.Partitions = append(q.Partitions, pp)
		}
		sort.Slice(q.Partitions, func(a, b int) bool { return q.Partitions[a].Partition < q.Partitions[b].Partition })
		out = append(out, q)
	}
	return out, nil
}

// Message là một bản ghi DLQ đã đọc, chỉ phần mô tả: không in thân (có thể là dữ liệu C1) ra màn hình.
type Message struct {
	Partition   int32             `json:"partition"`
	Offset      int64             `json:"offset"`
	Key         string            `json:"key"`
	ValueBytes  int               `json:"valueBytes"`
	ValueSHA256 string            `json:"valueSha256"`
	Timestamp   time.Time         `json:"timestamp"`
	Headers     map[string]string `json:"headers"`

	record *kgo.Record
}

// Range là dải offset bao gồm cả hai đầu trên một phân vùng hoặc mọi phân vùng (Partition < 0).
type Range struct {
	Partition int32
	From, To  int64
}

func (r Range) check() error {
	if r.From < 0 || r.To < r.From {
		return fmt.Errorf("dải offset %d..%d không hợp lệ", r.From, r.To)
	}
	if r.To-r.From > 1_000_000 {
		return errors.New("dải offset quá 1.000.000 bản ghi; chia nhỏ để dễ kiểm và quay lui")
	}
	return nil
}

// Read đọc các bản ghi DLQ trong dải. Không commit gì.
func (o *Ops) Read(ctx context.Context, topic, consumer string, r Range, limit int) ([]Message, error) {
	if err := Validate(topic, consumer); err != nil {
		return nil, err
	}
	if err := r.check(); err != nil {
		return nil, err
	}
	dlq := DLQTopic(topic, consumer)
	ends, err := o.Admin.ListEndOffsets(ctx, dlq)
	if err != nil {
		return nil, err
	}
	if ends.Error() != nil {
		return nil, fmt.Errorf("topic %s: %w", dlq, ends.Error())
	}
	parts := map[int32]kgo.Offset{}
	stop := map[int32]int64{}
	for p, e := range ends[dlq] {
		if r.Partition >= 0 && p != r.Partition {
			continue
		}
		last := min(r.To, e.Offset-1)
		if last < r.From {
			continue
		}
		parts[p] = kgo.NewOffset().At(r.From)
		stop[p] = last
	}
	if r.Partition >= 0 && len(ends[dlq]) > 0 {
		if _, ok := ends[dlq][r.Partition]; !ok {
			return nil, fmt.Errorf("topic %s không có phân vùng %d", dlq, r.Partition)
		}
	}
	if len(parts) == 0 {
		return nil, nil
	}
	cons, err := kgo.NewClient(append(o.Client.Opts(),
		kgo.ConsumePartitions(map[string]map[int32]kgo.Offset{dlq: parts}),
		kgo.FetchIsolationLevel(kgo.ReadCommitted()))...)
	if err != nil {
		return nil, err
	}
	defer cons.Close()
	var out []Message
	done := map[int32]bool{}
	for len(done) < len(parts) {
		// Bản ghi cuối dải có thể là bản ghi điều khiển giao dịch (không trả về): chờ quá IdleTimeout không có bản ghi
		// mới thì coi như hết dải.
		pctx, cancel := context.WithTimeout(ctx, o.idle())
		fs := cons.PollFetches(pctx)
		cancel()
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if pctx.Err() != nil && fs.NumRecords() == 0 {
			break
		}
		for _, fe := range fs.Errors() {
			if errors.Is(fe.Err, context.DeadlineExceeded) {
				continue
			}
			return nil, fmt.Errorf("đọc %s/%d: %w", fe.Topic, fe.Partition, fe.Err)
		}
		fs.EachRecord(func(rec *kgo.Record) {
			if done[rec.Partition] || rec.Offset > stop[rec.Partition] {
				done[rec.Partition] = true
				return
			}
			sum := sha256.Sum256(rec.Value)
			m := Message{Partition: rec.Partition, Offset: rec.Offset, Key: string(rec.Key), ValueBytes: len(rec.Value),
				ValueSHA256: hex.EncodeToString(sum[:]), Timestamp: rec.Timestamp, Headers: map[string]string{}, record: rec}
			for _, h := range rec.Headers {
				if h.Key == "apicurio.value.globalId" {
					m.Headers[h.Key] = hex.EncodeToString(h.Value)
					continue
				}
				m.Headers[h.Key] = string(h.Value)
			}
			if limit <= 0 || len(out) < limit {
				out = append(out, m)
			}
			if rec.Offset == stop[rec.Partition] {
				done[rec.Partition] = true
			}
		})
		if limit > 0 && len(out) >= limit {
			break
		}
	}
	sort.Slice(out, func(a, b int) bool {
		if out[a].Partition != out[b].Partition {
			return out[a].Partition < out[b].Partition
		}
		return out[a].Offset < out[b].Offset
	})
	return out, nil
}

// ReplayResult là kết quả một lần phát lại.
type ReplayResult struct {
	DLQTopic    string          `json:"dlqTopic"`
	ReplayTopic string          `json:"replayTopic"`
	Count       int             `json:"count"`
	First, Last map[int32]int64 `json:"-"`
	DryRun      bool            `json:"dryRun"`
}

// Replay phát lại dải sang `<topic>.replay.<consumer>`; dry=true chỉ đếm. Sau khi Kafka xác nhận hết mới commit vị
// trí dlq-replayer tới bản ghi cuối + 1 trên từng phân vùng (không lùi vị trí đã commit cao hơn).
func (o *Ops) Replay(ctx context.Context, topic, consumer string, r Range, dry bool) (ReplayResult, error) {
	msgs, err := o.Read(ctx, topic, consumer, r, 0)
	if err != nil {
		return ReplayResult{}, err
	}
	res := ReplayResult{DLQTopic: DLQTopic(topic, consumer), ReplayTopic: ReplayTopic(topic, consumer), Count: len(msgs),
		DryRun: dry, First: map[int32]int64{}, Last: map[int32]int64{}}
	for _, m := range msgs {
		if _, ok := res.First[m.Partition]; !ok {
			res.First[m.Partition] = m.Offset
		}
		res.Last[m.Partition] = m.Offset
	}
	if dry || len(msgs) == 0 {
		return res, nil
	}
	exists, err := o.Admin.ListTopics(ctx, res.ReplayTopic)
	if err != nil {
		return res, err
	}
	if !exists.Has(res.ReplayTopic) {
		return res, fmt.Errorf("topic %s chưa có; topic trung gian do KafkaTopic của consumer khai báo (danh mục topic)", res.ReplayTopic)
	}
	recs := make([]*kgo.Record, 0, len(msgs))
	for _, m := range msgs {
		hs := make([]kgo.RecordHeader, 0, len(m.record.Headers)+1)
		for _, h := range m.record.Headers {
			if errorHeader.MatchString(h.Key) || h.Key == "ce_replayed" {
				continue
			}
			hs = append(hs, h)
		}
		hs = append(hs, kgo.RecordHeader{Key: "ce_replayed", Value: []byte("true")})
		recs = append(recs, &kgo.Record{Topic: res.ReplayTopic, Key: m.record.Key, Value: m.record.Value, Headers: hs})
	}
	if err := o.Client.ProduceSync(ctx, recs...).FirstErr(); err != nil {
		return res, fmt.Errorf("ghi %s: %w", res.ReplayTopic, err)
	}
	if err := o.advance(ctx, res.DLQTopic, res.Last); err != nil {
		return res, fmt.Errorf("đã phát lại %d bản ghi nhưng chưa ghi được vị trí %s: %w", len(msgs), Group, err)
	}
	return res, nil
}

// Skip đánh dấu đã xử lý (bỏ qua có duyệt) đến hết offset `to` trên một phân vùng; không ghi topic nào.
func (o *Ops) Skip(ctx context.Context, topic, consumer string, partition int32, to int64, dry bool) (int64, error) {
	if err := Validate(topic, consumer); err != nil {
		return 0, err
	}
	dlq := DLQTopic(topic, consumer)
	ends, err := o.Admin.ListEndOffsets(ctx, dlq)
	if err != nil {
		return 0, err
	}
	e, ok := ends.Lookup(dlq, partition)
	if !ok || e.Err != nil {
		return 0, fmt.Errorf("topic %s không có phân vùng %d", dlq, partition)
	}
	if to < 0 || to >= e.Offset {
		return 0, fmt.Errorf("offset %d ngoài DLQ (cuối hiện tại %d)", to, e.Offset-1)
	}
	if dry {
		return to + 1, nil
	}
	return to + 1, o.advance(ctx, dlq, map[int32]int64{partition: to})
}

// committed đọc vị trí của nhóm dlq-replayer; nhóm chưa từng commit thì coi như chưa xử lý gì.
func (o *Ops) committed(ctx context.Context, topics ...string) (kadm.OffsetResponses, error) {
	r, err := o.Admin.FetchOffsetsForTopics(ctx, Group, topics...)
	if errors.Is(err, kerr.GroupIDNotFound) {
		return kadm.OffsetResponses{}, nil
	}
	return r, err
}

func (o *Ops) advance(ctx context.Context, dlq string, last map[int32]int64) error {
	cur, err := o.committed(ctx, dlq)
	if err != nil {
		return err
	}
	set := kadm.Offsets{}
	for p, off := range last {
		if c, ok := cur.Lookup(dlq, p); ok && c.At > off+1 {
			continue
		}
		set.AddOffset(dlq, p, off+1, -1)
	}
	if len(set) == 0 {
		return nil
	}
	resp, err := o.Admin.CommitOffsets(ctx, Group, set)
	if err != nil {
		return err
	}
	return resp.Error()
}

// Lag là độ trễ của một nhóm consumer.
type Lag struct {
	Group  string           `json:"group"`
	State  string           `json:"state"`
	Total  int64            `json:"total"`
	Topics map[string]int64 `json:"topics"`
}

// Lags trả độ trễ của các nhóm (rỗng là mọi nhóm).
func (o *Ops) Lags(ctx context.Context, groups ...string) ([]Lag, error) {
	if len(groups) == 0 {
		ls, err := o.Admin.ListGroups(ctx)
		if err != nil {
			return nil, err
		}
		groups = ls.Groups()
	}
	if len(groups) == 0 {
		return nil, nil
	}
	lags, err := o.Admin.Lag(ctx, groups...)
	if err != nil {
		return nil, err
	}
	var out []Lag
	for _, g := range lags.Sorted() {
		if g.Error() != nil {
			return nil, fmt.Errorf("nhóm %s: %w", g.Group, g.Error())
		}
		l := Lag{Group: g.Group, State: g.State, Total: g.Lag.Total(), Topics: map[string]int64{}}
		for t, ps := range g.Lag {
			var n int64
			for _, p := range ps {
				n += max(p.Lag, 0)
			}
			l.Topics[t] = n
		}
		out = append(out, l)
	}
	return out, nil
}
