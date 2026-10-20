package main

import (
	"bytes"
	"context"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"vn.viettel.okrkpi/services-go/outbox-replay/internal/replay"
)

type cliTestProducer struct {
	records []replay.KafkaRecord
}

func (p *cliTestProducer) Config() replay.ProducerConfig {
	return replay.ProducerConfig{EnableIdempotence: true, Acks: "all"}
}

func (p *cliTestProducer) Publish(_ context.Context, r replay.KafkaRecord) error {
	p.records = append(p.records, r)
	return nil
}

func TestCLIValidateDryRunAndConfirm(t *testing.T) {
	contractsDir := filepath.Join("..", "..", "..", "..", "contracts", "events")
	sourcesPath := filepath.Join(contractsDir, "outbox-sources.yaml")
	catalogPath := filepath.Join(contractsDir, "catalog.yaml")

	// 1. Test `validate` subcommand
	var stdout, stderr bytes.Buffer
	code := runCLI(
		context.Background(),
		[]string{"validate", "--sources", sourcesPath, "--catalog", catalogPath},
		&stdout,
		&stderr,
		nil,
		nil,
	)
	if code != 0 {
		t.Fatalf("validate failed with code %d: %s", code, stderr.String())
	}
	if !strings.Contains(stdout.String(), `"status": "VALID"`) {
		t.Fatalf("unexpected validate output: %s", stdout.String())
	}

	// 2. Test default `--dry-run` behavior
	store := replay.NewInMemoryOutboxStore()
	tenantID := "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	ts := time.Date(2026, 10, 20, 10, 0, 0, 0, time.UTC)
	store.Insert("task", replay.OutboxRow{
		ID:               "0195b000-0000-7000-8000-000000000001",
		TenantID:         tenantID,
		AggregateType:    "Task",
		AggregateID:      "22222222-2222-4222-8222-000000000001",
		AggregateVersion: 1,
		EventType:        "task.task.assigned.v1",
		Topic:            "task.task.assigned.v1",
		EventKey:         tenantID + ":22222222-2222-4222-8222-000000000001",
		Payload:          []byte("avro-payload"),
		SchemaVersion:    1,
		CreatedAt:        ts,
	})

	prod := &cliTestProducer{}
	stdout.Reset()
	stderr.Reset()
	code = runCLI(
		context.Background(),
		[]string{
			"--sources", sourcesPath,
			"--catalog", catalogPath,
			"--schema", "task",
			"--tenant", tenantID,
			"--from", ts.Add(-time.Hour).Format(time.RFC3339),
			"--to", ts.Add(time.Hour).Format(time.RFC3339),
		},
		&stdout,
		&stderr,
		store,
		prod,
	)
	if code != 0 {
		t.Fatalf("dry-run CLI failed with code %d: %s", code, stderr.String())
	}
	if len(prod.records) != 0 {
		t.Fatalf("expected 0 records published in default dry-run mode, got %d", len(prod.records))
	}
	var dryRes replay.Result
	if err := json.Unmarshal(stdout.Bytes(), &dryRes); err != nil {
		t.Fatalf("unmarshal dry-run result failed: %v", err)
	}
	if !dryRes.DryRun || dryRes.TotalMatched != 1 || dryRes.TotalPublished != 0 {
		t.Fatalf("unexpected dry-run result: %+v", dryRes)
	}

	// 3. Test `--dry-run=false` without `--confirm` fails with code 1
	stdout.Reset()
	stderr.Reset()
	code = runCLI(
		context.Background(),
		[]string{
			"--sources", sourcesPath,
			"--catalog", catalogPath,
			"--schema", "task",
			"--tenant", tenantID,
			"--dry-run=false",
		},
		&stdout,
		&stderr,
		store,
		prod,
	)
	if code == 0 || !strings.Contains(stderr.String(), "--confirm is required") {
		t.Fatalf("expected --confirm error when --dry-run=false without --confirm, got code=%d stderr=%s", code, stderr.String())
	}

	// 4. Test `--confirm` with `--consumer svc-scoring`
	stdout.Reset()
	stderr.Reset()
	code = runCLI(
		context.Background(),
		[]string{
			"--sources", sourcesPath,
			"--catalog", catalogPath,
			"--schema", "task",
			"--tenant", tenantID,
			"--from", ts.Add(-time.Hour).Format(time.RFC3339),
			"--to", ts.Add(time.Hour).Format(time.RFC3339),
			"--consumer", "svc-scoring",
			"--actor", "ops-user",
			"--reason", "DR-10-drill",
			"--confirm",
		},
		&stdout,
		&stderr,
		store,
		prod,
	)
	if code != 0 {
		t.Fatalf("confirmed CLI replay failed with code %d: %s", code, stderr.String())
	}
	if len(prod.records) != 2 { // 1 replayed record + 1 sys.audit.v1 record
		t.Fatalf("expected 2 published records (1 event + 1 audit), got %d", len(prod.records))
	}
	if prod.records[0].Topic != "task.task.assigned.v1.replay.svc-scoring" {
		t.Fatalf("expected replay topic task.task.assigned.v1.replay.svc-scoring, got %s", prod.records[0].Topic)
	}
	if prod.records[1].Topic != replay.AuditTopic {
		t.Fatalf("expected audit topic %s, got %s", replay.AuditTopic, prod.records[1].Topic)
	}
}
