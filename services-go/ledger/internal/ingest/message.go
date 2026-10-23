package ingest

import (
	_ "embed"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/linkedin/goavro/v2"
	"github.com/twmb/franz-go/pkg/kgo"

	"mission/services-go/ledger/internal/canon"
)

// Tiêu đề Kafka chế độ nhị phân CloudEvents (wiki trang 18) mà luồng ghi đọc. metaHeaderPrefix là
// tiêu đề mang metadata C1 (trạng thái trước/sau, mã tham chiếu) của bản ghi sổ (suy luận).
const (
	HeaderEventID        = "id"
	HeaderTenantID       = "tenantid"
	HeaderActorID        = "actorid"
	HeaderActorKind      = "actorkind"
	HeaderSubject        = "subject"
	HeaderClassification = "classification"
	metaHeaderPrefix     = "ledger_meta_"
)

//go:embed schemas/LedgerAppend.avsc
var ledgerAppendSchema string

var ledgerAppendCodec = func() *goavro.Codec {
	c, err := goavro.NewCodec(ledgerAppendSchema)
	if err != nil {
		panic(fmt.Sprintf("ingest: lược đồ LedgerAppend: %v", err))
	}
	return c
}()

// EventInput là một sự kiện cần ghi sổ.
type EventInput struct {
	EventID        uuid.UUID
	TenantID       uuid.UUID
	EventType      string
	AggregateType  string
	AggregateID    uuid.UUID
	ActorID        *uuid.UUID
	ActorKind      string
	OccurredAt     time.Time
	Classification string
	// PayloadHash là SHA-256 của dữ liệu sự kiện chuẩn hoá RFC 8785; rỗng thì băm Payload tại chỗ.
	PayloadHash []byte
	// Payload chỉ dùng cho bản ghi đặc biệt do lệnh quản trị tạo; sổ cái không lưu tải trọng.
	Payload  any
	Metadata map[string]any
	// OriginLastEntryHash chỉ dùng cho TENANT_MOVED_IN.
	OriginLastEntryHash []byte
	OriginLastSeqNo     int64
}

// ChainPartition là phân vùng chuỗi 0..63 của sự kiện.
func (e EventInput) ChainPartition() int16 { return canon.ChainPartition(e.AggregateID) }

// PartitionKey là khoá Kafka "<tenant_id>:<chain_partition>".
func (e EventInput) PartitionKey() string {
	return fmt.Sprintf("%s:%d", e.TenantID, e.ChainPartition())
}

// DecodeRecord đọc một bản ghi sys.ledger.append.v1: thân Avro LedgerAppend và tiêu đề CloudEvents.
// Lỗi bọc ErrInvalidEvent là lỗi vĩnh viễn, bản ghi chuyển thư chết.
func DecodeRecord(r *kgo.Record) (EventInput, error) {
	h := make(map[string]string, len(r.Headers))
	for _, x := range r.Headers {
		h[strings.ToLower(x.Key)] = string(x.Value)
	}
	native, _, err := ledgerAppendCodec.NativeFromBinary(r.Value)
	if err != nil {
		return EventInput{}, fmt.Errorf("%w: thân Avro LedgerAppend: %v", ErrInvalidEvent, err)
	}
	m, _ := native.(map[string]any)
	var ev EventInput
	if ev.EventID, err = uuid.Parse(str(m["sourceEventId"])); err != nil {
		return ev, fmt.Errorf("%w: sourceEventId", ErrInvalidEvent)
	}
	ev.EventType = str(m["sourceEventType"])
	if ev.AggregateID, err = uuid.Parse(str(m["aggregateId"])); err != nil {
		return ev, fmt.Errorf("%w: aggregateId", ErrInvalidEvent)
	}
	chain, _ := m["chainNo"].(int32)
	occurred, _ := m["sourceOccurredAt"].(time.Time)
	ev.OccurredAt = occurred.UTC()
	ev.PayloadHash, _ = m["payloadHash"].([]byte)

	tenant := h[HeaderTenantID]
	if tenant == "" {
		tenant, _, _ = strings.Cut(string(r.Key), ":")
	}
	if ev.TenantID, err = uuid.Parse(tenant); err != nil {
		return ev, fmt.Errorf("%w: thiếu hoặc sai tenantid", ErrInvalidEvent)
	}
	if !IsLegalEvent(ev.EventType) || adminOnly[ev.EventType] {
		return ev, fmt.Errorf("%w: %w: %s", ErrInvalidEvent, ErrEventNotInLegalCatalog, ev.EventType)
	}
	if int16(chain) != ev.ChainPartition() {
		return ev, fmt.Errorf("%w: chainNo %d khác phân vùng tính từ aggregateId (%d)",
			ErrInvalidEvent, chain, ev.ChainPartition())
	}
	if len(ev.PayloadHash) != 32 {
		return ev, fmt.Errorf("%w: payloadHash phải đủ 32 byte", ErrInvalidEvent)
	}
	if ev.OccurredAt.IsZero() {
		return ev, fmt.Errorf("%w: thiếu sourceOccurredAt", ErrInvalidEvent)
	}
	if a := h[HeaderActorID]; a != "" {
		id, err := uuid.Parse(a)
		if err != nil {
			return ev, fmt.Errorf("%w: actorid", ErrInvalidEvent)
		}
		ev.ActorID = &id
	}
	ev.ActorKind = actorKind(h[HeaderActorKind], ev.ActorID)
	ev.AggregateType = aggregateType(h[HeaderSubject], ev.EventType)
	ev.Classification = strings.ToUpper(h[HeaderClassification])
	for k, v := range h {
		if key, ok := strings.CutPrefix(k, metaHeaderPrefix); ok {
			if ev.Metadata == nil {
				ev.Metadata = map[string]any{}
			}
			ev.Metadata[key] = v
		}
	}
	if ev.Classification != "" {
		if ev.Metadata == nil {
			ev.Metadata = map[string]any{}
		}
		ev.Metadata["classification"] = ev.Classification
	}
	if err := ValidateMetadataC2(ev.Classification, ev.Metadata); err != nil {
		return ev, fmt.Errorf("%w: %w", ErrInvalidEvent, err)
	}
	return ev, nil
}

// EncodeRecord dựng bản ghi sys.ledger.append.v1 cho một sự kiện (bộ chuyển tiếp và kiểm thử).
func EncodeRecord(ev EventInput) (*kgo.Record, error) {
	if len(ev.PayloadHash) != 32 {
		return nil, fmt.Errorf("%w: payloadHash phải đủ 32 byte", ErrInvalidEvent)
	}
	value, err := ledgerAppendCodec.BinaryFromNative(nil, map[string]any{
		"sourceEventId":    ev.EventID.String(),
		"sourceEventType":  ev.EventType,
		"aggregateId":      ev.AggregateID.String(),
		"chainNo":          int32(ev.ChainPartition()),
		"sourceOccurredAt": ev.OccurredAt.UTC(),
		"payloadHash":      ev.PayloadHash,
	})
	if err != nil {
		return nil, err
	}
	headers := []kgo.RecordHeader{
		{Key: HeaderEventID, Value: []byte(ev.EventID.String())},
		{Key: HeaderTenantID, Value: []byte(ev.TenantID.String())},
	}
	if ev.ActorID != nil {
		headers = append(headers, kgo.RecordHeader{Key: HeaderActorID, Value: []byte(ev.ActorID.String())})
	}
	if ev.ActorKind != "" {
		headers = append(headers, kgo.RecordHeader{Key: HeaderActorKind, Value: []byte(ev.ActorKind)})
	}
	if ev.AggregateType != "" {
		headers = append(headers, kgo.RecordHeader{Key: HeaderSubject,
			Value: []byte(ev.AggregateType + "/" + ev.AggregateID.String())})
	}
	if ev.Classification != "" {
		headers = append(headers, kgo.RecordHeader{Key: HeaderClassification, Value: []byte(ev.Classification)})
	}
	for k, v := range ev.Metadata {
		if k == "classification" {
			continue
		}
		headers = append(headers, kgo.RecordHeader{Key: metaHeaderPrefix + k, Value: []byte(fmt.Sprint(v))})
	}
	return &kgo.Record{Topic: TopicLedgerAppend, Key: []byte(ev.PartitionKey()), Value: value, Headers: headers}, nil
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

// actorKind lấy tiêu đề actorkind nếu hợp lệ; không có thì có actorid là NGUOI, không có là HE_THONG
// (suy luận, phong bì CloudEvents chưa có loại tác nhân).
func actorKind(header string, actor *uuid.UUID) string {
	switch k := strings.ToUpper(strings.TrimSpace(header)); k {
	case "NGUOI", "DICH_VU", "HE_THONG":
		return k
	}
	if actor != nil {
		return "NGUOI"
	}
	return "HE_THONG"
}

// aggregateType lấy phần trước "/" của subject (`<aggregateType>/<aggregateId>`); thiếu thì lấy đoạn
// thứ hai của topic viết hoa, ví dụ task.task.assigned.v1 là TASK (suy luận).
func aggregateType(subject, eventType string) string {
	t, _, ok := strings.Cut(subject, "/")
	if !ok || strings.TrimSpace(t) == "" {
		parts := strings.Split(eventType, ".")
		t = "SYSTEM"
		if len(parts) >= 3 {
			t = parts[1]
		}
	}
	t = strings.ToUpper(strings.TrimSpace(t))
	if len(t) > 32 {
		t = t[:32]
	}
	return t
}
