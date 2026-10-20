// Package inbox provides idempotent Kafka event consumption and monotonic aggregate
// version checking backed by `<schema>.inbox_processed` and `<schema>.inbox_aggregate_version`
// (`V0_21__inbox.sql` from NEN-007, TL-01 §4.3).
package inbox

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"vn.viettel.okrkpi/services-go/vtplatform/db"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

// Outcome describes the result of processing an incoming event through Guard.
type Outcome string

const (
	// OutcomeProcessed indicates the event was newly processed and committed.
	OutcomeProcessed Outcome = "PROCESSED"
	// OutcomeDuplicateEvent indicates the (consumer, event_id) pair was already in inbox_processed.
	OutcomeDuplicateEvent Outcome = "DUPLICATE_EVENT"
	// OutcomeStaleVersion indicates the event's aggregate_version <= last_version for (consumer, aggregate_type, aggregate_id).
	OutcomeStaleVersion Outcome = "STALE_VERSION"
)

var (
	// ErrInvalidSchema is returned when the PostgreSQL schema identifier is invalid.
	ErrInvalidSchema = errors.New("inbox: invalid PostgreSQL schema identifier")
	// ErrInvalidConsumer is returned when the consumer group/handler name is empty.
	ErrInvalidConsumer = errors.New("inbox: consumer name is required")
	// ErrInvalidEvent is returned when required IncomingEvent fields are missing or invalid.
	ErrInvalidEvent = errors.New("inbox: invalid incoming event metadata")

	schemaIdentPattern = regexp.MustCompile(`^[a-zA-Z_][a-zA-Z0-9_]*$`)
)

// IncomingEvent represents a CloudEvents/Kafka message consumed by a Go microservice.
type IncomingEvent struct {
	// EventID is the CloudEvents UUID (ce_id).
	EventID string
	// TenantID is the tenant UUID from Kafka headers / payload.
	TenantID string
	// AggregateType is the domain aggregate type (optional; if non-empty with AggregateID and AggregateVersion >= 1, version guard is enforced).
	AggregateType string
	// AggregateID is the domain aggregate identifier.
	AggregateID string
	// AggregateVersion is the aggregate version carried by the event (>= 1 when version guard is active).
	AggregateVersion int64
	// SourceTopic is the Kafka topic from which the record was consumed.
	SourceTopic string
	// Partition is the Kafka partition number.
	Partition int
	// Offset is the Kafka offset within the partition.
	Offset int64
	// Traceparent is the optional W3C traceparent header from the Kafka record.
	Traceparent string
	// Payload is the raw message payload.
	Payload []byte
}

// Validate checks that required fields on IncomingEvent are present and valid.
func (e *IncomingEvent) Validate() error {
	if e == nil {
		return ErrInvalidEvent
	}
	normEventID, err := tenant.NormalizeUUID(e.EventID)
	if err != nil {
		return fmt.Errorf("%w: event_id must be a valid UUID: %v", ErrInvalidEvent, err)
	}
	e.EventID = normEventID

	normTenantID, err := tenant.NormalizeUUID(e.TenantID)
	if err != nil {
		return fmt.Errorf("%w: tenant_id must be a valid UUID: %v", ErrInvalidEvent, err)
	}
	e.TenantID = normTenantID

	if strings.TrimSpace(e.SourceTopic) == "" {
		e.SourceTopic = "unknown"
	}
	return nil
}

// HandlerFunc is the business callback executed inside the same tenant transaction as inbox state updates.
type HandlerFunc func(ctx context.Context, tx db.Tx, event IncomingEvent) error

// Guard enforces idempotency (`inbox_processed`) and aggregate version ordering (`inbox_aggregate_version`).
type Guard struct {
	schema   string
	consumer string
}

// NewGuard creates a new Inbox Guard for `<schema>.inbox_processed` and `<schema>.inbox_aggregate_version`.
func NewGuard(schema, consumer string) (*Guard, error) {
	trimmedSchema := strings.TrimSpace(schema)
	if !schemaIdentPattern.MatchString(trimmedSchema) {
		return nil, fmt.Errorf("%w: %q", ErrInvalidSchema, schema)
	}
	trimmedConsumer := strings.TrimSpace(consumer)
	if trimmedConsumer == "" {
		return nil, ErrInvalidConsumer
	}
	return &Guard{
		schema:   trimmedSchema,
		consumer: trimmedConsumer,
	}, nil
}

// ProcessInTx executes idempotency and version checks inside an existing tenant transaction tx.
func (g *Guard) ProcessInTx(ctx context.Context, tx db.Tx, event IncomingEvent, handler HandlerFunc) (Outcome, error) {
	if tx == nil {
		return "", errors.New("inbox: tx must not be nil")
	}
	if handler == nil {
		return "", errors.New("inbox: handler must not be nil")
	}
	if err := event.Validate(); err != nil {
		return "", err
	}
	ctxTenant, err := tenant.MustFromContext(ctx)
	if err != nil {
		return "", err
	}
	if ctxTenant != event.TenantID {
		return "", fmt.Errorf("inbox: event tenant_id %q does not match transaction tenant_id %q", event.TenantID, ctxTenant)
	}

	if event.Traceparent != "" {
		if parsed, err := otel.ParseTraceparent(event.Traceparent); err == nil {
			ctx = otel.WithSpanContext(ctx, otel.NewSpanContext(parsed))
		}
	}

	now := time.Now().UTC()

	// Step 1: Claim (consumer, event_id) in <schema>.inbox_processed
	insertProcessedSQL := fmt.Sprintf(
		`INSERT INTO %s.inbox_processed (event_id, consumer, processed_at, tenant_id, source_topic, kafka_partition, kafka_offset) VALUES ($1, $2, $3, $4, $5, $6, $7) ON CONFLICT (consumer, event_id) DO NOTHING`,
		g.schema,
	)
	tag, err := tx.Exec(
		ctx,
		insertProcessedSQL,
		event.EventID,
		g.consumer,
		now,
		event.TenantID,
		event.SourceTopic,
		event.Partition,
		event.Offset,
	)
	if err != nil {
		return "", fmt.Errorf("inbox: insert inbox_processed failed: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return OutcomeDuplicateEvent, nil
	}

	// Step 2: Enforce monotonic aggregate version in <schema>.inbox_aggregate_version if aggregate metadata is present
	if strings.TrimSpace(event.AggregateType) != "" && strings.TrimSpace(event.AggregateID) != "" && event.AggregateVersion >= 1 {
		upsertVersionSQL := fmt.Sprintf(
			`INSERT INTO %s.inbox_aggregate_version (consumer, aggregate_type, aggregate_id, tenant_id, last_version, last_event_id, updated_at) VALUES ($1, $2, $3, $4, $5, $6, $7) ON CONFLICT (consumer, aggregate_type, aggregate_id) DO UPDATE SET last_version = EXCLUDED.last_version, last_event_id = EXCLUDED.last_event_id, updated_at = EXCLUDED.updated_at WHERE %s.inbox_aggregate_version.last_version < EXCLUDED.last_version`,
			g.schema, g.schema,
		)
		vTag, err := tx.Exec(
			ctx,
			upsertVersionSQL,
			g.consumer,
			event.AggregateType,
			event.AggregateID,
			event.TenantID,
			event.AggregateVersion,
			event.EventID,
			now,
		)
		if err != nil {
			return "", fmt.Errorf("inbox: upsert inbox_aggregate_version failed: %w", err)
		}
		if vTag.RowsAffected() == 0 {
			// Event is older than or equal to the already-applied aggregate version;
			// keep inbox_processed entry so we don't reprocess it, and skip business handler.
			return OutcomeStaleVersion, nil
		}
	}

	// Step 3: Run business handler inside the same transaction
	if err := handler(ctx, tx, event); err != nil {
		return "", err
	}
	return OutcomeProcessed, nil
}

// Process opens a transaction scoped to event.TenantID via db.InTenantTx and invokes ProcessInTx.
func (g *Guard) Process(ctx context.Context, pool db.TxBeginner, event IncomingEvent, handler HandlerFunc) (Outcome, error) {
	if err := event.Validate(); err != nil {
		return "", err
	}
	var outcome Outcome
	err := db.InTenantTx(ctx, pool, event.TenantID, func(txCtx context.Context, tx db.Tx) error {
		var pErr error
		outcome, pErr = g.ProcessInTx(txCtx, tx, event, handler)
		return pErr
	})
	if err != nil {
		return "", err
	}
	return outcome, nil
}
