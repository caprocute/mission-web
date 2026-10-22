// Package outbox ghi sự kiện vào registry.outbox_event trong cùng giao dịch với thay đổi nghiệp vụ (mẫu
// Transactional Outbox, TL-03 "Bảng outbox_event"); Debezium đọc WAL và đẩy lên Kafka toàn cục. Mã nghiệp
// vụ không gửi Kafka trực tiếp (BACKEND.md mục 3.4).
// Điểm chờ nền móng: payload hiện là JSON (headers.contentType = application/json, schema_version = 0) vì
// lược đồ Avro và Apicurio (NEN-007, NEN-011) chưa có; thay bằng vtplatform/outbox của NEN-017.
package outbox

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// Topic sự kiện hạ tầng của SVC-03 (TL-03 mục SVC-03: registry.tenant.changed.v1).
const TopicTenantChanged = "registry.tenant.changed.v1"

// Topic kỹ thuật báo tenant đã cấp phát xong (TL-01 "Tiếp nhận tenant"; không phải EVT nghiệp vụ).
const TopicTenantProvisioned = "sys.tenant.provisioned.v1"

// TopicNotifyRequested là topic của EVT-21 YeuCauThongBao (contracts/events/catalog.yaml).
const TopicNotifyRequested = "notify.message.requested.v1"

// Envelope là một sự kiện cần phát.
type Envelope struct {
	TenantID         uuid.UUID
	AggregateType    string
	AggregateID      uuid.UUID
	AggregateVersion int64
	EventType        string
	Topic            string
	Payload          any
	TraceParent      string
	ActorID          uuid.UUID
	Classification   string // C0 hoặc C1; C2/C3 bị chặn
	// EventKey ghi đè khoá sự kiện (mặc định tenant:aggregateId), ví dụ EVT-21 dùng tenant:recipientId.
	EventKey string
}

// ErrClassified chặn đưa dữ liệu C2/C3 vào outbox.
var ErrClassified = errors.New("outbox: không phát sự kiện mang dữ liệu C2/C3")

// Append ghi một dòng outbox trong giao dịch tx.
func Append(ctx context.Context, tx pgx.Tx, e Envelope) (uuid.UUID, error) {
	if e.Classification == "C2" || e.Classification == "C3" {
		return uuid.Nil, ErrClassified
	}
	if e.Classification == "" {
		e.Classification = "C1"
	}
	key := e.EventKey
	if key == "" {
		key = e.TenantID.String() + ":" + e.AggregateID.String()
	}
	payload, err := json.Marshal(e.Payload)
	if err != nil {
		return uuid.Nil, err
	}
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.Nil, err
	}
	now := time.Now().UTC()
	headers, _ := json.Marshal(map[string]string{
		"traceparent":    e.TraceParent,
		"actorId":        e.ActorID.String(),
		"classification": e.Classification,
		"occurredAt":     now.Format(time.RFC3339Nano),
		"contentType":    "application/json",
	})
	_, err = tx.Exec(ctx, `INSERT INTO registry.outbox_event
		(id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, topic, event_key, payload, schema_version, headers, created_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,0,$10,$11)`,
		id, e.TenantID, e.AggregateType, e.AggregateID, e.AggregateVersion, e.EventType, e.Topic,
		key, payload, headers, now)
	return id, err
}
