package replay

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"vn.viettel.okrkpi/services-go/outbox-replay/internal/sources"
	"vn.viettel.okrkpi/services-go/vtplatform/db"
	"vn.viettel.okrkpi/services-go/vtplatform/inbox"
)

type recordingProducer struct {
	mu          sync.Mutex
	cfg         ProducerConfig
	records     []KafkaRecord
	cancelAfter int
	cancelFunc  context.CancelFunc
}

func newSafeRecordingProducer() *recordingProducer {
	return &recordingProducer{
		cfg: ProducerConfig{
			EnableIdempotence: true,
			Acks:              "all",
		},
	}
}

func (p *recordingProducer) Config() ProducerConfig {
	return p.cfg
}

func (p *recordingProducer) Publish(_ context.Context, record KafkaRecord) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.records = append(p.records, record)
	if p.cancelAfter > 0 && len(p.records) == p.cancelAfter && p.cancelFunc != nil {
		p.cancelFunc()
	}
	return nil
}

func (p *recordingProducer) Records() []KafkaRecord {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]KafkaRecord, len(p.records))
	copy(out, p.records)
	return out
}

func (p *recordingProducer) Reset() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.records = nil
}

func loadTestRegistry(t *testing.T) *sources.Registry {
	t.Helper()
	dir := filepath.Join("..", "..", "..", "..", "contracts", "events")
	reg, _, err := sources.LoadAndValidate(
		filepath.Join(dir, "outbox-sources.yaml"),
		filepath.Join(dir, "catalog.yaml"),
	)
	if err != nil {
		t.Fatalf("LoadAndValidate failed: %v", err)
	}
	return reg
}

func TestDebeziumEventRouterByteForByteParity(t *testing.T) {
	reg := loadTestRegistry(t)
	createdAt := time.Date(2026, 10, 20, 8, 30, 0, 123456000, time.UTC)

	// Simulate binary Avro payload bytes (magic byte 0x00 + 8-byte globalId + Avro body)
	avroPayload := []byte{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2a, 0x14, 0x74, 0x61, 0x73, 0x6b, 0x2d, 0x30, 0x30, 0x31}
	row := OutboxRow{
		ID:               "0195b000-0000-7000-8000-000000000001",
		TenantID:         "11111111-1111-4111-8111-111111111111",
		AggregateType:    "Task",
		AggregateID:      "22222222-2222-4222-8222-222222222222",
		AggregateVersion: 7,
		EventType:        "task.task.assigned.v1",
		Topic:            "task.task.assigned.v1",
		EventKey:         "11111111-1111-4111-8111-111111111111:22222222-2222-4222-8222-222222222222",
		Payload:          avroPayload,
		SchemaVersion:    42,
		Headers: map[string]string{
			"traceparent":             "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
			"classification":          "C1",
			"apicurio.value.globalId": "42",
			"actor_id":                "33333333-3333-4333-8333-333333333333",
		},
		CreatedAt: createdAt,
	}

	replayedRec, err := BuildKafkaRecord("task", row, "")
	if err != nil {
		t.Fatalf("BuildKafkaRecord failed: %v", err)
	}
	debeziumRec, err := DebeziumEventRouterSimulate(reg.Defaults.EventRouter, "task", row, "")
	if err != nil {
		t.Fatalf("DebeziumEventRouterSimulate failed: %v", err)
	}

	if !replayedRec.EqualBytes(debeziumRec) {
		t.Fatalf("replayed KafkaRecord does not match Debezium EventRouter byte-for-byte:\nreplayed: %+v\ndebezium: %+v", replayedRec, debeziumRec)
	}
	if !bytes.Equal(replayedRec.Value, avroPayload) {
		t.Fatalf("payload bytes were modified during replay")
	}
	if gotKey := string(replayedRec.Key); gotKey != row.EventKey {
		t.Fatalf("expected key %q, got %q", row.EventKey, gotKey)
	}
	for k, want := range map[string]string{
		"ce_id":                   row.ID,
		"ce_type":                 row.EventType,
		"ce_tenantid":             row.TenantID,
		"traceparent":             "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
		"ce_classification":       "C1",
		"apicurio.value.globalId": "42",
		"aggregate_version":       "7",
	} {
		got, ok := replayedRec.HeaderValue(k)
		if !ok || got != want {
			t.Errorf("header %q: got %q (ok=%v), want %q", k, got, ok, want)
		}
	}
}

func TestReplay1000RowsTwoTenantsAndIdempotentInbox(t *testing.T) {
	reg := loadTestRegistry(t)
	store := NewInMemoryOutboxStore()
	producer := newSafeRecordingProducer()

	replayer, err := NewReplayer(reg, store, producer)
	if err != nil {
		t.Fatalf("NewReplayer failed: %v", err)
	}

	tenantA := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	tenantB := "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
	baseTime := time.Date(2026, 10, 20, 10, 0, 0, 0, time.UTC)

	topics := []string{
		"task.task.assigned.v1",
		"task.task.progressed.v1",
		"notify.message.requested.v1",
	}

	// Insert 1,000 rows: 500 for tenantA, 500 for tenantB.
	// Every row i has timestamp baseTime + i minutes.
	// Window [baseTime + 100m, baseTime + 699m] contains 600 rows total: 300 for tenantA, 300 for tenantB.
	for i := 0; i < 1000; i++ {
		ten := tenantA
		if i%2 == 1 {
			ten = tenantB
		}
		topic := topics[i%len(topics)]
		eventID := fmt.Sprintf("0195b000-0000-7000-8000-%012d", i+1)
		aggID := fmt.Sprintf("22222222-2222-4222-8222-%012d", (i/2)+1)
		ts := baseTime.Add(time.Duration(i) * time.Minute)

		store.Insert("task", OutboxRow{
			ID:               eventID,
			TenantID:         ten,
			AggregateType:    "Task",
			AggregateID:      aggID,
			AggregateVersion: 1,
			EventType:        topic,
			Topic:            topic,
			EventKey:         ten + ":" + aggID,
			Payload:          []byte(fmt.Sprintf("avro-binary-payload-%d", i)),
			SchemaVersion:    1,
			Headers: map[string]string{
				"traceparent":             "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
				"classification":          "C1",
				"apicurio.value.globalId": "1",
			},
			CreatedAt: ts,
		})
	}

	windowFrom := baseTime.Add(100 * time.Minute)
	windowTo := baseTime.Add(699 * time.Minute)

	// 1. Dry-run first (default mode): counts records without publishing to Kafka
	dryOpts := NewDefaultOptions()
	dryOpts.Now = func() time.Time { return baseTime.Add(12 * time.Hour) }
	dryOpts.Filter = Filter{
		Schema:   "task",
		TenantID: tenantA,
		From:     windowFrom,
		To:       windowTo,
	}
	dryRes, err := replayer.Run(context.Background(), dryOpts)
	if err != nil {
		t.Fatalf("DryRun failed: %v", err)
	}
	if !dryRes.DryRun {
		t.Fatalf("expected DryRun=true")
	}
	if dryRes.TotalMatched != 300 {
		t.Fatalf("expected 300 matched rows for tenantA in window, got %d", dryRes.TotalMatched)
	}
	if dryRes.TotalPublished != 0 || len(producer.Records()) != 0 {
		t.Fatalf("expected 0 published messages in dry-run, got %d", len(producer.Records()))
	}

	// 2. Confirmed replay (#1): publishes 300 domain records + 1 audit record on sys.audit.v1
	realOpts := dryOpts
	realOpts.DryRun = false
	realOpts.Confirm = true
	realOpts.BatchSize = 64
	realOpts.ActorID = "ops-admin-01"
	realOpts.Reason = "INC-2026-1020"

	realRes1, err := replayer.Run(context.Background(), realOpts)
	if err != nil {
		t.Fatalf("Confirmed replay #1 failed: %v", err)
	}
	if realRes1.TotalPublished != 300 {
		t.Fatalf("expected 300 published domain records, got %d", realRes1.TotalPublished)
	}
	if realRes1.AuditEventID == "" {
		t.Fatalf("expected non-empty AuditEventID on confirmed replay")
	}

	published1 := producer.Records()
	if len(published1) != 301 { // 300 events + 1 sys.audit.v1
		t.Fatalf("expected 301 total Kafka messages (300 + 1 audit), got %d", len(published1))
	}
	auditRec := published1[len(published1)-1]
	if auditRec.Topic != AuditTopic {
		t.Fatalf("expected last message on %s, got %s", AuditTopic, auditRec.Topic)
	}
	var auditBody AuditPayload
	if err := json.Unmarshal(auditRec.Value, &auditBody); err != nil {
		t.Fatalf("unmarshal audit payload failed: %v", err)
	}
	if auditBody.Action != "PHAT_LAI_OUTBOX" || auditBody.ActorID != "ops-admin-01" || auditBody.PublishedCount != 300 {
		t.Fatalf("unexpected audit payload: %+v", auditBody)
	}

	// Verify sample consumer with vtplatform/inbox.Guard processes all 300 events exactly once
	inboxPool := db.NewSimulatedPool(1)
	businessApply := make(map[string]int)
	guard, err := inbox.NewGuard("scoring", "svc-scoring-consumer")
	if err != nil {
		t.Fatalf("inbox.NewGuard failed: %v", err)
	}

	consumeRecords := func(records []KafkaRecord) (processedCount, dupCount int) {
		for idx, rec := range records {
			if rec.Topic == AuditTopic {
				continue
			}
			tenID, _ := rec.HeaderValue("ce_tenantid")
			if tenID != tenantA {
				t.Fatalf("unexpected tenant in replayed record: %s", tenID)
			}
			evtID, _ := rec.HeaderValue("ce_id")
			aggType, _ := rec.HeaderValue("aggregate_type")
			aggID, _ := rec.HeaderValue("aggregate_id")
			aggVerStr, _ := rec.HeaderValue("aggregate_version")
			aggVer, _ := strconv.ParseInt(aggVerStr, 10, 64)
			tp, _ := rec.HeaderValue("traceparent")

			incoming := inbox.IncomingEvent{
				EventID:          evtID,
				TenantID:         tenID,
				AggregateType:    aggType,
				AggregateID:      aggID,
				AggregateVersion: aggVer,
				SourceTopic:      rec.Topic,
				Partition:        0,
				Offset:           int64(idx + 1),
				Traceparent:      tp,
				Payload:          rec.Value,
			}
			outcome, err := guard.Process(context.Background(), inboxPool, incoming, func(_ context.Context, _ db.Tx, ev inbox.IncomingEvent) error {
				businessApply[ev.AggregateID]++
				return nil
			})
			if err != nil {
				t.Fatalf("guard.Process failed on record %d: %v", idx, err)
			}
			switch outcome {
			case inbox.OutcomeProcessed:
				processedCount++
			case inbox.OutcomeDuplicateEvent:
				dupCount++
			default:
				t.Fatalf("unexpected inbox outcome %s", outcome)
			}
		}
		return processedCount, dupCount
	}

	proc1, dup1 := consumeRecords(published1)
	if proc1 != 300 || dup1 != 0 {
		t.Fatalf("first pass: expected 300 processed and 0 duplicates, got processed=%d, dup=%d", proc1, dup1)
	}
	if len(businessApply) != 300 {
		t.Fatalf("expected 300 distinct aggregates updated in consumer state, got %d", len(businessApply))
	}

	// 3. Replay the exact same range a second time -> consumer inbox deduplicates 100% of events
	producer.Reset()
	realRes2, err := replayer.Run(context.Background(), realOpts)
	if err != nil {
		t.Fatalf("Confirmed replay #2 failed: %v", err)
	}
	if realRes2.TotalPublished != 300 {
		t.Fatalf("expected 300 published records on second replay, got %d", realRes2.TotalPublished)
	}

	proc2, dup2 := consumeRecords(producer.Records())
	if proc2 != 0 || dup2 != 300 {
		t.Fatalf("second pass: expected 0 processed and 300 duplicates, got processed=%d, dup=%d", proc2, dup2)
	}
	for aggID, count := range businessApply {
		if count != 1 {
			t.Fatalf("aggregate %s was applied %d times, expected 1", aggID, count)
		}
	}
}

func TestSafetyGuardsRetentionAndCheckpointResume(t *testing.T) {
	reg := loadTestRegistry(t)
	store := NewInMemoryOutboxStore()
	store.SetRetention("task", "3 days")
	producer := newSafeRecordingProducer()

	replayer, err := NewReplayer(reg, store, producer)
	if err != nil {
		t.Fatalf("NewReplayer failed: %v", err)
	}

	tenantID := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	now := time.Date(2026, 10, 20, 12, 0, 0, 0, time.UTC)

	// 1. Running with DryRun=false and Confirm=false must be rejected
	opts := NewDefaultOptions()
	opts.DryRun = false
	opts.Confirm = false
	opts.Filter = Filter{Schema: "task", TenantID: tenantID}
	if _, err := replayer.Run(context.Background(), opts); !errors.Is(err, ErrConfirmRequired) {
		t.Fatalf("expected ErrConfirmRequired, got %v", err)
	}

	// 2. Running with unsafe producer config must be rejected
	unsafeProducer := &recordingProducer{cfg: ProducerConfig{EnableIdempotence: false, Acks: "1"}}
	unsafeReplayer, _ := NewReplayer(reg, store, unsafeProducer)
	opts.Confirm = true
	if _, err := unsafeReplayer.Run(context.Background(), opts); !errors.Is(err, ErrUnsafeProducerConfig) {
		t.Fatalf("expected ErrUnsafeProducerConfig, got %v", err)
	}

	// 3. Seed 10 rows and test retention warning (--from 5 days ago exceeds 3 days / 72h retention)
	for i := 0; i < 10; i++ {
		store.Insert("task", OutboxRow{
			ID:               fmt.Sprintf("0195b000-0000-7000-8000-%012d", i+1),
			TenantID:         tenantID,
			AggregateType:    "Task",
			AggregateID:      fmt.Sprintf("22222222-2222-4222-8222-%012d", i+1),
			AggregateVersion: 1,
			EventType:        "task.task.statuschanged.v1",
			Topic:            "task.task.statuschanged.v1",
			EventKey:         tenantID + ":agg",
			Payload:          []byte("payload"),
			SchemaVersion:    1,
			CreatedAt:        now.Add(-time.Duration(10-i) * time.Minute),
		})
	}

	// Test mid-run cancellation after 4 published messages and checkpoint resume + consumer topic routing
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	producer.cancelAfter = 4
	producer.cancelFunc = cancel

	runOpts := NewDefaultOptions()
	runOpts.DryRun = false
	runOpts.Confirm = true
	runOpts.Consumer = "svc-scoring"
	runOpts.BatchSize = 3
	runOpts.Now = func() time.Time { return now }
	runOpts.Filter = Filter{
		Schema:   "task",
		TenantID: tenantID,
		From:     now.Add(-5 * 24 * time.Hour), // 5 days ago -> triggers 72h/3-day retention warning
		To:       now,
	}

	part1, err := replayer.Run(ctx, runOpts)
	if err != nil {
		t.Fatalf("part1 replay failed: %v", err)
	}
	if !part1.RetentionExceeded || len(part1.Warnings) == 0 {
		t.Fatalf("expected RetentionExceeded=true and warning when --from is 5 days old")
	}
	if !part1.Interrupted {
		t.Fatalf("expected Interrupted=true when context is cancelled after 4 records")
	}
	if part1.TotalPublished != 4 {
		t.Fatalf("expected 4 published records before interruption, got %d", part1.TotalPublished)
	}
	if part1.LastCreatedAt == nil || part1.LastEventID == "" {
		t.Fatalf("expected LastCreatedAt and LastEventID checkpoint on interruption")
	}

	// Resume from LastCreatedAt / LastEventID checkpoint
	producer.cancelAfter = 0
	producer.cancelFunc = nil
	resumeOpts := runOpts
	resumeOpts.Filter.AfterCreatedAt = part1.LastCreatedAt
	resumeOpts.Filter.AfterID = part1.LastEventID

	part2, err := replayer.Run(context.Background(), resumeOpts)
	if err != nil {
		t.Fatalf("part2 resume failed: %v", err)
	}
	if part2.Interrupted {
		t.Fatalf("expected Interrupted=false on resume")
	}
	if part2.TotalPublished != 6 {
		t.Fatalf("expected remaining 6 records to be published on resume, got %d", part2.TotalPublished)
	}

	// Verify all 10 domain records were routed to task.task.statuschanged.v1.replay.svc-scoring
	var domainRecs []KafkaRecord
	for _, r := range producer.Records() {
		if r.Topic != AuditTopic {
			domainRecs = append(domainRecs, r)
		}
	}
	if len(domainRecs) != 10 {
		t.Fatalf("expected 10 total domain records across part1 + part2, got %d", len(domainRecs))
	}
	for i, r := range domainRecs {
		wantTopic := "task.task.statuschanged.v1.replay.svc-scoring"
		if r.Topic != wantTopic {
			t.Fatalf("record %d routed to %q, want %q", i, r.Topic, wantTopic)
		}
		wantID := fmt.Sprintf("0195b000-0000-7000-8000-%012d", i+1)
		if gotID, _ := r.HeaderValue("ce_id"); gotID != wantID {
			t.Fatalf("record %d has ce_id=%q, want %q", i, gotID, wantID)
		}
	}
}
