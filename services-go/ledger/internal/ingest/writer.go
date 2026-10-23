package ingest

import (
	"bytes"
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/partition"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

// AppendedEntry là một bản ghi vừa ghi vào ledger.ledger_entry.
type AppendedEntry struct {
	ID             uuid.UUID
	TenantID       uuid.UUID
	RecordedMonth  int
	ChainPartition int16
	SeqNo          int64
	EventID        uuid.UUID
	EventType      string
	PayloadHash    []byte
	MetadataHash   []byte
	PrevHash       []byte
	EntryHash      []byte
	OccurredAt     time.Time
	RecordedAt     time.Time
}

// BatchResult là kết quả một lô ghi.
type BatchResult struct {
	TenantID          uuid.UUID
	ChainPartition    int16
	Appended          []AppendedEntry
	DuplicatesSkipped int
	LastSeqNo         int64
	LastHash          []byte
	Duration          time.Duration
}

// TenantMovedInCmd là lệnh quản trị ghi TENANT_MOVED_IN ở cell đích khi di trú tenant (TL-01 di trú
// tenant giữa các cell): bản ghi đầu chuỗi nối tiếp entry_hash cuối của chuỗi tương ứng ở cell nguồn.
type TenantMovedInCmd struct {
	TenantID            uuid.UUID
	ChainPartition      int16
	OriginCell          string
	LastSealID          uuid.UUID
	OriginLastEntryHash []byte
	OriginLastSeqNo     int64
	AdminActorID        uuid.UUID
	AuditReason         string
}

// IncidentRecoveryCmd là lệnh quản trị ghi bản ghi "đánh dấu sự cố" trên chuỗi đang bị khoá, mở khoá và
// mở phân đoạn mới tham chiếu phong bì niêm phong hợp lệ cuối (TL-02b QT-13 "Ngoại lệ và xử lý").
type IncidentRecoveryCmd struct {
	TenantID        uuid.UUID
	ChainPartition  int16
	LastValidSealID uuid.UUID
	IncidentRef     string
	AdminActorID    uuid.UUID
	AuditReason     string
}

// Ingester ghi lô chuỗi băm vào ledger.ledger_entry và ledger.chain_head.
type Ingester struct {
	pool *pgxpool.Pool
	otel *otel.Provider
	log  *slog.Logger
	now  func() time.Time

	partMu  sync.Mutex
	ensured map[string]bool

	stats *stats
}

// NewIngester tạo bộ ghi với pool của vai trò svc_ledger.
func NewIngester(pool *pgxpool.Pool, otelProv *otel.Provider, log *slog.Logger) *Ingester {
	if log == nil {
		log = slog.Default()
	}
	return &Ingester{
		pool:    pool,
		otel:    otelProv,
		log:     log,
		now:     time.Now,
		ensured: map[string]bool{},
		stats:   newStats(),
	}
}

func (ing *Ingester) count(name string, n int) {
	if ing.otel != nil && n > 0 {
		ing.otel.AddCounter(name, int64(n))
	}
}

// ensurePartitions tạo phân vùng lá tháng ghi và tháng kế tiếp cho tenant (một lần mỗi tiến trình).
func (ing *Ingester) ensurePartitions(ctx context.Context, tenantID uuid.UUID, month int) error {
	key := fmt.Sprintf("%s:%d", tenantID, month)
	// Giữ khoá suốt lúc tạo: fn_ensure_tenant_partitions không chịu được hai lời gọi song song cho cùng
	// tenant mới (42P07). Tiến trình khác tạo cùng lúc thì gọi lại một lần, lần sau thấy đã có.
	ing.partMu.Lock()
	defer ing.partMu.Unlock()
	if ing.ensured[key] {
		return nil
	}
	next, err := partition.NextRecordedMonth(month)
	if err != nil {
		return err
	}
	_, err = partition.EnsureTenantPartitions(ctx, ing.pool, tenantID, month, next)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "42P07" {
		_, err = partition.EnsureTenantPartitions(ctx, ing.pool, tenantID, month, next)
	}
	if err != nil {
		return err
	}
	ing.ensured[key] = true
	return nil
}

// AppendBatch ghi một lô tối đa 200 sự kiện của cùng (tenant_id, chain_partition) trong một giao dịch:
// khoá dòng chain_head FOR UPDATE, bỏ qua event_id đã có, tính metadata_hash và entry_hash nối tiếp,
// chèn lô, cập nhật chain_head. Chuỗi đang khoá ghi trả ErrPartitionLocked.
func (ing *Ingester) AppendBatch(ctx context.Context, tenantID uuid.UUID, chain int16, events []EventInput) (*BatchResult, error) {
	return ing.append(ctx, tenantID, chain, events, false)
}

func (ing *Ingester) append(ctx context.Context, tenantID uuid.UUID, chain int16, events []EventInput, recovery bool) (*BatchResult, error) {
	res := &BatchResult{TenantID: tenantID, ChainPartition: chain}
	if len(events) == 0 {
		return res, nil
	}
	if len(events) > MaxBatchSize {
		return nil, fmt.Errorf("ingest: lô %d bản ghi vượt giới hạn %d", len(events), MaxBatchSize)
	}
	if chain < 0 || chain >= canon.ChainPartitionCount {
		return nil, fmt.Errorf("ingest: chain_partition %d ngoài miền 0..63", chain)
	}
	start := ing.now()
	recordedAt := start.UTC().Truncate(time.Microsecond)
	month := partition.RecordedMonth(recordedAt)

	valid := make([]EventInput, 0, len(events))
	seen := make(map[uuid.UUID]bool, len(events))
	for _, ev := range events {
		if err := checkEvent(ev, tenantID, chain); err != nil {
			return nil, err
		}
		if seen[ev.EventID] {
			res.DuplicatesSkipped++
			continue
		}
		seen[ev.EventID] = true
		valid = append(valid, ev)
	}
	if err := ing.ensurePartitions(ctx, tenantID, month); err != nil {
		return nil, err
	}

	tx, err := ing.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, fmt.Errorf("ingest: mở giao dịch: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenantID.String()); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO ledger.chain_head (tenant_id, chain_partition, last_seq_no, last_hash, updated_at)
		 VALUES ($1, $2, 0, $3, $4) ON CONFLICT (tenant_id, chain_partition) DO NOTHING`,
		tenantID, chain, canon.ZeroHash[:], recordedAt); err != nil {
		return nil, fmt.Errorf("ingest: khởi tạo chain_head: %w", err)
	}
	var (
		lastSeq  int64
		lastHash []byte
		locked   bool
		reason   string
	)
	if err := tx.QueryRow(ctx,
		`SELECT last_seq_no, last_hash, is_locked, COALESCE(locked_reason, '')
		 FROM ledger.chain_head WHERE tenant_id = $1 AND chain_partition = $2 FOR UPDATE`,
		tenantID, chain).Scan(&lastSeq, &lastHash, &locked, &reason); err != nil {
		return nil, fmt.Errorf("ingest: khoá chain_head: %w", err)
	}
	if locked && !recovery {
		return nil, fmt.Errorf("%w (tenant=%s, chuỗi=%d, lý do=%s)", ErrPartitionLocked, tenantID, chain, reason)
	}
	if recovery && !locked {
		return nil, errors.New("ingest: chỉ ghi DANH_DAU_SU_CO trên chuỗi đang bị khoá ghi")
	}

	ids := make([]uuid.UUID, len(valid))
	for i, ev := range valid {
		ids[i] = ev.EventID
	}
	existing := map[uuid.UUID]bool{}
	rows, err := tx.Query(ctx,
		`SELECT event_id FROM ledger.ledger_entry WHERE tenant_id = $1 AND event_id = ANY($2)`, tenantID, ids)
	if err != nil {
		return nil, fmt.Errorf("ingest: kiểm event_id đã ghi: %w", err)
	}
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		existing[id] = true
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}

	var prev [32]byte
	copy(prev[:], lastHash)
	seq := lastSeq
	batch := &pgx.Batch{}
	appended := make([]AppendedEntry, 0, len(valid))
	for _, ev := range valid {
		if existing[ev.EventID] {
			res.DuplicatesSkipped++
			continue
		}
		if ev.EventType == EventTypeTenantMovedIn {
			// Chuỗi ở cell đích nối tiếp cell nguồn: seq_no và prev_hash tiếp từ bản ghi cuối ở nguồn.
			if seq != 0 || len(ev.OriginLastEntryHash) != 32 || ev.OriginLastSeqNo < 0 {
				return nil, errors.New("ingest: TENANT_MOVED_IN chỉ ghi lên chuỗi chưa có bản ghi, cần entry_hash và seq_no cuối của cell nguồn")
			}
			seq = ev.OriginLastSeqNo
			copy(prev[:], ev.OriginLastEntryHash)
		}
		e, err := ing.entry(ev, tenantID, chain, seq+1, prev, recordedAt)
		if err != nil {
			return nil, err
		}
		meta, err := canon.CanonicalizeValue(metadataOf(ev))
		if err != nil {
			return nil, fmt.Errorf("ingest: chuẩn hoá metadata sự kiện %s: %w", ev.EventID, err)
		}
		batch.Queue(`INSERT INTO ledger.ledger_entry (
				tenant_id, recorded_month, chain_partition, seq_no, id, event_id, event_type,
				aggregate_type, aggregate_id, actor_id, actor_kind, occurred_at, recorded_at,
				payload_hash, metadata, metadata_hash, prev_hash, entry_hash)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15::jsonb,$16,$17,$18)
			ON CONFLICT (tenant_id, recorded_month, event_id) DO NOTHING`,
			tenantID, month, chain, e.SeqNo, e.ID, ev.EventID, ev.EventType,
			ev.AggregateType, ev.AggregateID, ev.ActorID, ev.ActorKind, e.OccurredAt, recordedAt,
			e.PayloadHash, string(meta), e.MetadataHash, e.PrevHash, e.EntryHash)
		seq = e.SeqNo
		copy(prev[:], e.EntryHash)
		appended = append(appended, e)
	}
	if len(appended) > 0 {
		br := tx.SendBatch(ctx, batch)
		for i := range appended {
			ct, err := br.Exec()
			if err != nil {
				_ = br.Close()
				return nil, fmt.Errorf("ingest: chèn bản ghi %s: %w", appended[i].EventID, err)
			}
			if ct.RowsAffected() == 0 {
				_ = br.Close()
				return nil, fmt.Errorf("ingest: event_id %s đã có ở chuỗi khác", appended[i].EventID)
			}
		}
		if err := br.Close(); err != nil {
			return nil, err
		}
	}
	if len(appended) > 0 || recovery {
		if _, err := tx.Exec(ctx,
			`UPDATE ledger.chain_head SET last_seq_no = $3, last_hash = $4, updated_at = $5,
			        is_locked = false, locked_reason = NULL
			 WHERE tenant_id = $1 AND chain_partition = $2`,
			tenantID, chain, seq, prev[:], recordedAt); err != nil {
			return nil, fmt.Errorf("ingest: cập nhật chain_head: %w", err)
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("ingest: cam kết lô: %w", err)
	}
	res.Appended = appended
	res.LastSeqNo = seq
	res.LastHash = prev[:]
	res.Duration = ing.now().Sub(start)
	ing.count("ledger.ingest.batches_total", 1)
	ing.count("ledger.ingest.entries_total", len(appended))
	ing.count("ledger.ingest.duplicates_total", res.DuplicatesSkipped)
	ing.stats.observe(tenantID, chain, len(appended), recordedAt, valid)
	return res, nil
}

func checkEvent(ev EventInput, tenantID uuid.UUID, chain int16) error {
	switch {
	case ev.TenantID != tenantID:
		return fmt.Errorf("%w: sự kiện %s thuộc tenant %s, lô của tenant %s", ErrInvalidEvent, ev.EventID, ev.TenantID, tenantID)
	case ev.EventID == uuid.Nil:
		return fmt.Errorf("%w: thiếu event_id", ErrInvalidEvent)
	case !IsLegalEvent(ev.EventType):
		return fmt.Errorf("%w: %s", ErrEventNotInLegalCatalog, ev.EventType)
	case ev.ChainPartition() != chain:
		return fmt.Errorf("%w: sự kiện %s thuộc chuỗi %d, lô của chuỗi %d", ErrInvalidEvent, ev.EventID, ev.ChainPartition(), chain)
	case ev.OccurredAt.IsZero():
		return fmt.Errorf("%w: thiếu occurred_at", ErrInvalidEvent)
	case ev.ActorKind != "NGUOI" && ev.ActorKind != "DICH_VU" && ev.ActorKind != "HE_THONG":
		return fmt.Errorf("%w: actor_kind %q", ErrInvalidEvent, ev.ActorKind)
	case strings.TrimSpace(ev.AggregateType) == "" || len(ev.AggregateType) > 32:
		return fmt.Errorf("%w: aggregate_type %q", ErrInvalidEvent, ev.AggregateType)
	}
	return ValidateMetadataC2(ev.Classification, ev.Metadata)
}

func metadataOf(ev EventInput) map[string]any {
	if ev.Metadata == nil {
		return map[string]any{}
	}
	return ev.Metadata
}

// entry tính payload_hash (nếu chưa có), metadata_hash và entry_hash của bản ghi seq trên chuỗi.
func (ing *Ingester) entry(ev EventInput, tenantID uuid.UUID, chain int16, seq int64, prev [32]byte, recordedAt time.Time) (AppendedEntry, error) {
	var payload [32]byte
	if len(ev.PayloadHash) == 32 {
		copy(payload[:], ev.PayloadHash)
	} else {
		raw, err := canon.CanonicalizeValue(ev.Payload)
		if err != nil {
			return AppendedEntry{}, fmt.Errorf("ingest: chuẩn hoá payload sự kiện %s: %w", ev.EventID, err)
		}
		if payload, _, err = canon.PayloadHash(raw); err != nil {
			return AppendedEntry{}, err
		}
	}
	var actor *string
	if ev.ActorID != nil {
		s := ev.ActorID.String()
		actor = &s
	}
	occurred := ev.OccurredAt.UTC().Truncate(time.Microsecond)
	meta, _, err := canon.MetadataHash(canon.MetadataHeader{
		TenantID:       tenantID.String(),
		ChainPartition: chain,
		SeqNo:          seq,
		EventType:      ev.EventType,
		AggregateType:  ev.AggregateType,
		AggregateID:    ev.AggregateID.String(),
		ActorID:        actor,
		ActorKind:      ev.ActorKind,
		OccurredAt:     canon.FormatTimestamp(occurred),
		RecordedAt:     canon.FormatTimestamp(recordedAt),
	})
	if err != nil {
		return AppendedEntry{}, fmt.Errorf("ingest: metadata_hash sự kiện %s: %w", ev.EventID, err)
	}
	h := canon.EntryHash(prev, payload, meta)
	return AppendedEntry{
		ID:             uuid.Must(uuid.NewV7()),
		TenantID:       tenantID,
		RecordedMonth:  partition.RecordedMonth(recordedAt),
		ChainPartition: chain,
		SeqNo:          seq,
		EventID:        ev.EventID,
		EventType:      ev.EventType,
		PayloadHash:    payload[:],
		MetadataHash:   meta[:],
		PrevHash:       append([]byte(nil), prev[:]...),
		EntryHash:      h[:],
		OccurredAt:     occurred,
		RecordedAt:     recordedAt,
	}, nil
}

// adminAggregate là mã đối tượng tất định rơi đúng vào chuỗi của bản ghi đặc biệt cấp chuỗi.
func adminAggregate(tenantID uuid.UUID, chain int16) uuid.UUID {
	for i := 0; ; i++ {
		u := uuid.NewSHA1(tenantID, fmt.Appendf(nil, "ledger-chain-%d-%d", chain, i))
		if canon.ChainPartition(u) == chain {
			return u
		}
	}
}

// AppendTenantMovedIn ghi TENANT_MOVED_IN làm bản ghi đầu chuỗi ở cell đích. Người thực hiện và lý do
// nằm trong chính bản ghi (actor_id, metadata.reason_code) và nhật ký vận hành.
func (ing *Ingester) AppendTenantMovedIn(ctx context.Context, cmd TenantMovedInCmd) (*AppendedEntry, error) {
	if cmd.TenantID == uuid.Nil || cmd.AdminActorID == uuid.Nil || strings.TrimSpace(cmd.AuditReason) == "" {
		return nil, ErrUnauthorizedAdminCommand
	}
	if strings.TrimSpace(cmd.OriginCell) == "" || cmd.LastSealID == uuid.Nil || len(cmd.OriginLastEntryHash) != 32 {
		return nil, errors.New("ingest: TENANT_MOVED_IN cần origin_cell, last_seal_id và origin_last_entry_hash 32 byte")
	}
	actor := cmd.AdminActorID
	origin := hex.EncodeToString(cmd.OriginLastEntryHash)
	ev := EventInput{
		EventID:       uuid.NewSHA1(cmd.TenantID, fmt.Appendf(nil, "%s|%d|%s", EventTypeTenantMovedIn, cmd.ChainPartition, origin)),
		TenantID:      cmd.TenantID,
		EventType:     EventTypeTenantMovedIn,
		AggregateType: "CHUOI_SO_CAI",
		AggregateID:   adminAggregate(cmd.TenantID, cmd.ChainPartition),
		ActorID:       &actor,
		ActorKind:     "NGUOI",
		OccurredAt:    ing.now().UTC(),
		Payload: map[string]any{
			"origin_cell": cmd.OriginCell, "last_seal_id": cmd.LastSealID.String(), "origin_last_entry_hash": origin,
		},
		Metadata: map[string]any{
			"origin_cell": cmd.OriginCell, "seal_id": cmd.LastSealID.String(),
			"origin_last_entry_hash": origin, "reason_code": cmd.AuditReason,
		},
		OriginLastEntryHash: cmd.OriginLastEntryHash,
		OriginLastSeqNo:     cmd.OriginLastSeqNo,
	}
	res, err := ing.append(ctx, cmd.TenantID, cmd.ChainPartition, []EventInput{ev}, false)
	if err != nil {
		return nil, err
	}
	if len(res.Appended) == 0 {
		return nil, errors.New("ingest: TENANT_MOVED_IN của chuỗi này đã được ghi")
	}
	ing.log.Warn("lệnh quản trị sổ cái: TENANT_MOVED_IN", "tenant_id", cmd.TenantID, "chain_partition", cmd.ChainPartition,
		"actor_id", cmd.AdminActorID, "origin_cell", cmd.OriginCell, "reason", cmd.AuditReason)
	return &res.Appended[0], nil
}

// AppendIncidentRecoveryMarker ghi DANH_DAU_SU_CO trên chuỗi đang khoá ghi và mở khoá trong cùng giao dịch.
func (ing *Ingester) AppendIncidentRecoveryMarker(ctx context.Context, cmd IncidentRecoveryCmd) (*AppendedEntry, error) {
	if cmd.TenantID == uuid.Nil || cmd.AdminActorID == uuid.Nil || strings.TrimSpace(cmd.AuditReason) == "" {
		return nil, ErrUnauthorizedAdminCommand
	}
	if cmd.LastValidSealID == uuid.Nil || strings.TrimSpace(cmd.IncidentRef) == "" {
		return nil, errors.New("ingest: DANH_DAU_SU_CO cần last_valid_seal_id và incident_ref")
	}
	actor := cmd.AdminActorID
	ev := EventInput{
		EventID:       uuid.Must(uuid.NewV7()),
		TenantID:      cmd.TenantID,
		EventType:     EventTypeIncidentRecovery,
		AggregateType: "CHUOI_SO_CAI",
		AggregateID:   adminAggregate(cmd.TenantID, cmd.ChainPartition),
		ActorID:       &actor,
		ActorKind:     "NGUOI",
		OccurredAt:    ing.now().UTC(),
		Payload: map[string]any{
			"incident_ref": cmd.IncidentRef, "last_valid_seal_id": cmd.LastValidSealID.String(),
		},
		Metadata: map[string]any{
			"incident_ref": cmd.IncidentRef, "last_valid_seal_id": cmd.LastValidSealID.String(),
			"reason_code": cmd.AuditReason,
		},
	}
	res, err := ing.append(ctx, cmd.TenantID, cmd.ChainPartition, []EventInput{ev}, true)
	if err != nil {
		return nil, err
	}
	ing.log.Warn("lệnh quản trị sổ cái: DANH_DAU_SU_CO", "tenant_id", cmd.TenantID, "chain_partition", cmd.ChainPartition,
		"actor_id", cmd.AdminActorID, "incident_ref", cmd.IncidentRef, "reason", cmd.AuditReason)
	return &res.Appended[0], nil
}

// LockPartition khoá ghi một chuỗi khi phát hiện phá chuỗi (BR-13-11); luồng ghi dừng chuỗi này
// cho tới khi có bản ghi DANH_DAU_SU_CO. Phát EVT-20 thuộc DV-09-04.
func (ing *Ingester) LockPartition(ctx context.Context, tenantID uuid.UUID, chain int16, reason string) error {
	if strings.TrimSpace(reason) == "" {
		reason = "PHA_CHUOI"
	}
	tx, err := ing.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenantID.String()); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO ledger.chain_head (tenant_id, chain_partition, last_seq_no, last_hash, is_locked, locked_reason, updated_at)
		 VALUES ($1, $2, 0, $3, true, $4, $5)
		 ON CONFLICT (tenant_id, chain_partition)
		 DO UPDATE SET is_locked = true, locked_reason = EXCLUDED.locked_reason, updated_at = EXCLUDED.updated_at`,
		tenantID, chain, canon.ZeroHash[:], reason, ing.now().UTC()); err != nil {
		return fmt.Errorf("ingest: khoá chuỗi: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	ing.count("ledger.ingest.incidents_total", 1)
	return nil
}

// Locked cho biết chuỗi đang bị khoá ghi.
func (ing *Ingester) Locked(ctx context.Context, tenantID uuid.UUID, chain int16) (bool, error) {
	var locked bool
	err := pgx.BeginFunc(ctx, ing.pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenantID.String()); err != nil {
			return err
		}
		err := tx.QueryRow(ctx, `SELECT is_locked FROM ledger.chain_head WHERE tenant_id = $1 AND chain_partition = $2`,
			tenantID, chain).Scan(&locked)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		return err
	})
	return locked, err
}

// VerifyPartitionChain kiểm seq_no liên tục từ 1 và prev_hash, entry_hash của mọi bản ghi một chuỗi;
// trả số bản ghi đã kiểm. Kiểm tra toàn vẹn đầy đủ thuộc DV-09-04.
func (ing *Ingester) VerifyPartitionChain(ctx context.Context, tenantID uuid.UUID, chain int16) (int64, error) {
	var count int64
	err := pgx.BeginTxFunc(ctx, ing.pool, pgx.TxOptions{AccessMode: pgx.ReadOnly}, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenantID.String()); err != nil {
			return err
		}
		rows, err := tx.Query(ctx,
			`SELECT seq_no, event_type, payload_hash, metadata_hash, prev_hash, entry_hash
			 FROM ledger.ledger_entry WHERE tenant_id = $1 AND chain_partition = $2 ORDER BY seq_no`,
			tenantID, chain)
		if err != nil {
			return err
		}
		defer rows.Close()
		expected := canon.ZeroHash[:]
		var next int64 = 1
		for rows.Next() {
			var (
				seq                   int64
				typ                   string
				p, m, prev, entryHash []byte
			)
			if err := rows.Scan(&seq, &typ, &p, &m, &prev, &entryHash); err != nil {
				return err
			}
			movedIn := count == 0 && typ == EventTypeTenantMovedIn
			if movedIn {
				next = seq
			}
			if seq != next {
				return fmt.Errorf("ingest: lỗ seq_no ở chuỗi %d: cần %d, gặp %d", chain, next, seq)
			}
			if !movedIn && !bytes.Equal(prev, expected) {
				return fmt.Errorf("ingest: prev_hash lệch tại seq_no=%d chuỗi %d", seq, chain)
			}
			if got := canon.EntryHash([32]byte(prev), [32]byte(p), [32]byte(m)); !bytes.Equal(got[:], entryHash) {
				return fmt.Errorf("ingest: entry_hash lệch tại seq_no=%d chuỗi %d", seq, chain)
			}
			expected = entryHash
			next++
			count++
		}
		return rows.Err()
	})
	return count, err
}

// stats giữ số liệu vận hành trong bộ nhớ: độ trễ từ lúc sự kiện xảy ra tới khi có trong ledger_entry
// (p99 trên 4096 mẫu gần nhất) và số bản ghi theo chuỗi.
type stats struct {
	mu      sync.Mutex
	started time.Time
	lagMs   []float64
	total   int64
	byChain map[int16]int64
}

func newStats() *stats { return &stats{started: time.Now(), byChain: map[int16]int64{}} }

func (s *stats) observe(_ uuid.UUID, chain int16, n int, recordedAt time.Time, evs []EventInput) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.total += int64(n)
	s.byChain[chain] += int64(n)
	for _, ev := range evs {
		s.lagMs = append(s.lagMs, float64(recordedAt.Sub(ev.OccurredAt).Microseconds())/1000)
	}
	if over := len(s.lagMs) - 4096; over > 0 {
		s.lagMs = s.lagMs[over:]
	}
}

// Stats trả số liệu cho /metrics: tổng bản ghi, bản ghi/giây, p99 độ trễ (ms) và bản ghi/giây theo chuỗi.
func (ing *Ingester) Stats() map[string]float64 {
	s := ing.stats
	s.mu.Lock()
	defer s.mu.Unlock()
	elapsed := math.Max(time.Since(s.started).Seconds(), 0.001)
	out := map[string]float64{
		"ledger.ingest.entries":            float64(s.total),
		"ledger.ingest.entries_per_second": float64(s.total) / elapsed,
		"ledger.ingest.lag_p99_ms":         p99(s.lagMs),
	}
	for c, n := range s.byChain {
		out[fmt.Sprintf("ledger.ingest.chain.%02d.entries_per_second", c)] = float64(n) / elapsed
	}
	return out
}

func p99(samples []float64) float64 {
	if len(samples) == 0 {
		return 0
	}
	cp := append([]float64(nil), samples...)
	sort.Float64s(cp)
	return cp[int(math.Ceil(0.99*float64(len(cp))))-1]
}
