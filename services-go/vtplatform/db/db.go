// Package db provides transaction-scoped PostgreSQL tenant context management
// (SELECT set_config('app.tenant_id', $1, true)) safe for PgBouncer transaction pooling
// (TL-03 L706-734). Session-level tenant assignment is strictly forbidden.
package db

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"

	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

const (
	// SetLocalTenantSQL is the canonical query executed immediately after BeginTx (TL-03 L706-734).
	SetLocalTenantSQL = "SELECT set_config('app.tenant_id', $1, true)"
)

var (
	// ErrSessionTenantForbidden is returned if any code attempts to set app.tenant_id at session scope.
	ErrSessionTenantForbidden = errors.New("db: setting app.tenant_id at session scope is forbidden with PgBouncer transaction pooling")
	// ErrRLSViolation is returned when a row operation violates FORCE ROW LEVEL SECURITY on tenant_id.
	ErrRLSViolation = errors.New("db: new row violates row-level security policy for table (SQLSTATE 42501)")
	// ErrTxClosed is returned when operations are invoked on a committed or rolled-back transaction.
	ErrTxClosed = errors.New("db: transaction is already closed")

	sessionTenantSetPattern = regexp.MustCompile(
		`(?is)\bset\s+(?:session\s+)?app\.tenant_id\b|\bset_config\s*\(\s*'app\.tenant_id'\s*,[^,]+,\s*false\s*\)`,
	)
)

// CommandTag represents the result metadata of an SQL DML command (compatible with pgconn.CommandTag).
type CommandTag struct {
	sql          string
	rowsAffected int64
}

// NewCommandTag creates a CommandTag with the given SQL tag and affected row count.
func NewCommandTag(sql string, rowsAffected int64) CommandTag {
	return CommandTag{sql: sql, rowsAffected: rowsAffected}
}

// RowsAffected returns the number of rows affected by the command.
func (ct CommandTag) RowsAffected() int64 {
	return ct.rowsAffected
}

// String returns the command tag string.
func (ct CommandTag) String() string {
	return ct.sql
}

// Row represents a single result row (compatible with pgx.Row).
type Row interface {
	Scan(dest ...any) error
}

// Tx represents a database transaction (compatible with pgx.Tx subset used by services).
type Tx interface {
	Exec(ctx context.Context, sql string, args ...any) (CommandTag, error)
	QueryRow(ctx context.Context, sql string, args ...any) Row
	Commit(ctx context.Context) error
	Rollback(ctx context.Context) error
}

// TxBeginner represents a connection pool capable of starting a transaction (compatible with pgxpool.Pool).
type TxBeginner interface {
	Begin(ctx context.Context) (Tx, error)
}

// ValidateSQLNoSessionTenantSet rejects any SQL statement attempting to set app.tenant_id at session scope.
func ValidateSQLNoSessionTenantSet(sql string) error {
	if sessionTenantSetPattern.MatchString(sql) {
		return ErrSessionTenantForbidden
	}
	return nil
}

type safeTx struct {
	inner    Tx
	tenantID string
}

func (s *safeTx) Exec(ctx context.Context, sql string, args ...any) (CommandTag, error) {
	if err := ValidateSQLNoSessionTenantSet(sql); err != nil {
		return CommandTag{}, err
	}
	return s.inner.Exec(ctx, sql, args...)
}

func (s *safeTx) QueryRow(ctx context.Context, sql string, args ...any) Row {
	if err := ValidateSQLNoSessionTenantSet(sql); err != nil {
		return errRow{err: err}
	}
	return s.inner.QueryRow(ctx, sql, args...)
}

func (s *safeTx) Commit(ctx context.Context) error {
	return s.inner.Commit(ctx)
}

func (s *safeTx) Rollback(ctx context.Context) error {
	return s.inner.Rollback(ctx)
}

type errRow struct {
	err error
}

func (r errRow) Scan(_ ...any) error {
	return r.err
}

// InTx opens a transaction from pool, sets app.tenant_id locally for the transaction
// via `SELECT set_config('app.tenant_id', $1, true)`, executes fn, and commits or rolls back.
// There is intentionally NO API to set app.tenant_id at session scope.
func InTx(ctx context.Context, pool TxBeginner, fn func(ctx context.Context, tx Tx) error) (err error) {
	if pool == nil {
		return errors.New("db: pool must not be nil")
	}
	if fn == nil {
		return errors.New("db: transaction callback fn must not be nil")
	}

	tenantID, err := tenant.MustFromContext(ctx)
	if err != nil {
		return err
	}

	rawTx, err := pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("db: begin tx failed: %w", err)
	}

	defer func() {
		if p := recover(); p != nil {
			_ = rawTx.Rollback(ctx)
			panic(p)
		}
	}()

	if _, err := rawTx.Exec(ctx, SetLocalTenantSQL, tenantID); err != nil {
		_ = rawTx.Rollback(ctx)
		return fmt.Errorf("db: failed to set local app.tenant_id: %w", err)
	}

	wrapped := &safeTx{inner: rawTx, tenantID: tenantID}
	if err := fn(ctx, wrapped); err != nil {
		_ = rawTx.Rollback(ctx)
		return err
	}

	if err := rawTx.Commit(ctx); err != nil {
		_ = rawTx.Rollback(ctx)
		return fmt.Errorf("db: commit tx failed: %w", err)
	}
	return nil
}

// InTenantTx binds tenantID into ctx and executes fn inside a transaction-scoped tenant context via InTx.
func InTenantTx(ctx context.Context, pool TxBeginner, tenantID string, fn func(ctx context.Context, tx Tx) error) error {
	tCtx, err := tenant.WithTenantID(ctx, tenantID)
	if err != nil {
		return err
	}
	return InTx(tCtx, pool, fn)
}

// =============================================================================
// Simulated PgBouncer Transaction-Mode Pool + PostgreSQL FORCE RLS Engine
// =============================================================================

// RLSItem represents a business row stored in a simulated RLS-protected table.
type RLSItem struct {
	ID       string
	TenantID string
	Title    string
}

// OutboxRow represents a row stored in `<schema>.outbox_event`.
type OutboxRow struct {
	Schema           string
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
	HeadersJSON      string
	CreatedAt        time.Time
}

// InboxProcessedRow represents a row in `<schema>.inbox_processed`.
type InboxProcessedRow struct {
	Schema         string
	EventID        string
	Consumer       string
	ProcessedAt    time.Time
	TenantID       string
	SourceTopic    string
	KafkaPartition int
	KafkaOffset    int64
}

// InboxVersionRow represents a row in `<schema>.inbox_aggregate_version`.
type InboxVersionRow struct {
	Schema        string
	Consumer      string
	AggregateType string
	AggregateID   string
	TenantID      string
	LastVersion   int64
	LastEventID   string
	UpdatedAt     time.Time
}

type storageState struct {
	items         []RLSItem
	outbox        []OutboxRow
	inbox         map[string]InboxProcessedRow
	inboxVersions map[string]InboxVersionRow
}

func (s *storageState) clone() storageState {
	itemsCopy := make([]RLSItem, len(s.items))
	copy(itemsCopy, s.items)

	outboxCopy := make([]OutboxRow, len(s.outbox))
	for i, r := range s.outbox {
		pCopy := make([]byte, len(r.Payload))
		copy(pCopy, r.Payload)
		r.Payload = pCopy
		outboxCopy[i] = r
	}

	inboxCopy := make(map[string]InboxProcessedRow, len(s.inbox))
	for k, v := range s.inbox {
		inboxCopy[k] = v
	}

	verCopy := make(map[string]InboxVersionRow, len(s.inboxVersions))
	for k, v := range s.inboxVersions {
		verCopy[k] = v
	}

	return storageState{
		items:         itemsCopy,
		outbox:        outboxCopy,
		inbox:         inboxCopy,
		inboxVersions: verCopy,
	}
}

type physicalConn struct {
	id            int
	sessionTenant string // Simulates session-level GUC that would leak across PgBouncer tx mode if ever set
}

// SimulatedPool simulates a PostgreSQL 17 database behind PgBouncer in `pool_mode = transaction`
// (`default_pool_size = 1` by default) with FORCE ROW LEVEL SECURITY on all tables.
type SimulatedPool struct {
	conns chan *physicalConn
	mu    sync.Mutex
	state storageState
}

// NewSimulatedPool creates a SimulatedPool with poolSize physical connections (use 1 to simulate default_pool_size=1).
func NewSimulatedPool(poolSize int) *SimulatedPool {
	if poolSize <= 0 {
		poolSize = 1
	}
	ch := make(chan *physicalConn, poolSize)
	for i := 0; i < poolSize; i++ {
		ch <- &physicalConn{id: i + 1}
	}
	return &SimulatedPool{
		conns: ch,
		state: storageState{
			inbox:         make(map[string]InboxProcessedRow),
			inboxVersions: make(map[string]InboxVersionRow),
		},
	}
}

// Begin acquires a physical connection from the pool and starts a transaction.
func (p *SimulatedPool) Begin(ctx context.Context) (Tx, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case conn := <-p.conns:
		p.mu.Lock()
		working := p.state.clone()
		p.mu.Unlock()
		return &simulatedTx{
			pool:        p,
			conn:        conn,
			localTenant: "",
			working:     working,
		}, nil
	}
}

// OutboxRows returns a snapshot of all committed outbox_event rows across all tenants (like Debezium WAL reader).
func (p *SimulatedPool) OutboxRows() []OutboxRow {
	p.mu.Lock()
	defer p.mu.Unlock()
	cp := p.state.clone()
	return cp.outbox
}

// InboxProcessedRows returns a snapshot of all committed inbox_processed rows.
func (p *SimulatedPool) InboxProcessedRows() []InboxProcessedRow {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]InboxProcessedRow, 0, len(p.state.inbox))
	for _, r := range p.state.inbox {
		out = append(out, r)
	}
	return out
}

// InboxVersionRows returns a snapshot of all committed inbox_aggregate_version rows.
func (p *SimulatedPool) InboxVersionRows() []InboxVersionRow {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]InboxVersionRow, 0, len(p.state.inboxVersions))
	for _, r := range p.state.inboxVersions {
		out = append(out, r)
	}
	return out
}

type simulatedTx struct {
	pool        *SimulatedPool
	conn        *physicalConn
	localTenant string
	working     storageState
	closed      bool
}

func (tx *simulatedTx) effectiveTenant() string {
	if tx.localTenant != "" {
		return tx.localTenant
	}
	return tx.conn.sessionTenant
}

func (tx *simulatedTx) Exec(_ context.Context, sql string, args ...any) (CommandTag, error) {
	if tx.closed {
		return CommandTag{}, ErrTxClosed
	}
	norm := strings.ToLower(strings.TrimSpace(sql))

	// 1. set_config('app.tenant_id', $1, is_local)
	if strings.Contains(norm, "set_config('app.tenant_id'") {
		if len(args) < 1 {
			return CommandTag{}, errors.New("db: missing tenant_id argument to set_config")
		}
		tid, _ := args[0].(string)
		isLocal := strings.Contains(norm, "true")
		if len(args) >= 2 {
			if b, ok := args[1].(bool); ok {
				isLocal = b
			}
		}
		if isLocal {
			tx.localTenant = tid
		} else {
			tx.conn.sessionTenant = tid
		}
		return NewCommandTag("SELECT 1", 1), nil
	}

	// 2. INSERT INTO rls_items (id, tenant_id, title) VALUES ($1, $2, $3)
	if strings.HasPrefix(norm, "insert into") && strings.Contains(norm, "rls_items") {
		if len(args) != 3 {
			return CommandTag{}, errors.New("db: expected 3 args for rls_items insert")
		}
		item := RLSItem{
			ID:       fmt.Sprint(args[0]),
			TenantID: fmt.Sprint(args[1]),
			Title:    fmt.Sprint(args[2]),
		}
		if tx.effectiveTenant() == "" || item.TenantID != tx.effectiveTenant() {
			return CommandTag{}, ErrRLSViolation
		}
		tx.working.items = append(tx.working.items, item)
		return NewCommandTag("INSERT 0 1", 1), nil
	}

	// 3. INSERT INTO <schema>.outbox_event (...)
	if strings.HasPrefix(norm, "insert into") && strings.Contains(norm, "outbox_event") {
		if len(args) != 12 {
			return CommandTag{}, fmt.Errorf("db: expected 12 args for outbox_event insert, got %d", len(args))
		}
		tid := fmt.Sprint(args[1])
		if tx.effectiveTenant() == "" || tid != tx.effectiveTenant() {
			return CommandTag{}, ErrRLSViolation
		}
		aggVer, _ := toInt64(args[4])
		schemaVer, _ := toInt(args[9])
		payloadBytes, _ := args[8].([]byte)
		var headersStr string
		switch h := args[10].(type) {
		case string:
			headersStr = h
		case []byte:
			headersStr = string(h)
		default:
			b, _ := json.Marshal(h)
			headersStr = string(b)
		}
		createdAt, ok := args[11].(time.Time)
		if !ok {
			createdAt = time.Now().UTC()
		}
		row := OutboxRow{
			Schema:           extractSchema(norm, "outbox_event"),
			ID:               fmt.Sprint(args[0]),
			TenantID:         tid,
			AggregateType:    fmt.Sprint(args[2]),
			AggregateID:      fmt.Sprint(args[3]),
			AggregateVersion: aggVer,
			EventType:        fmt.Sprint(args[5]),
			Topic:            fmt.Sprint(args[6]),
			EventKey:         fmt.Sprint(args[7]),
			Payload:          payloadBytes,
			SchemaVersion:    schemaVer,
			HeadersJSON:      headersStr,
			CreatedAt:        createdAt,
		}
		tx.working.outbox = append(tx.working.outbox, row)
		return NewCommandTag("INSERT 0 1", 1), nil
	}

	// 4. INSERT INTO <schema>.inbox_processed (...) ON CONFLICT (consumer, event_id) DO NOTHING
	if strings.HasPrefix(norm, "insert into") && strings.Contains(norm, "inbox_processed") {
		if len(args) != 7 {
			return CommandTag{}, fmt.Errorf("db: expected 7 args for inbox_processed insert, got %d", len(args))
		}
		eventID := fmt.Sprint(args[0])
		consumer := fmt.Sprint(args[1])
		processedAt, ok := args[2].(time.Time)
		if !ok {
			processedAt = time.Now().UTC()
		}
		tid := fmt.Sprint(args[3])
		if tx.effectiveTenant() == "" || tid != tx.effectiveTenant() {
			return CommandTag{}, ErrRLSViolation
		}
		sourceTopic := fmt.Sprint(args[4])
		part, _ := toInt(args[5])
		offset, _ := toInt64(args[6])
		schema := extractSchema(norm, "inbox_processed")
		key := schema + "|" + consumer + "|" + eventID
		if _, exists := tx.working.inbox[key]; exists {
			return NewCommandTag("INSERT 0 0", 0), nil
		}
		tx.working.inbox[key] = InboxProcessedRow{
			Schema:         schema,
			EventID:        eventID,
			Consumer:       consumer,
			ProcessedAt:    processedAt,
			TenantID:       tid,
			SourceTopic:    sourceTopic,
			KafkaPartition: part,
			KafkaOffset:    offset,
		}
		return NewCommandTag("INSERT 0 1", 1), nil
	}

	// 5. INSERT INTO <schema>.inbox_aggregate_version (...) ON CONFLICT DO UPDATE WHERE last_version < EXCLUDED.last_version
	if strings.HasPrefix(norm, "insert into") && strings.Contains(norm, "inbox_aggregate_version") {
		if len(args) != 7 {
			return CommandTag{}, fmt.Errorf("db: expected 7 args for inbox_aggregate_version upsert, got %d", len(args))
		}
		consumer := fmt.Sprint(args[0])
		aggType := fmt.Sprint(args[1])
		aggID := fmt.Sprint(args[2])
		tid := fmt.Sprint(args[3])
		if tx.effectiveTenant() == "" || tid != tx.effectiveTenant() {
			return CommandTag{}, ErrRLSViolation
		}
		ver, _ := toInt64(args[4])
		eventID := fmt.Sprint(args[5])
		updatedAt, ok := args[6].(time.Time)
		if !ok {
			updatedAt = time.Now().UTC()
		}
		schema := extractSchema(norm, "inbox_aggregate_version")
		key := schema + "|" + consumer + "|" + aggType + "|" + aggID
		if existing, exists := tx.working.inboxVersions[key]; exists {
			if existing.TenantID != tx.effectiveTenant() {
				return CommandTag{}, ErrRLSViolation
			}
			if ver <= existing.LastVersion {
				return NewCommandTag("INSERT 0 0", 0), nil
			}
		}
		tx.working.inboxVersions[key] = InboxVersionRow{
			Schema:        schema,
			Consumer:      consumer,
			AggregateType: aggType,
			AggregateID:   aggID,
			TenantID:      tid,
			LastVersion:   ver,
			LastEventID:   eventID,
			UpdatedAt:     updatedAt,
		}
		return NewCommandTag("INSERT 0 1", 1), nil
	}

	return NewCommandTag("OK", 1), nil
}

func (tx *simulatedTx) QueryRow(_ context.Context, sql string, _ ...any) Row {
	if tx.closed {
		return errRow{err: ErrTxClosed}
	}
	norm := strings.ToLower(strings.TrimSpace(sql))
	if strings.Contains(norm, "current_setting('app.tenant_id'") {
		return singleValueRow{val: tx.effectiveTenant()}
	}
	if strings.Contains(norm, "count(*)") && strings.Contains(norm, "rls_items") {
		var count int64
		eff := tx.effectiveTenant()
		if eff != "" {
			for _, it := range tx.working.items {
				if it.TenantID == eff {
					count++
				}
			}
		}
		return singleValueRow{val: count}
	}
	if strings.Contains(norm, "count(*)") && strings.Contains(norm, "outbox_event") {
		var count int64
		eff := tx.effectiveTenant()
		if eff != "" {
			for _, ob := range tx.working.outbox {
				if ob.TenantID == eff {
					count++
				}
			}
		}
		return singleValueRow{val: count}
	}
	return errRow{err: fmt.Errorf("db: unsupported query in simulatedTx: %s", sql)}
}

func (tx *simulatedTx) Commit(_ context.Context) error {
	if tx.closed {
		return ErrTxClosed
	}
	tx.closed = true
	tx.localTenant = "" // Transaction-local setting cleared on commit
	tx.pool.mu.Lock()
	tx.pool.state = tx.working
	tx.pool.mu.Unlock()
	tx.pool.conns <- tx.conn
	return nil
}

func (tx *simulatedTx) Rollback(_ context.Context) error {
	if tx.closed {
		return nil
	}
	tx.closed = true
	tx.localTenant = "" // Transaction-local setting cleared on rollback
	tx.pool.conns <- tx.conn
	return nil
}

type singleValueRow struct {
	val any
}

func (r singleValueRow) Scan(dest ...any) error {
	if len(dest) != 1 {
		return errors.New("db: expected 1 scan destination")
	}
	switch d := dest[0].(type) {
	case *string:
		*d = fmt.Sprint(r.val)
		return nil
	case *int64:
		v, _ := toInt64(r.val)
		*d = v
		return nil
	case *int:
		v, _ := toInt(r.val)
		*d = v
		return nil
	default:
		return fmt.Errorf("db: unsupported scan destination %T", dest[0])
	}
}

func extractSchema(normSQL, table string) string {
	idx := strings.Index(normSQL, "."+table)
	if idx <= 0 {
		return "public"
	}
	prefix := normSQL[:idx]
	fields := strings.Fields(prefix)
	if len(fields) == 0 {
		return "public"
	}
	return strings.Trim(fields[len(fields)-1], `"`)
}

func toInt64(v any) (int64, bool) {
	switch x := v.(type) {
	case int64:
		return x, true
	case int:
		return int64(x), true
	case int32:
		return int64(x), true
	default:
		return 0, false
	}
}

func toInt(v any) (int, bool) {
	switch x := v.(type) {
	case int:
		return x, true
	case int64:
		return int(x), true
	case int32:
		return int(x), true
	default:
		return 0, false
	}
}
