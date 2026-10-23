// Package forward hiện thực bộ chuyển tiếp của ledger-ingester (TL-03 chương 8 "Hình 8.1"): consumer
// nhóm ledger-forwarder đọc các topic nghiệp vụ có giá trị pháp lý (EVT-01..05, 07..09, 11..13, 18, 22,
// 25) và sys.audit.v1, tính payload_hash và chuyển tiếp thành bản ghi LedgerAppend trên
// sys.ledger.append.v1 với khoá tenant:partition để luồng ghi nối chuỗi băm theo thứ tự.
//
// payload_hash = SHA-256(JCS + NFC của bản mã hoá JSON Avro chuẩn của tải trọng sự kiện) (suy luận: TL-03
// chỉ ghi "payload sự kiện ở dạng chuẩn hoá JCS"; tải trọng trên topic là Avro nhị phân, nên chọn bản mã
// hoá JSON theo đặc tả Avro để module nguồn tính lại được bằng JsonEncoder khi đối soát R2).
package forward

import (
	"bytes"
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/linkedin/goavro/v2"
	"github.com/twmb/franz-go/pkg/kgo"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/ingest"
)

// ConsumerGroup là consumer group của bộ chuyển tiếp (suy luận, tách khỏi nhóm ghi ledger-ingester để
// offset hai chặng độc lập).
const ConsumerGroup = "ledger-forwarder"

//go:embed schemas/*.avsc
var schemaFS embed.FS

// Topics ánh xạ topic nguồn với tệp lược đồ Avro (bản chép từ contracts/events/avro, kiểm thử giữ khớp).
var Topics = map[string]string{
	"okr.objective.approved.v1":          "MucTieuDaDuyet.avsc",
	"okr.keyresult.checkedin.v1":         "KrDaCheckIn.avsc",
	"task.plan.approved.v1":              "KeHoachDaDuyet.avsc",
	"task.task.assigned.v1":              "NhiemVuDaGiao.avsc",
	"task.task.progressed.v1":            "TienDoDaCapNhat.avsc",
	"product.evidence.submitted.v1":      "MinhChungDaNop.avsc",
	"product.evidence.accepted.v1":       "MinhChungDaXacNhan.avsc",
	"config.period.locked.v1":            "KyDaKhoa.avsc",
	"appraisal.profile.stepcompleted.v1": "HoSoDanhGiaChuyenBuoc.avsc",
	"appraisal.rating.decided.v1":        "XepLoaiDaQuyetDinh.avsc",
	"policy.pack.published.v1":           "PolicyPackDaPhatHanh.avsc",
	"esign.signature.completed.v1":       "KySoDaHoanTat.avsc",
	"oversight.period.reopened.v1":       "KyDaMoLaiHauKiem.avsc",
	"oversight.complaint.filed.v1":       "KhieuNaiDaGui.avsc",
	"sys.audit.v1":                       "AuditEvent.avsc",
}

var codecs = func() map[string]*goavro.Codec {
	m := make(map[string]*goavro.Codec, len(Topics))
	for topic, file := range Topics {
		raw, err := schemaFS.ReadFile("schemas/" + file)
		if err != nil {
			panic(fmt.Sprintf("forward: thiếu lược đồ %s: %v", file, err))
		}
		c, err := goavro.NewCodec(string(raw))
		if err != nil {
			panic(fmt.Sprintf("forward: lược đồ %s: %v", file, err))
		}
		m[topic] = c
	}
	return m
}()

// ErrInvalid là lỗi vĩnh viễn của một bản ghi nguồn; bản ghi chuyển thư chết.
var ErrInvalid = errors.New("forward: bản ghi nguồn không chuyển tiếp được")

// PayloadHash giải Avro nhị phân của topic, mã hoá JSON Avro, chuẩn hoá JCS + NFC rồi băm SHA-256.
// Module nguồn dùng đúng công thức này để tính lại băm từ dữ liệu hiện tại (đối soát R2).
func PayloadHash(topic string, value []byte) ([]byte, error) {
	codec := codecs[topic]
	if codec == nil {
		return nil, fmt.Errorf("%w: topic %s không thuộc danh mục ghi sổ", ErrInvalid, topic)
	}
	native, rest, err := codec.NativeFromBinary(value)
	if err != nil {
		return nil, fmt.Errorf("%w: thân Avro %s: %v", ErrInvalid, topic, err)
	}
	if len(rest) != 0 {
		return nil, fmt.Errorf("%w: thân Avro %s còn %d byte thừa (lệch phiên bản lược đồ)", ErrInvalid, topic, len(rest))
	}
	text, err := codec.TextualFromNative(nil, native)
	if err != nil {
		return nil, fmt.Errorf("%w: mã hoá JSON Avro: %v", ErrInvalid, err)
	}
	if text, err = specUnionNames(text); err != nil {
		return nil, fmt.Errorf("%w: mã hoá JSON Avro: %v", ErrInvalid, err)
	}
	sum, _, err := canon.PayloadHash(text)
	if err != nil {
		return nil, fmt.Errorf("%w: chuẩn hoá JCS: %v", ErrInvalid, err)
	}
	return sum[:], nil
}

// specUnionNames đổi tên nhánh union của goavro về tên kiểu theo đặc tả Avro: goavro đặt tên nhánh kiểu
// logic là "<kiểu gốc>.<logicalType>" (ví dụ "bytes.decimal"), còn đặc tả và JsonEncoder của Avro Java dùng
// tên kiểu gốc ("bytes"). Tên trường Avro không chứa dấu chấm nên đối tượng một khoá có dạng đó chỉ có thể là
// nhánh union; kiểu có tên (record, enum, fixed) giữ nguyên tên đầy đủ.
func specUnionNames(text []byte) ([]byte, error) {
	dec := json.NewDecoder(bytes.NewReader(text))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		return nil, err
	}
	changed := false
	var walk func(any) any
	walk = func(x any) any {
		switch t := x.(type) {
		case map[string]any:
			if len(t) == 1 {
				for k, inner := range t {
					if base, _, ok := strings.Cut(k, "."); ok && primitive[base] {
						changed = true
						return map[string]any{base: walk(inner)}
					}
				}
			}
			for k, inner := range t {
				t[k] = walk(inner)
			}
		case []any:
			for i, inner := range t {
				t[i] = walk(inner)
			}
		}
		return x
	}
	v = walk(v)
	if !changed {
		return text, nil
	}
	return json.Marshal(v)
}

var primitive = map[string]bool{"int": true, "long": true, "float": true, "double": true, "bytes": true, "string": true}

// headers gom tiêu đề Kafka: dạng phẳng của OutboxHeadersTransform (id, tenantid, actorid, ce_type,
// occurredat, classification) và dạng EventRouter (ce_id, ce_tenantid, ce_source, khối JSON ce_headers).
func headers(r *kgo.Record) map[string]string {
	h := make(map[string]string, len(r.Headers))
	for _, x := range r.Headers {
		h[strings.ToLower(x.Key)] = string(x.Value)
	}
	if block := h["ce_headers"]; block != "" {
		var m map[string]any
		if json.Unmarshal([]byte(block), &m) == nil {
			for k, v := range m {
				k = strings.ToLower(k)
				if _, ok := h[k]; !ok {
					if s, ok := v.(string); ok {
						h[k] = s
					} else if v != nil {
						h[k] = strings.Trim(fmt.Sprint(v), "\"")
					}
				}
			}
		}
	}
	for flat, ce := range map[string]string{"id": "ce_id", "tenantid": "ce_tenantid"} {
		if h[flat] == "" {
			h[flat] = h[ce]
		}
	}
	return h
}

// Convert dựng sự kiện ghi sổ từ một bản ghi topic nghiệp vụ. Khoá Kafka là tenantId:aggregateId
// (cột event_key của outbox_event).
func Convert(r *kgo.Record) (ingest.EventInput, error) {
	var ev ingest.EventInput
	h := headers(r)
	tenantPart, aggPart, ok := strings.Cut(string(r.Key), ":")
	if !ok {
		return ev, fmt.Errorf("%w: khoá %q không có dạng tenant:aggregateId", ErrInvalid, r.Key)
	}
	var err error
	if ev.AggregateID, err = uuid.Parse(aggPart); err != nil {
		return ev, fmt.Errorf("%w: aggregateId trong khoá", ErrInvalid)
	}
	tenant := h["tenantid"]
	if tenant == "" {
		tenant = tenantPart
	}
	if ev.TenantID, err = uuid.Parse(tenant); err != nil {
		return ev, fmt.Errorf("%w: thiếu hoặc sai tenantid", ErrInvalid)
	}
	if ev.EventID, err = uuid.Parse(h["id"]); err != nil {
		return ev, fmt.Errorf("%w: thiếu hoặc sai id sự kiện", ErrInvalid)
	}
	ev.EventType = r.Topic
	if ev.PayloadHash, err = PayloadHash(r.Topic, r.Value); err != nil {
		return ev, err
	}
	ev.OccurredAt = r.Timestamp.UTC()
	if at := h["occurredat"]; at != "" {
		t, err := time.Parse(time.RFC3339Nano, at)
		if err != nil {
			return ev, fmt.Errorf("%w: occurredat %q", ErrInvalid, at)
		}
		ev.OccurredAt = t.UTC()
	}
	if a := h["actorid"]; a != "" {
		id, err := uuid.Parse(a)
		if err != nil {
			return ev, fmt.Errorf("%w: actorid", ErrInvalid)
		}
		ev.ActorID = &id
	}
	if src := strings.TrimSpace(h["ce_source"]); src != "" {
		ev.AggregateType = strings.ToUpper(src)
	}
	ev.Classification = strings.ToUpper(h["classification"])
	return ev, nil
}

// Forwarder là vòng tiêu thụ topic nghiệp vụ và ghi sys.ledger.append.v1.
type Forwarder struct {
	client *kgo.Client
	log    *slog.Logger
	count  func(name string, n int64)
}

// New tạo bộ chuyển tiếp; topics rỗng là toàn bộ danh mục. Producer bật idempotence, acks=all.
func New(brokers, topics []string, log *slog.Logger, count func(string, int64), opts ...kgo.Opt) (*Forwarder, error) {
	if log == nil {
		log = slog.Default()
	}
	if len(topics) == 0 {
		for t := range Topics {
			topics = append(topics, t)
		}
	}
	for _, t := range topics {
		if Topics[t] == "" {
			return nil, fmt.Errorf("forward: topic %s không thuộc danh mục ghi sổ", t)
		}
	}
	if count == nil {
		count = func(string, int64) {}
	}
	base := []kgo.Opt{
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(ConsumerGroup),
		kgo.ConsumeTopics(topics...),
		kgo.DisableAutoCommit(),
		kgo.FetchMaxWait(50 * time.Millisecond),
		kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()),
		kgo.BlockRebalanceOnPoll(),
		kgo.RequiredAcks(kgo.AllISRAcks()),
		kgo.RecordPartitioner(kgo.StickyKeyPartitioner(nil)),
	}
	cl, err := kgo.NewClient(append(base, opts...)...)
	if err != nil {
		return nil, fmt.Errorf("forward: tạo client Kafka: %w", err)
	}
	return &Forwarder{client: cl, log: log, count: count}, nil
}

// Close rời group và đóng kết nối.
func (f *Forwarder) Close() { f.client.Close() }

// DLQTopic trả topic thư chết của một topic nguồn theo quy ước `<topic>.dlq.<consumer>` (wiki trang 18).
func DLQTopic(topic string) string { return topic + ".dlq." + ConsumerGroup }

// PollOnce đọc tối đa max bản ghi, chuyển tiếp và chỉ xác nhận offset khi mọi bản ghi đã ghi xong
// (at-least-once; luồng ghi bỏ trùng theo event_id).
func (f *Forwarder) PollOnce(ctx context.Context, max int) (int, error) {
	fetches := f.client.PollRecords(ctx, max)
	defer f.client.AllowRebalance()
	if fetches.IsClientClosed() {
		return 0, context.Canceled
	}
	for _, fe := range fetches.Errors() {
		if errors.Is(fe.Err, context.Canceled) || errors.Is(fe.Err, context.DeadlineExceeded) {
			return 0, fe.Err
		}
		f.log.Error("lỗi đọc Kafka", "topic", fe.Topic, "partition", fe.Partition, "err", fe.Err)
	}
	records := fetches.Records()
	if len(records) == 0 {
		return 0, nil
	}
	out := make([]*kgo.Record, 0, len(records))
	forwarded := 0
	for _, r := range records {
		ev, err := Convert(r)
		var rec *kgo.Record
		if err == nil {
			rec, err = ingest.EncodeRecord(ev)
		}
		if err != nil {
			f.log.Error("sự kiện nghiệp vụ không chuyển tiếp được, chuyển thư chết",
				"topic", r.Topic, "partition", r.Partition, "offset", r.Offset, "err", err)
			out = append(out, deadLetter(r, err))
			f.count("ledger.forward.dead_total", 1)
			continue
		}
		out = append(out, rec)
		forwarded++
	}
	if err := f.client.ProduceSync(ctx, out...).FirstErr(); err != nil {
		f.rewind(records)
		return 0, fmt.Errorf("forward: ghi sys.ledger.append.v1: %w", err)
	}
	if err := f.client.CommitRecords(ctx, records...); err != nil {
		return forwarded, fmt.Errorf("forward: xác nhận offset: %w", err)
	}
	f.count("ledger.forward.records_total", int64(forwarded))
	return forwarded, nil
}

func deadLetter(r *kgo.Record, cause error) *kgo.Record {
	h := append([]kgo.RecordHeader(nil), r.Headers...)
	h = append(h,
		kgo.RecordHeader{Key: "x-inbox-dlq-reason", Value: []byte(cause.Error())},
		kgo.RecordHeader{Key: "x-inbox-dlq-consumer", Value: []byte(ConsumerGroup)},
		kgo.RecordHeader{Key: "x-inbox-dlq-original-topic", Value: []byte(r.Topic)},
		kgo.RecordHeader{Key: "x-inbox-dlq-failed-at", Value: []byte(time.Now().UTC().Format(time.RFC3339))},
	)
	return &kgo.Record{Topic: DLQTopic(r.Topic), Key: r.Key, Value: bytes.Clone(r.Value), Headers: h}
}

func (f *Forwarder) rewind(records []*kgo.Record) {
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
	f.client.SetOffsets(first)
}

// Run chuyển tiếp tới khi ctx bị huỷ; lỗi được ghi nhật ký rồi thử lại sau một nhịp.
func (f *Forwarder) Run(ctx context.Context) error {
	for ctx.Err() == nil {
		if _, err := f.PollOnce(ctx, 2000); err != nil {
			if ctx.Err() != nil || errors.Is(err, context.Canceled) {
				return nil
			}
			f.log.Error("lỗi chuyển tiếp sang sổ cái", "err", err)
			select {
			case <-ctx.Done():
			case <-time.After(time.Second):
			}
		}
	}
	return nil
}
