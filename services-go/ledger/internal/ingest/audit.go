package ingest

import (
	"context"
	"time"

	"github.com/google/uuid"
)

// AuditAggregateType là loại đối tượng của sự kiện kiểm toán thao tác kiểm tra sổ cái (suy luận).
const AuditAggregateType = "KIEM_TRA_SO_CAI"

// Audit ghi trực tiếp một bản ghi sys.audit.v1 vào chuỗi của đối tượng (SCR-1302: mọi lần tra cứu, kiểm tra
// được ghi sổ cái). Tải trọng chỉ gồm mã thao tác và tham số C1; sổ cái chỉ giữ băm của nó.
func (ing *Ingester) Audit(ctx context.Context, tenantID uuid.UUID, actor *uuid.UUID, operation string, subject uuid.UUID, detail map[string]any) error {
	kind := "HE_THONG"
	if actor != nil {
		kind = "NGUOI"
	}
	payload := map[string]any{"operation": operation, "subjectId": subject.String()}
	for k, v := range detail {
		payload[k] = v
	}
	ev := EventInput{
		EventID: uuid.Must(uuid.NewV7()), TenantID: tenantID, EventType: "sys.audit.v1", AggregateType: AuditAggregateType,
		AggregateID: subject, ActorID: actor, ActorKind: kind, OccurredAt: time.Now().UTC(), Classification: "C1",
		Payload: payload, Metadata: map[string]any{"operation": operation},
	}
	_, err := ing.AppendBatch(ctx, tenantID, ev.ChainPartition(), []EventInput{ev})
	return err
}
