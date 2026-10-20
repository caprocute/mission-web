package inbox

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"

	"vn.viettel.okrkpi/services-go/vtplatform/db"
)

func TestGuard_IdempotencyAndStaleVersionAndRollback(t *testing.T) {
	pool := db.NewSimulatedPool(1)
	guard, err := NewGuard("notify", "svc-12-notification-consumer")
	if err != nil {
		t.Fatalf("NewGuard failed: %v", err)
	}

	tenantA := "11111111-1111-4111-8111-111111111111"
	evtV2 := IncomingEvent{
		EventID:          "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2",
		TenantID:         tenantA,
		AggregateType:    "Task",
		AggregateID:      "task-100",
		AggregateVersion: 2,
		SourceTopic:      "task.assigned.v1",
		Partition:        0,
		Offset:           101,
		Traceparent:      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
		Payload:          []byte(`{"taskId":"task-100","version":2}`),
	}

	var calls int32
	handler := func(ctx context.Context, tx db.Tx, event IncomingEvent) error {
		atomic.AddInt32(&calls, 1)
		_, err := tx.Exec(ctx, "INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)",
			event.EventID, event.TenantID, "Notification for "+event.AggregateID)
		return err
	}

	// 1. First delivery of v2 -> PROCESSED
	outcome, err := guard.Process(context.Background(), pool, evtV2, handler)
	if err != nil {
		t.Fatalf("first Process failed: %v", err)
	}
	if outcome != OutcomeProcessed {
		t.Fatalf("expected OutcomeProcessed, got %s", outcome)
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("expected handler called 1 time, got %d", calls)
	}

	// 2. Duplicate delivery of same EventID -> DUPLICATE_EVENT, handler NOT called again
	outcome, err = guard.Process(context.Background(), pool, evtV2, handler)
	if err != nil {
		t.Fatalf("duplicate Process failed: %v", err)
	}
	if outcome != OutcomeDuplicateEvent {
		t.Fatalf("expected OutcomeDuplicateEvent, got %s", outcome)
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("expected handler still called 1 time on duplicate, got %d", calls)
	}

	// 3. Out-of-order / stale version delivery (v1 arrives after v2 with a new EventID) -> STALE_VERSION
	evtV1Stale := IncomingEvent{
		EventID:          "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
		TenantID:         tenantA,
		AggregateType:    "Task",
		AggregateID:      "task-100",
		AggregateVersion: 1,
		SourceTopic:      "task.assigned.v1",
		Partition:        0,
		Offset:           102,
		Payload:          []byte(`{"taskId":"task-100","version":1}`),
	}
	outcome, err = guard.Process(context.Background(), pool, evtV1Stale, handler)
	if err != nil {
		t.Fatalf("stale Process failed: %v", err)
	}
	if outcome != OutcomeStaleVersion {
		t.Fatalf("expected OutcomeStaleVersion, got %s", outcome)
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("expected handler still called 1 time on stale version, got %d", calls)
	}

	// 4. Newer version (v3) fails in handler -> transaction rolls back inbox_processed & inbox_aggregate_version
	evtV3 := IncomingEvent{
		EventID:          "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3",
		TenantID:         tenantA,
		AggregateType:    "Task",
		AggregateID:      "task-100",
		AggregateVersion: 3,
		SourceTopic:      "task.assigned.v1",
		Partition:        0,
		Offset:           103,
		Payload:          []byte(`{"taskId":"task-100","version":3}`),
	}
	boom := errors.New("temporary SMTP failure")
	_, err = guard.Process(context.Background(), pool, evtV3, func(ctx context.Context, tx db.Tx, event IncomingEvent) error {
		return boom
	})
	if !errors.Is(err, boom) {
		t.Fatalf("expected boom error, got %v", err)
	}

	// Retry of v3 after rollback succeeds -> PROCESSED
	outcome, err = guard.Process(context.Background(), pool, evtV3, handler)
	if err != nil {
		t.Fatalf("retry of v3 after rollback failed: %v", err)
	}
	if outcome != OutcomeProcessed {
		t.Fatalf("expected OutcomeProcessed on retry of v3, got %s", outcome)
	}
	if atomic.LoadInt32(&calls) != 2 {
		t.Fatalf("expected handler called 2 times total, got %d", calls)
	}

	versions := pool.InboxVersionRows()
	if len(versions) != 1 || versions[0].LastVersion != 3 || versions[0].LastEventID != evtV3.EventID {
		t.Fatalf("expected aggregate version 3 in inbox_aggregate_version, got %+v", versions)
	}
}
