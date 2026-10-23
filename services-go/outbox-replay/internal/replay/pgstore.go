package replay

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"vn.viettel.okrkpi/services-go/outbox-replay/internal/sources"
	"vn.viettel.okrkpi/services-go/vtplatform/db"
)

// Rows represents a multi-row SQL result set (compatible with pgx.Rows).
type Rows interface {
	Next() bool
	Scan(dest ...any) error
	Err() error
	Close()
}

// Querier represents a PostgreSQL connection or pool capable of executing SELECT queries (compatible with pgxpool.Pool).
type Querier interface {
	Query(ctx context.Context, sql string, args ...any) (Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) db.Row
}

// PGOutboxStore implements OutboxStore on top of a PostgreSQL connection (`Querier`).
type PGOutboxStore struct {
	querier Querier
}

// NewPGOutboxStore creates a PostgreSQL-backed OutboxStore.
func NewPGOutboxStore(querier Querier) (*PGOutboxStore, error) {
	if querier == nil {
		return nil, errors.New("replay: postgres querier must not be nil")
	}
	return &PGOutboxStore{querier: querier}, nil
}

// BuildSelectQuery constructs the parameterized SQL query and arguments for reading `<schema>.outbox_event`.
// uuid and jsonb columns are cast to text so they scan into the string fields of OutboxRow.
func BuildSelectQuery(filter Filter, afterCreatedAt *time.Time, afterID string, limit int) (string, []any) {
	var (
		conds []string
		args  []any
	)
	addArg := func(v any) string {
		args = append(args, v)
		return fmt.Sprintf("$%d", len(args))
	}

	conds = append(conds, "tenant_id = "+addArg(filter.TenantID))
	if !filter.From.IsZero() {
		conds = append(conds, "created_at >= "+addArg(filter.From.UTC()))
	}
	if !filter.To.IsZero() {
		conds = append(conds, "created_at <= "+addArg(filter.To.UTC()))
	}
	if filter.EventID != "" {
		conds = append(conds, "id = "+addArg(filter.EventID))
	}
	if filter.AggregateID != "" {
		conds = append(conds, "aggregate_id = "+addArg(filter.AggregateID))
	}
	if filter.EventType != "" {
		conds = append(conds, "event_type = "+addArg(filter.EventType))
	}
	if filter.Topic != "" {
		conds = append(conds, "topic = "+addArg(filter.Topic))
	}
	if afterCreatedAt != nil && afterID != "" {
		pTime := addArg(afterCreatedAt.UTC())
		pID := addArg(afterID)
		conds = append(conds, fmt.Sprintf("(created_at > %s OR (created_at = %s AND id > %s))", pTime, pTime, pID))
	}

	limitPlaceholder := addArg(limit)
	sql := fmt.Sprintf(
		`SELECT id::text, tenant_id::text, aggregate_type, aggregate_id::text, aggregate_version, event_type, topic, event_key, payload, schema_version, headers::text, created_at FROM %s.outbox_event WHERE %s ORDER BY created_at ASC, id ASC LIMIT %s`,
		filter.Schema,
		strings.Join(conds, " AND "),
		limitPlaceholder,
	)
	return sql, args
}

// QueryBatch executes the keyset-paginated query against `<schema>.outbox_event`.
func (s *PGOutboxStore) QueryBatch(ctx context.Context, filter Filter, afterCreatedAt *time.Time, afterID string, limit int) ([]OutboxRow, error) {
	sql, args := BuildSelectQuery(filter, afterCreatedAt, afterID, limit)
	rows, err := s.querier.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var result []OutboxRow
	for rows.Next() {
		var (
			r          OutboxRow
			headersRaw string
		)
		if err := rows.Scan(
			&r.ID,
			&r.TenantID,
			&r.AggregateType,
			&r.AggregateID,
			&r.AggregateVersion,
			&r.EventType,
			&r.Topic,
			&r.EventKey,
			&r.Payload,
			&r.SchemaVersion,
			&headersRaw,
			&r.CreatedAt,
		); err != nil {
			return nil, err
		}
		if headersRaw != "" {
			_ = json.Unmarshal([]byte(headersRaw), &r.Headers)
		}
		result = append(result, r)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return result, nil
}

// RetentionPolicy queries `partman.part_config` for the parent table `<schema>.outbox_event`.
func (s *PGOutboxStore) RetentionPolicy(ctx context.Context, schema string) (string, error) {
	parentTable := strings.TrimSpace(schema) + ".outbox_event"
	row := s.querier.QueryRow(
		ctx,
		`SELECT retention FROM partman.part_config WHERE parent_table = $1`,
		parentTable,
	)
	var retention string
	if err := row.Scan(&retention); err != nil {
		return "3 days", nil
	}
	if strings.TrimSpace(retention) == "" {
		return "3 days", nil
	}
	return retention, nil
}

// SimulatedPoolOutboxStore adapts `vtplatform/db.SimulatedPool` to `OutboxStore` so tests can
// write rows via `vtplatform/outbox.Writer` inside `db.InTenantTx` and replay them directly.
type SimulatedPoolOutboxStore struct {
	pool       *db.SimulatedPool
	retentions map[string]string
}

// NewSimulatedPoolOutboxStore creates an OutboxStore backed by `vtplatform/db.SimulatedPool`.
func NewSimulatedPoolOutboxStore(pool *db.SimulatedPool) *SimulatedPoolOutboxStore {
	return &SimulatedPoolOutboxStore{
		pool:       pool,
		retentions: make(map[string]string),
	}
}

// SetRetention sets the `partman.part_config` retention string for `<schema>.outbox_event`.
func (s *SimulatedPoolOutboxStore) SetRetention(schema, retention string) {
	s.retentions[strings.TrimSpace(schema)] = retention
}

// RetentionPolicy returns the `partman.part_config` retention string for `<schema>.outbox_event`.
func (s *SimulatedPoolOutboxStore) RetentionPolicy(_ context.Context, schema string) (string, error) {
	if r, ok := s.retentions[strings.TrimSpace(schema)]; ok && r != "" {
		return r, nil
	}
	return "3 days", nil
}

// QueryBatch reads committed outbox rows from `vtplatform/db.SimulatedPool` matching `filter`.
func (s *SimulatedPoolOutboxStore) QueryBatch(_ context.Context, filter Filter, afterCreatedAt *time.Time, afterID string, limit int) ([]OutboxRow, error) {
	all := s.pool.OutboxRows()
	var rows []OutboxRow
	for _, r := range all {
		if r.Schema != filter.Schema {
			continue
		}
		var hdrs map[string]string
		if r.HeadersJSON != "" {
			_ = json.Unmarshal([]byte(r.HeadersJSON), &hdrs)
		}
		rows = append(rows, OutboxRow{
			ID:               r.ID,
			TenantID:         r.TenantID,
			AggregateType:    r.AggregateType,
			AggregateID:      r.AggregateID,
			AggregateVersion: r.AggregateVersion,
			EventType:        r.EventType,
			Topic:            r.Topic,
			EventKey:         r.EventKey,
			Payload:          r.Payload,
			SchemaVersion:    r.SchemaVersion,
			Headers:          hdrs,
			CreatedAt:        r.CreatedAt,
		})
	}
	return filterAndPageRows(rows, filter, afterCreatedAt, afterID, limit), nil
}

// InMemoryOutboxStore is a thread-safe store supporting direct OutboxRow insertion and `partman.part_config`.
type InMemoryOutboxStore struct {
	mu         sync.RWMutex
	rowsBySc   map[string][]OutboxRow
	retentions map[string]string
}

// NewInMemoryOutboxStore creates an InMemoryOutboxStore with default `3 days` retention.
func NewInMemoryOutboxStore() *InMemoryOutboxStore {
	return &InMemoryOutboxStore{
		rowsBySc:   make(map[string][]OutboxRow),
		retentions: make(map[string]string),
	}
}

// SetRetention configures the simulated `partman.part_config.retention` for `<schema>.outbox_event`.
func (m *InMemoryOutboxStore) SetRetention(schema, retention string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.retentions[strings.TrimSpace(schema)] = retention
}

// Insert adds an OutboxRow into `<schema>.outbox_event`.
func (m *InMemoryOutboxStore) Insert(schema string, row OutboxRow) {
	m.mu.Lock()
	defer m.mu.Unlock()
	sc := strings.TrimSpace(schema)
	m.rowsBySc[sc] = append(m.rowsBySc[sc], row)
}

// RetentionPolicy returns the configured retention for `<schema>.outbox_event` (defaults to `"3 days"`).
func (m *InMemoryOutboxStore) RetentionPolicy(_ context.Context, schema string) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if ret, ok := m.retentions[strings.TrimSpace(schema)]; ok && ret != "" {
		return ret, nil
	}
	return "3 days", nil
}

// QueryBatch filters and orders rows by `(created_at ASC, id ASC)` using the exact semantics of `<schema>.outbox_event`.
func (m *InMemoryOutboxStore) QueryBatch(_ context.Context, filter Filter, afterCreatedAt *time.Time, afterID string, limit int) ([]OutboxRow, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return filterAndPageRows(m.rowsBySc[filter.Schema], filter, afterCreatedAt, afterID, limit), nil
}

func filterAndPageRows(src []OutboxRow, filter Filter, afterCreatedAt *time.Time, afterID string, limit int) []OutboxRow {
	var matched []OutboxRow
	for _, r := range src {
		if r.TenantID != filter.TenantID {
			continue
		}
		if !filter.From.IsZero() && r.CreatedAt.UTC().Before(filter.From.UTC()) {
			continue
		}
		if !filter.To.IsZero() && r.CreatedAt.UTC().After(filter.To.UTC()) {
			continue
		}
		if filter.EventID != "" && r.ID != filter.EventID {
			continue
		}
		if filter.AggregateID != "" && r.AggregateID != filter.AggregateID {
			continue
		}
		if filter.EventType != "" && r.EventType != filter.EventType {
			continue
		}
		if filter.Topic != "" && r.Topic != filter.Topic {
			continue
		}
		if afterCreatedAt != nil && afterID != "" {
			rt := r.CreatedAt.UTC()
			at := afterCreatedAt.UTC()
			if rt.Before(at) || (rt.Equal(at) && r.ID <= afterID) {
				continue
			}
		}
		matched = append(matched, r)
	}

	sort.Slice(matched, func(i, j int) bool {
		ti := matched[i].CreatedAt.UTC()
		tj := matched[j].CreatedAt.UTC()
		if !ti.Equal(tj) {
			return ti.Before(tj)
		}
		return matched[i].ID < matched[j].ID
	})

	if limit > 0 && len(matched) > limit {
		matched = matched[:limit]
	}
	return matched
}

// DebeziumEventRouterSimulate applies the Debezium Outbox `EventRouter` SMT rules declared in
// `outbox-sources.yaml` (`defaults.eventRouter`) to an `OutboxRow`, producing the reference
// `KafkaRecord` for byte-for-byte verification (NEN-022 §6).
func DebeziumEventRouterSimulate(cfg sources.EventRouterConfig, schema string, row OutboxRow, consumer string) (KafkaRecord, error) {
	var topic string
	if cfg.TopicField == "topic" || cfg.TopicField == "" {
		topic = row.Topic
	}
	if consumer != "" {
		topic = topic + ".replay." + consumer
	}

	var key string
	if cfg.KeyField == "event_key" || cfg.KeyField == "" {
		key = row.EventKey
		if key == "" {
			key = row.TenantID + ":" + row.AggregateID
		}
	}

	payloadCopy := make([]byte, len(row.Payload))
	copy(payloadCopy, row.Payload)

	hdrs := make(map[string]string, len(row.Headers)+16)
	for k, v := range row.Headers {
		hdrs[k] = v
	}

	for _, hm := range cfg.HeaderFields {
		switch hm.Column {
		case "id":
			hdrs[hm.Header] = row.ID
			hdrs["id"] = row.ID
		case "event_type":
			hdrs[hm.Header] = row.EventType
		case "tenant_id":
			hdrs[hm.Header] = row.TenantID
			hdrs["tenant_id"] = row.TenantID
		case "aggregate_version":
			hdrs[hm.Header] = strconv.FormatInt(row.AggregateVersion, 10)
		case "schema_version":
			sv := row.SchemaVersion
			if sv <= 0 {
				sv = 1
			}
			if _, exists := hdrs[hm.Header]; !exists {
				hdrs[hm.Header] = strconv.Itoa(sv)
			}
			hdrs["schema_version"] = strconv.Itoa(sv)
		}
		if hm.JSONBKey != "" {
			if val, ok := row.Headers[hm.JSONBKey]; ok && val != "" {
				hdrs[hm.Header] = val
				hdrs[hm.JSONBKey] = val
			}
		}
	}

	hdrs["ce_specversion"] = "1.0"
	hdrs["aggregate_type"] = row.AggregateType
	hdrs["aggregate_id"] = row.AggregateID
	if _, ok := hdrs["ce_source"]; !ok {
		hdrs["ce_source"] = "urn:viettel:okrkpi:" + strings.TrimSpace(schema)
	}
	if _, ok := hdrs["ce_time"]; !ok && !row.CreatedAt.IsZero() {
		hdrs["ce_time"] = row.CreatedAt.UTC().Format(time.RFC3339Nano)
	}
	if _, ok := hdrs["ce_datacontenttype"]; !ok {
		hdrs["ce_datacontenttype"] = "application/avro"
	}
	if cls := hdrs["classification"]; cls != "" {
		hdrs["ce_classification"] = cls
	} else if cls := hdrs["ce_classification"]; cls != "" {
		hdrs["classification"] = cls
	} else {
		hdrs["classification"] = "C1"
		hdrs["ce_classification"] = "C1"
	}

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
	seen := make(map[string]struct{}, len(hdrs))
	var headerSlice []KafkaHeader
	for _, k := range canonicalOrder {
		if v, ok := hdrs[k]; ok && v != "" {
			headerSlice = append(headerSlice, KafkaHeader{Key: k, Value: []byte(v)})
			seen[k] = struct{}{}
		}
	}
	var extra []string
	for k, v := range hdrs {
		if _, ok := seen[k]; !ok && v != "" {
			extra = append(extra, k)
		}
	}
	sort.Strings(extra)
	for _, k := range extra {
		headerSlice = append(headerSlice, KafkaHeader{Key: k, Value: []byte(hdrs[k])})
	}

	return KafkaRecord{
		Topic:   topic,
		Key:     []byte(key),
		Value:   payloadCopy,
		Headers: headerSlice,
	}, nil
}
