package outbox

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"vn.viettel.okrkpi/services-go/vtplatform/db"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

func TestWriter_RejectsC2AndC3Classification(t *testing.T) {
	pool := db.NewSimulatedPool(1)
	writer, err := NewWriter("notify", "urn:viettel:okrkpi:svc-12:notify")
	if err != nil {
		t.Fatalf("NewWriter failed: %v", err)
	}
	tenantA := "11111111-1111-4111-8111-111111111111"
	ctx := tenant.MustWithTenantID(context.Background(), tenantA)

	for _, forbidden := range []string{"C2", "C3", "c2", "c3", "SECRET", ""} {
		err := db.InTx(ctx, pool, func(ctx context.Context, tx db.Tx) error {
			_, err := writer.Write(ctx, tx, Envelope{
				AggregateType:    "Notification",
				AggregateID:      "notif-001",
				AggregateVersion: 1,
				EventType:        "EVT-01",
				Topic:            "task.assigned.v1",
				Payload:          []byte(`{"notificationId":"notif-001"}`),
				Classification:   forbidden,
			})
			return err
		})
		if !errors.Is(err, ErrInvalidClassification) {
			t.Fatalf("expected ErrInvalidClassification for %q, got %v", forbidden, err)
		}
	}

	if got := len(pool.OutboxRows()); got != 0 {
		t.Fatalf("expected 0 outbox rows after C2/C3 rejection, got %d", got)
	}
}

func TestWriter_CommitsAndRollsBackWithBusinessTx(t *testing.T) {
	pool := db.NewSimulatedPool(1)
	writer, err := NewWriter("ledger", "urn:viettel:okrkpi:svc-09:ledger")
	if err != nil {
		t.Fatalf("NewWriter failed: %v", err)
	}
	tenantA := "11111111-1111-4111-8111-111111111111"
	ctx := tenant.MustWithTenantID(context.Background(), tenantA)
	ctx = tenant.WithActorID(ctx, "actor-uuid-123")
	sc, _ := otel.ParseTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
	ctx = otel.WithSpanContext(ctx, sc)

	// 1. Committed transaction persists both business row and outbox row
	var eventID string
	err = db.InTx(ctx, pool, func(ctx context.Context, tx db.Tx) error {
		if _, err := tx.Exec(ctx, "INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)",
			"item-1", tenantA, "Ledger Entry 1"); err != nil {
			return err
		}
		var wErr error
		eventID, wErr = writer.Write(ctx, tx, Envelope{
			AggregateType:    "DigitalSignature",
			AggregateID:      "sig-001",
			AggregateVersion: 3,
			EventType:        "EVT-15",
			Topic:            "signature.completed.v1",
			Payload:          []byte(`{"signatureId":"sig-001"}`),
			SchemaVersion:    2,
			Classification:   ClassificationC1,
		})
		return wErr
	})
	if err != nil {
		t.Fatalf("committed tx failed: %v", err)
	}
	if !tenant.IsValidUUID(eventID) {
		t.Fatalf("expected valid UUID eventID, got %q", eventID)
	}

	rows := pool.OutboxRows()
	if len(rows) != 1 {
		t.Fatalf("expected 1 outbox row, got %d", len(rows))
	}
	row := rows[0]
	if row.Schema != "ledger" || row.ID != eventID || row.TenantID != tenantA {
		t.Fatalf("unexpected outbox row metadata: %+v", row)
	}
	if row.EventKey != tenantA+":sig-001" {
		t.Fatalf("expected default EventKey %q, got %q", tenantA+":sig-001", row.EventKey)
	}

	var headers map[string]string
	if err := json.Unmarshal([]byte(row.HeadersJSON), &headers); err != nil {
		t.Fatalf("failed to unmarshal headers JSON: %v", err)
	}
	if headers["ce_specversion"] != "1.0" ||
		headers["ce_id"] != eventID ||
		headers["tenant_id"] != tenantA ||
		headers["classification"] != "C1" ||
		headers["actor_id"] != "actor-uuid-123" ||
		headers["traceparent"] != "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" {
		t.Fatalf("unexpected CloudEvents headers: %+v", headers)
	}

	// 2. Rolled-back transaction rolls back both business row and outbox row
	sentinel := errors.New("downstream failure after outbox write")
	err = db.InTx(ctx, pool, func(ctx context.Context, tx db.Tx) error {
		_, err := writer.Write(ctx, tx, Envelope{
			AggregateType:    "DigitalSignature",
			AggregateID:      "sig-002",
			AggregateVersion: 1,
			EventType:        "EVT-15",
			Topic:            "signature.completed.v1",
			Payload:          []byte(`{"signatureId":"sig-002"}`),
			Classification:   ClassificationC0,
		})
		if err != nil {
			return err
		}
		return sentinel
	})
	if !errors.Is(err, sentinel) {
		t.Fatalf("expected sentinel error, got %v", err)
	}
	if got := len(pool.OutboxRows()); got != 1 {
		t.Fatalf("expected outbox row count to remain 1 after rollback, got %d", got)
	}
}
