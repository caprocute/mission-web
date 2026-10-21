// Package replay implements the transactional outbox replay engine (NEN-022, ADR-03,
// TL-03 "Transactional Outbox và Debezium", TL-04 DR-10 & OPS-24).
package replay

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"vn.viettel.okrkpi/services-go/outbox-replay/internal/sources"
	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

const (
	// AuditTopic is the technical topic receiving an audit trail whenever a real replay executes.
	AuditTopic = "sys.audit.v1"
	// DefaultRetentionHours is the 72-hour (3-day) outbox partition retention from TL-03 and TL-04.
	DefaultRetentionHours = 72
	// DefaultBatchSize is the keyset pagination batch size when reading outbox_event.
	DefaultBatchSize = 500
)

var (
	// ErrConfirmRequired is returned when DryRun is false but Confirm is not set.
	ErrConfirmRequired = errors.New("replay: --confirm is required when running with --dry-run=false")
	// ErrInvalidFilter is returned when required filter arguments are missing or invalid.
	ErrInvalidFilter = errors.New("replay: invalid replay filter")
	// ErrUnsafeProducerConfig is returned when the Kafka producer does not enforce enable.idempotence=true and acks=all.
	ErrUnsafeProducerConfig = errors.New("replay: producer must have enable.idempotence=true and acks=all")

	consumerIdentRe = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]*$`)
)

// OutboxRow mirrors a record in `<schema>.outbox_event` (TL-03 L768, NEN-011, NEN-032).
type OutboxRow struct {
	ID               string
	TenantID         string
	AggregateType    string
	AggregateID      string
	AggregateVersion int64
	EventType        string
	Topic            string
	EventKey         string
	Payload          []byte
	SchemaVersion    int
	Headers          map[string]string
	CreatedAt        time.Time
}

// KafkaHeader is a single binary Kafka record header.
type KafkaHeader struct {
	Key   string
	Value []byte
}

// KafkaRecord represents a Kafka message emitted by the replay tool or Debezium Outbox EventRouter.
type KafkaRecord struct {
	Topic   string
	Key     []byte
	Value   []byte
	Headers []KafkaHeader
}

// HeaderValue returns the string value of the first header matching key.
func (r KafkaRecord) HeaderValue(key string) (string, bool) {
	for _, h := range r.Headers {
		if h.Key == key {
			return string(h.Value), true
		}
	}
	return "", false
}

// EqualBytes compares two KafkaRecords byte-for-byte across Topic, Key, Value, and all Headers.
func (r KafkaRecord) EqualBytes(other KafkaRecord) bool {
	if r.Topic != other.Topic {
		return false
	}
	if !bytes.Equal(r.Key, other.Key) {
		return false
	}
	if !bytes.Equal(r.Value, other.Value) {
		return false
	}
	if len(r.Headers) != len(other.Headers) {
		return false
	}
	for i := range r.Headers {
		if r.Headers[i].Key != other.Headers[i].Key {
			return false
		}
		if !bytes.Equal(r.Headers[i].Value, other.Headers[i].Value) {
			return false
		}
	}
	return true
}

// ProducerConfig captures the Kafka producer durability and idempotency settings.
type ProducerConfig struct {
	EnableIdempotence bool
	Acks              string
}

// Producer abstracts the Kafka producer (e.g. franz-go kgo.Client) with idempotency verification.
type Producer interface {
	Config() ProducerConfig
	Publish(ctx context.Context, record KafkaRecord) error
}

// Filter specifies which rows in `<schema>.outbox_event` to inspect or replay.
type Filter struct {
	Schema      string
	TenantID    string
	From        time.Time
	To          time.Time
	EventID     string
	AggregateID string
	EventType   string
	Topic       string
	// AfterCreatedAt and AfterID allow resuming a previously interrupted replay after a checkpoint.
	AfterCreatedAt *time.Time
	AfterID        string
}

// Validate normalizes and validates the filter against the outbox sources registry.
func (f *Filter) Validate(reg *sources.Registry) error {
	if f == nil {
		return ErrInvalidFilter
	}
	f.Schema = strings.TrimSpace(f.Schema)
	if f.Schema == "" {
		return fmt.Errorf("%w: --schema is required", ErrInvalidFilter)
	}
	if _, ok := reg.Schema(f.Schema); !ok {
		return fmt.Errorf("%w: %v", sources.ErrUnregisteredSchema, f.Schema)
	}

	if strings.TrimSpace(f.TenantID) == "" {
		return fmt.Errorf("%w: --tenant is required", ErrInvalidFilter)
	}
	normTenant, err := tenant.NormalizeUUID(f.TenantID)
	if err != nil {
		return fmt.Errorf("%w: invalid --tenant UUID: %v", ErrInvalidFilter, err)
	}
	f.TenantID = normTenant

	if strings.TrimSpace(f.EventID) != "" {
		normEvt, err := tenant.NormalizeUUID(f.EventID)
		if err != nil {
			return fmt.Errorf("%w: invalid --event-id UUID: %v", ErrInvalidFilter, err)
		}
		f.EventID = normEvt
	}

	if !f.From.IsZero() && !f.To.IsZero() && f.To.Before(f.From) {
		return fmt.Errorf("%w: --to (%s) must not be before --from (%s)",
			ErrInvalidFilter, f.To.Format(time.RFC3339), f.From.Format(time.RFC3339))
	}

	if strings.TrimSpace(f.Topic) != "" {
		f.Topic = strings.TrimSpace(f.Topic)
		if err := reg.ValidateSchemaAndTopic(f.Schema, f.Topic); err != nil {
			return err
		}
	}
	return nil
}

// Options controls execution of the outbox replay engine.
type Options struct {
	Filter          Filter
	Consumer        string // optional: routes to <topic>.replay.<consumer> when non-empty
	DryRun          bool   // default true via NewDefaultOptions
	Confirm         bool   // required when DryRun == false
	RateLimitPerSec int    // max messages/sec (0 = unlimited)
	BatchSize       int    // keyset pagination batch size (default 500)
	ActorID         string // operator identity recorded in sys.audit.v1
	Reason          string // operational ticket/reason recorded in sys.audit.v1
	Now             func() time.Time
}

// NewDefaultOptions returns Options initialized to safe defaults (`DryRun: true`, `BatchSize: 500`).
func NewDefaultOptions() Options {
	return Options{
		DryRun:    true,
		Confirm:   false,
		BatchSize: DefaultBatchSize,
		Now:       func() time.Time { return time.Now().UTC() },
	}
}

// OutboxStore reads outbox rows and partition retention metadata from PostgreSQL.
type OutboxStore interface {
	// QueryBatch returns up to `limit` rows matching `filter` ordered by `(created_at ASC, id ASC)`
	// strictly after `(afterCreatedAt, afterID)` when `afterCreatedAt != nil`.
	QueryBatch(ctx context.Context, filter Filter, afterCreatedAt *time.Time, afterID string, limit int) ([]OutboxRow, error)
	// RetentionPolicy returns the pg_partman `retention` string from `partman.part_config`
	// for `<schema>.outbox_event` (e.g. `"3 days"` or `"72 hours"`).
	RetentionPolicy(ctx context.Context, schema string) (string, error)
}

// Result summarizes the outcome of a dry-run or confirmed replay execution.
type Result struct {
	DryRun            bool              `json:"dryRun"`
	Schema            string            `json:"schema"`
	TenantID          string            `json:"tenantId"`
	TargetConsumer    string            `json:"targetConsumer,omitempty"`
	TotalMatched      int               `json:"totalMatched"`
	TotalPublished    int               `json:"totalPublished"`
	CountByTopic      map[string]int    `json:"countByTopic"`
	CountByTenant     map[string]int    `json:"countByTenant"`
	RetentionPolicy   string            `json:"retentionPolicy"`
	RetentionDuration time.Duration     `json:"retentionDuration"`
	RetentionCutoff   time.Time         `json:"retentionCutoff"`
	RetentionExceeded bool              `json:"retentionExceeded"`
	Warnings          []string          `json:"warnings,omitempty"`
	Interrupted       bool              `json:"interrupted"`
	LastCreatedAt     *time.Time        `json:"lastCreatedAt,omitempty"`
	LastEventID       string            `json:"lastEventId,omitempty"`
	AuditEventID      string            `json:"auditEventId,omitempty"`
	Metadata          map[string]string `json:"metadata,omitempty"`
}

// AuditPayload is the JSON payload emitted to `sys.audit.v1` on a confirmed replay.
type AuditPayload struct {
	Action         string         `json:"action"`
	ObjectType     string         `json:"objectType"`
	ObjectID       string         `json:"objectId"`
	Outcome        string         `json:"outcome"`
	Purpose        string         `json:"purpose,omitempty"`
	ActorID        string         `json:"actorId"`
	TenantID       string         `json:"tenantId"`
	Schema         string         `json:"schema"`
	Consumer       string         `json:"consumer,omitempty"`
	From           string         `json:"from,omitempty"`
	To             string         `json:"to,omitempty"`
	PublishedCount int            `json:"publishedCount"`
	CountByTopic   map[string]int `json:"countByTopic"`
	Interrupted    bool           `json:"interrupted"`
	LastCreatedAt  string         `json:"lastCreatedAt,omitempty"`
	LastEventID    string         `json:"lastEventId,omitempty"`
}

// Replayer coordinates reading `<schema>.outbox_event`, validating against `outbox-sources.yaml`,
// checking `pg_partman` retention, publishing Kafka records identical to Debezium EventRouter,
// and emitting an audit event to `sys.audit.v1`.
type Replayer struct {
	registry *sources.Registry
	store    OutboxStore
	producer Producer
}

// NewReplayer creates a new Replayer.
func NewReplayer(registry *sources.Registry, store OutboxStore, producer Producer) (*Replayer, error) {
	if registry == nil {
		return nil, errors.New("replay: registry must not be nil")
	}
	if store == nil {
		return nil, errors.New("replay: store must not be nil")
	}
	return &Replayer{
		registry: registry,
		store:    store,
		producer: producer,
	}, nil
}

// TargetTopic returns the destination Kafka topic: `<topic>` normally, or `<topic>.replay.<consumer>`
// when a specific consumer is targeted (TL-04 OPS-24, NEN-022 §3).
func TargetTopic(baseTopic, consumer string) (string, error) {
	base := strings.TrimSpace(baseTopic)
	if base == "" {
		return "", fmt.Errorf("%w: empty topic", sources.ErrTopicNotAllowed)
	}
	cons := strings.TrimSpace(consumer)
	if cons == "" {
		return base, nil
	}
	if !consumerIdentRe.MatchString(cons) {
		return "", fmt.Errorf("%w: invalid consumer identifier %q", ErrInvalidFilter, consumer)
	}
	return base + ".replay." + cons, nil
}

// BuildKafkaRecord transforms an OutboxRow into a KafkaRecord using the exact Debezium Outbox
// EventRouter semantics configured in TL-03 and `outbox-sources.yaml`:
//   - Key: `event_key` (`tenantId:aggregateId`)
//   - Value: `payload` (raw Avro binary bytes, unmodified)
//   - Headers: `id`, `ce_id`, `ce_specversion`, `ce_source`, `ce_type`, `ce_time`,
//     `ce_datacontenttype`, `ce_tenantid`, `tenant_id`, `traceparent`, `ce_classification`,
//     `classification`, `apicurio.value.globalId`, `aggregate_type`, `aggregate_id`,
//     `aggregate_version`, `schema_version`, plus any extra headers in deterministic key order.
func BuildKafkaRecord(schema string, row OutboxRow, consumer string) (KafkaRecord, error) {
	destTopic, err := TargetTopic(row.Topic, consumer)
	if err != nil {
		return KafkaRecord{}, err
	}
	keyStr := row.EventKey
	if strings.TrimSpace(keyStr) == "" {
		keyStr = row.TenantID + ":" + row.AggregateID
	}

	// Copy payload bytes to guarantee immutability
	valCopy := make([]byte, len(row.Payload))
	copy(valCopy, row.Payload)

	hdrMap := make(map[string]string, len(row.Headers)+16)
	for k, v := range row.Headers {
		hdrMap[k] = v
	}

	// Authoritative column-backed headers (Debezium EventRouter fields.additional.placement)
	hdrMap["id"] = row.ID
	hdrMap["ce_id"] = row.ID
	hdrMap["ce_specversion"] = "1.0"
	hdrMap["ce_type"] = row.EventType
	hdrMap["ce_tenantid"] = row.TenantID
	hdrMap["tenant_id"] = row.TenantID
	hdrMap["aggregate_type"] = row.AggregateType
	hdrMap["aggregate_id"] = row.AggregateID
	hdrMap["aggregate_version"] = strconv.FormatInt(row.AggregateVersion, 10)
	schemaVer := row.SchemaVersion
	if schemaVer <= 0 {
		schemaVer = 1
	}
	hdrMap["schema_version"] = strconv.Itoa(schemaVer)
	if _, hasGlobalID := hdrMap["apicurio.value.globalId"]; !hasGlobalID {
		hdrMap["apicurio.value.globalId"] = strconv.Itoa(schemaVer)
	}
	if _, hasSource := hdrMap["ce_source"]; !hasSource {
		hdrMap["ce_source"] = "urn:viettel:okrkpi:" + strings.TrimSpace(schema)
	}
	if _, hasTime := hdrMap["ce_time"]; !hasTime && !row.CreatedAt.IsZero() {
		hdrMap["ce_time"] = row.CreatedAt.UTC().Format(time.RFC3339Nano)
	}
	if _, hasCT := hdrMap["ce_datacontenttype"]; !hasCT {
		hdrMap["ce_datacontenttype"] = "application/avro"
	}

	// Classification from headers JSONB ("classification" or "ce_classification")
	cls := hdrMap["classification"]
	if cls == "" {
		cls = hdrMap["ce_classification"]
	}
	if cls == "" {
		cls = "C1"
	}
	hdrMap["classification"] = cls
	hdrMap["ce_classification"] = cls

	// Order headers deterministically: canonical EventRouter headers first, then any custom headers sorted
	canonicalOrder := []string{
		"id",
		"ce_id",
		"ce_specversion",
		"ce_source",
		"ce_type",
		"ce_time",
		"ce_datacontenttype",
		"ce_tenantid",
		"tenant_id",
		"traceparent",
		"ce_classification",
		"classification",
		"apicurio.value.globalId",
		"aggregate_type",
		"aggregate_id",
		"aggregate_version",
		"schema_version",
	}

	seen := make(map[string]struct{}, len(hdrMap))
	headers := make([]KafkaHeader, 0, len(hdrMap))
	for _, k := range canonicalOrder {
		if v, ok := hdrMap[k]; ok && v != "" {
			headers = append(headers, KafkaHeader{Key: k, Value: []byte(v)})
			seen[k] = struct{}{}
		}
	}

	var extraKeys []string
	for k, v := range hdrMap {
		if _, already := seen[k]; !already && v != "" {
			extraKeys = append(extraKeys, k)
		}
	}
	sort.Strings(extraKeys)
	for _, k := range extraKeys {
		headers = append(headers, KafkaHeader{Key: k, Value: []byte(hdrMap[k])})
	}

	return KafkaRecord{
		Topic:   destTopic,
		Key:     []byte(keyStr),
		Value:   valCopy,
		Headers: headers,
	}, nil
}

// Run executes the outbox inspection (`DryRun: true`) or replay (`DryRun: false, Confirm: true`).
func (r *Replayer) Run(ctx context.Context, opts Options) (*Result, error) {
	if !opts.DryRun && !opts.Confirm {
		return nil, ErrConfirmRequired
	}
	if err := opts.Filter.Validate(r.registry); err != nil {
		return nil, err
	}
	if opts.Consumer != "" {
		if _, err := TargetTopic("test.topic.event.v1", opts.Consumer); err != nil {
			return nil, err
		}
	}
	if !opts.DryRun {
		if r.producer == nil {
			return nil, errors.New("replay: producer is required when --dry-run=false")
		}
		pCfg := r.producer.Config()
		if !pCfg.EnableIdempotence || strings.ToLower(strings.TrimSpace(pCfg.Acks)) != "all" {
			return nil, fmt.Errorf("%w: got enable.idempotence=%v, acks=%q", ErrUnsafeProducerConfig, pCfg.EnableIdempotence, pCfg.Acks)
		}
	}

	nowFn := opts.Now
	if nowFn == nil {
		nowFn = func() time.Time { return time.Now().UTC() }
	}
	now := nowFn().UTC()

	batchSize := opts.BatchSize
	if batchSize <= 0 {
		batchSize = DefaultBatchSize
	}

	res := &Result{
		DryRun:         opts.DryRun,
		Schema:         opts.Filter.Schema,
		TenantID:       opts.Filter.TenantID,
		TargetConsumer: opts.Consumer,
		CountByTopic:   make(map[string]int),
		CountByTenant:  make(map[string]int),
	}

	// Check pg_partman retention policy from partman.part_config (TL-03: 3 days, TL-04: 72 hours)
	retStr, err := r.store.RetentionPolicy(ctx, opts.Filter.Schema)
	if err != nil || strings.TrimSpace(retStr) == "" {
		retStr = r.registry.Defaults.PartmanRetention
		if retStr == "" {
			retStr = "3 days"
		}
	}
	retDur := ParseRetentionDuration(retStr)
	cutoff := now.Add(-retDur)
	res.RetentionPolicy = retStr
	res.RetentionDuration = retDur
	res.RetentionCutoff = cutoff

	if !opts.Filter.From.IsZero() && opts.Filter.From.Before(cutoff) {
		res.RetentionExceeded = true
		warning := fmt.Sprintf(
			"CẢNH BÁO: Mốc --from (%s) vượt quá thời hạn giữ phân vùng outbox (%s = %s, mốc cắt %s). Các phân vùng trước %s có thể đã được pg_partman dọn dẹp.",
			opts.Filter.From.UTC().Format(time.RFC3339),
			retStr,
			retDur.String(),
			cutoff.Format(time.RFC3339),
			cutoff.Format(time.RFC3339),
		)
		res.Warnings = append(res.Warnings, warning)
	}

	var minInterval time.Duration
	if opts.RateLimitPerSec > 0 && !opts.DryRun {
		minInterval = time.Second / time.Duration(opts.RateLimitPerSec)
	}
	var lastPublishTime time.Time

	afterCreatedAt := opts.Filter.AfterCreatedAt
	afterID := opts.Filter.AfterID

	for {
		if err := ctx.Err(); err != nil {
			res.Interrupted = true
			break
		}

		rows, err := r.store.QueryBatch(ctx, opts.Filter, afterCreatedAt, afterID, batchSize)
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				res.Interrupted = true
				break
			}
			return res, fmt.Errorf("replay: query outbox_event batch failed: %w", err)
		}
		if len(rows) == 0 {
			break
		}

		for _, row := range rows {
			if err := ctx.Err(); err != nil {
				res.Interrupted = true
				break
			}

			// Verify row topic is registered and allowed for this schema in outbox-sources.yaml
			if err := r.registry.ValidateSchemaAndTopic(opts.Filter.Schema, row.Topic); err != nil {
				return res, fmt.Errorf("replay: row id=%s has forbidden topic %q: %w", row.ID, row.Topic, err)
			}

			rec, err := BuildKafkaRecord(opts.Filter.Schema, row, opts.Consumer)
			if err != nil {
				return res, fmt.Errorf("replay: build kafka record for id=%s failed: %w", row.ID, err)
			}

			res.TotalMatched++
			res.CountByTopic[rec.Topic]++
			res.CountByTenant[row.TenantID]++

			if !opts.DryRun {
				if minInterval > 0 && !lastPublishTime.IsZero() {
					elapsed := time.Since(lastPublishTime)
					if elapsed < minInterval {
						sleepDur := minInterval - elapsed
						timer := time.NewTimer(sleepDur)
						select {
						case <-ctx.Done():
							timer.Stop()
							res.Interrupted = true
						case <-timer.C:
						}
						if res.Interrupted {
							break
						}
					}
				}

				if err := r.producer.Publish(ctx, rec); err != nil {
					if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
						res.Interrupted = true
						break
					}
					return res, fmt.Errorf("replay: publish id=%s to topic %s failed: %w", row.ID, rec.Topic, err)
				}
				lastPublishTime = time.Now()
				res.TotalPublished++
			}

			ts := row.CreatedAt.UTC()
			res.LastCreatedAt = &ts
			res.LastEventID = row.ID
			afterCreatedAt = &ts
			afterID = row.ID
		}

		if res.Interrupted || len(rows) < batchSize {
			break
		}
	}

	// Emit audit event to sys.audit.v1 whenever a confirmed replay runs
	if !opts.DryRun && opts.Confirm {
		auditID, err := r.emitAuditEvent(opts, res, now)
		if err != nil {
			return res, fmt.Errorf("replay: emit audit event to %s failed: %w", AuditTopic, err)
		}
		res.AuditEventID = auditID
	}

	return res, nil
}

func (r *Replayer) emitAuditEvent(opts Options, res *Result, now time.Time) (string, error) {
	auditID := newUUIDv4()
	actor := strings.TrimSpace(opts.ActorID)
	if actor == "" {
		actor = "system:outbox-replay"
	}
	outcome := "THANH_CONG"
	if res.Interrupted {
		outcome = "TAM_DUNG"
	}

	payload := AuditPayload{
		Action:         "PHAT_LAI_OUTBOX",
		ObjectType:     "outbox_event",
		ObjectID:       opts.Filter.Schema + ".outbox_event",
		Outcome:        outcome,
		Purpose:        opts.Reason,
		ActorID:        actor,
		TenantID:       opts.Filter.TenantID,
		Schema:         opts.Filter.Schema,
		Consumer:       opts.Consumer,
		PublishedCount: res.TotalPublished,
		CountByTopic:   res.CountByTopic,
		Interrupted:    res.Interrupted,
	}
	if !opts.Filter.From.IsZero() {
		payload.From = opts.Filter.From.UTC().Format(time.RFC3339)
	}
	if !opts.Filter.To.IsZero() {
		payload.To = opts.Filter.To.UTC().Format(time.RFC3339)
	}
	if res.LastCreatedAt != nil {
		payload.LastCreatedAt = res.LastCreatedAt.UTC().Format(time.RFC3339Nano)
		payload.LastEventID = res.LastEventID
	}

	rawBytes, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}

	auditRow := OutboxRow{
		ID:               auditID,
		TenantID:         opts.Filter.TenantID,
		AggregateType:    "outbox_event",
		AggregateID:      opts.Filter.Schema,
		AggregateVersion: 1,
		EventType:        AuditTopic,
		Topic:            AuditTopic,
		EventKey:         opts.Filter.TenantID + ":" + opts.Filter.Schema,
		Payload:          rawBytes,
		SchemaVersion:    1,
		Headers: map[string]string{
			"actor_id":           actor,
			"classification":     "C1",
			"ce_datacontenttype": "application/json",
		},
		CreatedAt: now,
	}
	rec, err := BuildKafkaRecord(opts.Filter.Schema, auditRow, "")
	if err != nil {
		return "", err
	}

	// Use a short background context so an interrupted replay still records its audit event
	auditCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := r.producer.Publish(auditCtx, rec); err != nil {
		return "", err
	}
	return auditID, nil
}

// ParseRetentionDuration parses a pg_partman retention string such as "3 days", "72 hours", or "72h".
func ParseRetentionDuration(s string) time.Duration {
	trimmed := strings.ToLower(strings.TrimSpace(s))
	if trimmed == "" {
		return DefaultRetentionHours * time.Hour
	}
	if d, err := time.ParseDuration(trimmed); err == nil && d > 0 {
		return d
	}
	parts := strings.Fields(trimmed)
	if len(parts) == 2 {
		n, err := strconv.Atoi(parts[0])
		if err == nil && n > 0 {
			switch {
			case strings.HasPrefix(parts[1], "day"):
				return time.Duration(n) * 24 * time.Hour
			case strings.HasPrefix(parts[1], "hour") || strings.HasPrefix(parts[1], "hr"):
				return time.Duration(n) * time.Hour
			}
		}
	}
	return DefaultRetentionHours * time.Hour
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
