// Package outbox provides transactional outbox writing into `<schema>.outbox_event`
// compatible with NEN-007's `V0_20__outbox_event.sql` and Debezium Outbox Event Router
// (ADR-02, TL-01 §4.3). Events classified as C2 or C3 are rejected at the envelope boundary.
package outbox

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"vn.viettel.okrkpi/services-go/vtplatform/db"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

const (
	// CloudEventsSpecVersion is the CloudEvents specification version used on Kafka headers.
	CloudEventsSpecVersion = "1.0"
	// ClassificationC0 represents public/unclassified event metadata.
	ClassificationC0 = "C0"
	// ClassificationC1 represents internal operational event metadata.
	ClassificationC1 = "C1"
)

var (
	// ErrInvalidClassification is returned when an event attempts to publish C2/C3 data to Kafka.
	ErrInvalidClassification = errors.New("outbox: classification must be C0 or C1; C2/C3 payloads are forbidden on Kafka")
	// ErrInvalidSchemaName is returned when the target PostgreSQL schema identifier is unsafe.
	ErrInvalidSchemaName = errors.New("outbox: invalid PostgreSQL schema identifier")
	// ErrInvalidEnvelope is returned when required envelope fields are missing.
	ErrInvalidEnvelope = errors.New("outbox: missing or invalid required envelope fields")

	schemaIdentPattern = regexp.MustCompile(`^[a-zA-Z_][a-zA-Z0-9_]*$`)
)

// Envelope represents the transactional outbox record and CloudEvents 1.0 metadata
// written into `<schema>.outbox_event` within the same database transaction as business state.
type Envelope struct {
	// ID is the unique event UUID (ce_id). Generated automatically (UUIDv4) if empty.
	ID string
	// TenantID is the tenant UUID. Automatically populated from ctx if empty; if provided, must match ctx.
	TenantID string
	// AggregateType is the domain aggregate name (e.g., "Notification", "DigitalSignature").
	AggregateType string
	// AggregateID is the aggregate instance identifier.
	AggregateID string
	// AggregateVersion is the monotonically increasing version (>= 1) for inbox ordering.
	AggregateVersion int64
	// EventType is the canonical event code or CloudEvents type (e.g., "EVT-01" or "vn.viettel.okrkpi.task.assigned.v1").
	EventType string
	// Topic is the Kafka topic name (e.g., "task.assigned.v1").
	Topic string
	// EventKey is the Kafka partition key. Defaults to "<TenantID>:<AggregateID>" if empty.
	EventKey string
	// Payload is the serialized Avro or JSON payload (must only contain C0/C1 data + IDs).
	Payload []byte
	// SchemaVersion is the Avro schema version (>= 1, defaults to 1).
	SchemaVersion int
	// Classification is the data sensitivity level; only "C0" and "C1" are permitted on Kafka.
	Classification string
	// Source is the CloudEvents source URI (e.g., "urn:viettel:okrkpi:svc-11:notify").
	Source string
	// ContentType is the CloudEvents datacontenttype (defaults to "application/avro").
	ContentType string
	// ActorID is the optional authenticated user/service actor ID.
	ActorID string
	// ExtraHeaders holds optional additional headers serialized into the JSONB headers column.
	ExtraHeaders map[string]string
	// CreatedAt is the event creation timestamp (defaults to time.Now().UTC()).
	CreatedAt time.Time
}

// Validate checks all invariants on the Envelope, enforcing that Classification is strictly C0 or C1.
func (e *Envelope) Validate() error {
	if e == nil {
		return ErrInvalidEnvelope
	}
	cls := strings.ToUpper(strings.TrimSpace(e.Classification))
	if cls != ClassificationC0 && cls != ClassificationC1 {
		return fmt.Errorf("%w: got %q", ErrInvalidClassification, e.Classification)
	}
	e.Classification = cls

	if strings.TrimSpace(e.AggregateType) == "" {
		return fmt.Errorf("%w: aggregate_type is required", ErrInvalidEnvelope)
	}
	if strings.TrimSpace(e.AggregateID) == "" {
		return fmt.Errorf("%w: aggregate_id is required", ErrInvalidEnvelope)
	}
	if e.AggregateVersion < 1 {
		return fmt.Errorf("%w: aggregate_version must be >= 1", ErrInvalidEnvelope)
	}
	if strings.TrimSpace(e.EventType) == "" {
		return fmt.Errorf("%w: event_type is required", ErrInvalidEnvelope)
	}
	if strings.TrimSpace(e.Topic) == "" {
		return fmt.Errorf("%w: topic is required", ErrInvalidEnvelope)
	}
	if len(e.Payload) == 0 {
		return fmt.Errorf("%w: payload must not be empty", ErrInvalidEnvelope)
	}
	if e.SchemaVersion <= 0 {
		e.SchemaVersion = 1
	}
	if strings.TrimSpace(e.ContentType) == "" {
		e.ContentType = "application/avro"
	}
	if strings.TrimSpace(e.Source) == "" {
		e.Source = "urn:viettel:okrkpi:services-go"
	}
	if e.CreatedAt.IsZero() {
		e.CreatedAt = time.Now().UTC()
	} else {
		e.CreatedAt = e.CreatedAt.UTC()
	}
	return nil
}

// Writer inserts outbox records into `<schema>.outbox_event` inside an active db.Tx.
type Writer struct {
	schema string
	source string
}

// NewWriter creates a new Outbox Writer targeting `<schema>.outbox_event`.
func NewWriter(schema string, defaultSource ...string) (*Writer, error) {
	trimmed := strings.TrimSpace(schema)
	if !schemaIdentPattern.MatchString(trimmed) {
		return nil, fmt.Errorf("%w: %q", ErrInvalidSchemaName, schema)
	}
	src := "urn:viettel:okrkpi:" + trimmed
	if len(defaultSource) > 0 && strings.TrimSpace(defaultSource[0]) != "" {
		src = strings.TrimSpace(defaultSource[0])
	}
	return &Writer{
		schema: trimmed,
		source: src,
	}, nil
}

// Schema returns the PostgreSQL schema name configured on this Writer.
func (w *Writer) Schema() string {
	return w.schema
}

// Write validates env and inserts a row into `<schema>.outbox_event` inside tx.
// Because Write uses the caller's db.Tx, rolling back tx automatically rolls back the outbox event.
func (w *Writer) Write(ctx context.Context, tx db.Tx, env Envelope) (string, error) {
	if tx == nil {
		return "", errors.New("outbox: tx must not be nil")
	}
	ctxTenant, err := tenant.MustFromContext(ctx)
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(env.TenantID) == "" {
		env.TenantID = ctxTenant
	} else {
		normTenant, err := tenant.NormalizeUUID(env.TenantID)
		if err != nil {
			return "", err
		}
		if normTenant != ctxTenant {
			return "", fmt.Errorf("outbox: envelope tenant_id %q does not match context tenant_id %q", normTenant, ctxTenant)
		}
		env.TenantID = normTenant
	}

	if strings.TrimSpace(env.Source) == "" {
		env.Source = w.source
	}
	if err := env.Validate(); err != nil {
		return "", err
	}

	if strings.TrimSpace(env.ID) == "" {
		env.ID = newUUIDv4()
	} else {
		normID, err := tenant.NormalizeUUID(env.ID)
		if err != nil {
			return "", fmt.Errorf("%w: invalid event id UUID: %v", ErrInvalidEnvelope, err)
		}
		env.ID = normID
	}

	if strings.TrimSpace(env.EventKey) == "" {
		env.EventKey = env.TenantID + ":" + env.AggregateID
	}
	if strings.TrimSpace(env.ActorID) == "" {
		if actor, ok := tenant.ActorFromContext(ctx); ok {
			env.ActorID = actor
		}
	}

	headers := map[string]string{
		"ce_specversion":     CloudEventsSpecVersion,
		"ce_id":              env.ID,
		"ce_source":          env.Source,
		"ce_type":            env.EventType,
		"ce_time":            env.CreatedAt.Format(time.RFC3339Nano),
		"ce_datacontenttype": env.ContentType,
		"tenant_id":          env.TenantID,
		"aggregate_type":     env.AggregateType,
		"aggregate_id":       env.AggregateID,
		"aggregate_version":  fmt.Sprintf("%d", env.AggregateVersion),
		"schema_version":     fmt.Sprintf("%d", env.SchemaVersion),
		"classification":     env.Classification,
	}
	if env.ActorID != "" {
		headers["actor_id"] = env.ActorID
	}
	if tp := otel.TraceparentFromContext(ctx); tp != "" {
		headers["traceparent"] = tp
	}
	for k, v := range env.ExtraHeaders {
		if _, reserved := headers[k]; !reserved {
			headers[k] = v
		}
	}

	headersBytes, err := json.Marshal(headers)
	if err != nil {
		return "", fmt.Errorf("outbox: marshal headers failed: %w", err)
	}

	sql := fmt.Sprintf(
		`INSERT INTO %s.outbox_event (id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, topic, event_key, payload, schema_version, headers, created_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
		w.schema,
	)

	_, err = tx.Exec(
		ctx,
		sql,
		env.ID,
		env.TenantID,
		env.AggregateType,
		env.AggregateID,
		env.AggregateVersion,
		env.EventType,
		env.Topic,
		env.EventKey,
		env.Payload,
		env.SchemaVersion,
		string(headersBytes),
		env.CreatedAt,
	)
	if err != nil {
		return "", fmt.Errorf("outbox: insert failed: %w", err)
	}
	return env.ID, nil
}

func newUUIDv4() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%s-%s-%s-%s-%s",
		hex.EncodeToString(b[0:4]),
		hex.EncodeToString(b[4:6]),
		hex.EncodeToString(b[6:8]),
		hex.EncodeToString(b[8:10]),
		hex.EncodeToString(b[10:16]),
	)
}
