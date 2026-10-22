package sources

import (
	"errors"
	"path/filepath"
	"strings"
	"testing"
)

func contractsDir(t *testing.T) string {
	t.Helper()
	return filepath.Join("..", "..", "..", "..", "contracts", "events")
}

func TestOutboxSourcesMatchesCatalog(t *testing.T) {
	dir := contractsDir(t)
	sourcesPath := filepath.Join(dir, "outbox-sources.yaml")
	catalogPath := filepath.Join(dir, "catalog.yaml")

	reg, cat, err := LoadAndValidate(sourcesPath, catalogPath)
	if err != nil {
		t.Fatalf("LoadAndValidate failed: %v", err)
	}
	if len(cat.Events) != 26 {
		t.Fatalf("expected 26 business events in catalog.yaml, got %d", len(cat.Events))
	}
	if len(cat.TechnicalTopics) != 10 {
		t.Fatalf("expected 10 technical topics in catalog.yaml, got %d", len(cat.TechnicalTopics))
	}
	if len(reg.Sources) != 18 {
		t.Fatalf("expected 18 outbox schemas in outbox-sources.yaml, got %d", len(reg.Sources))
	}

	// Verify TL-03 L4234 table.include.list for tenant_data matches the 13 tenant schemas
	expectedTenantTables := "okr.outbox_event,task.outbox_event,product.outbox_event,appraisal.outbox_event,policy.outbox_event,dossier.outbox_event,oversight.outbox_event,config.outbox_event,scoring.outbox_event,org_replica.outbox_event,docgen.outbox_event,notify.outbox_event,integ.outbox_event"
	gotTenantTables := reg.TableIncludeListForDatabase("tenant_data")
	if gotTenantTables != expectedTenantTables {
		t.Fatalf("TableIncludeListForDatabase(tenant_data) mismatch:\ngot:  %s\nwant: %s", gotTenantTables, expectedTenantTables)
	}

	// Verify allowed topics and wildcard topics per schema
	if err := reg.ValidateSchemaAndTopic("task", "task.task.assigned.v1"); err != nil {
		t.Errorf("expected task.task.assigned.v1 to be allowed for schema task: %v", err)
	}
	if err := reg.ValidateSchemaAndTopic("task", "notify.message.requested.v1"); err != nil {
		t.Errorf("expected wildcard topic notify.message.requested.v1 to be allowed for schema task: %v", err)
	}
	if err := reg.ValidateSchemaAndTopic("task", "sys.audit.v1"); err != nil {
		t.Errorf("expected wildcard topic sys.audit.v1 to be allowed for schema task: %v", err)
	}
	if err := reg.ValidateSchemaAndTopic("task", "period.drain.marker"); err != nil {
		t.Errorf("expected wildcard topic period.drain.marker to be allowed for schema task: %v", err)
	}
}

func TestRejectsUnknownSchemaAndUnlistedTopic(t *testing.T) {
	dir := contractsDir(t)
	reg, cat, err := LoadAndValidate(
		filepath.Join(dir, "outbox-sources.yaml"),
		filepath.Join(dir, "catalog.yaml"),
	)
	if err != nil {
		t.Fatalf("LoadAndValidate failed: %v", err)
	}

	// Unknown schema
	if err := reg.ValidateSchemaAndTopic("unknown_schema", "task.task.assigned.v1"); !errors.Is(err, ErrUnregisteredSchema) {
		t.Fatalf("expected ErrUnregisteredSchema, got %v", err)
	}

	// Topic belonging to another schema
	if err := reg.ValidateSchemaAndTopic("task", "okr.objective.approved.v1"); !errors.Is(err, ErrTopicNotAllowed) {
		t.Fatalf("expected ErrTopicNotAllowed for cross-schema topic, got %v", err)
	}

	// Non-outbox topic (EVT-20 analytics.anomaly.detected.v1)
	if err := reg.ValidateSchemaAndTopic("oversight", "analytics.anomaly.detected.v1"); !errors.Is(err, ErrTopicNotAllowed) {
		t.Fatalf("expected ErrTopicNotAllowed for non-outbox topic EVT-20, got %v", err)
	}

	// Completely unknown topic
	if err := reg.ValidateSchemaAndTopic("task", "rogue.topic.injected.v1"); !errors.Is(err, ErrTopicNotAllowed) {
		t.Fatalf("expected ErrTopicNotAllowed for unknown topic, got %v", err)
	}

	// Tamper with registry to add an unknown topic -> ValidateAgainstCatalog must reject it
	badReg, err := LoadRegistry(filepath.Join(dir, "outbox-sources.yaml"))
	if err != nil {
		t.Fatalf("LoadRegistry failed: %v", err)
	}
	badReg.Sources[0].Topics = append(badReg.Sources[0].Topics, "unknown.event.topic.v1")
	err = badReg.ValidateAgainstCatalog(cat)
	if err == nil || !errors.Is(err, ErrValidationFailed) || !strings.Contains(err.Error(), "unknown.event.topic.v1") {
		t.Fatalf("expected ValidateAgainstCatalog to fail on unknown topic, got %v", err)
	}

	// Tamper with registry so two schemas claim the same EVT -> ValidateAgainstCatalog must reject it
	dupReg, err := LoadRegistry(filepath.Join(dir, "outbox-sources.yaml"))
	if err != nil {
		t.Fatalf("LoadRegistry failed: %v", err)
	}
	dupReg.Sources[1].Events = append(dupReg.Sources[1].Events, "EVT-01")
	err = dupReg.ValidateAgainstCatalog(cat)
	if err == nil || !errors.Is(err, ErrValidationFailed) {
		t.Fatalf("expected ValidateAgainstCatalog to fail when EVT-01 is claimed twice, got %v", err)
	}
}
