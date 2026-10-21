// Package ingest hiện thực bộ tiêu thụ sự kiện Kafka (consumer group ledger-ingester),
// bộ lọc danh mục sự kiện pháp lý, kiểm soát siêu dữ liệu C2 (ADR-06),
// ghi lô (batch writer) tính chuỗi băm SHA-256 trên phân vùng (tenant_id, chain_partition),
// và các bản ghi đặc biệt TENANT_MOVED_IN / DANH_DAU_SU_CO theo TL-01 §3.6, TL-02b QT-13, TL-03 §1.1–1.2.
package ingest

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
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
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/twmb/franz-go/pkg/kgo"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/partition"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
)

const (
	// TopicLedgerAppend là chủ đề nội bộ gom mọi sự kiện pháp lý cần ghi vào sổ cái.
	TopicLedgerAppend = "sys.ledger.append.v1"
	// ConsumerGroupLedgerIngester là định danh consumer group của luồng ghi sổ cái.
	ConsumerGroupLedgerIngester = "ledger-ingester"
	// MaxBatchSize là kích thước lô tối đa trong một giao dịch ghi sổ cái (<= 200 bản ghi).
	MaxBatchSize = 200

	// EventTypeTenantMovedIn là bản ghi đặc biệt mở phân đoạn chuỗi băm tại cell đích sau di trú.
	EventTypeTenantMovedIn = "TENANT_MOVED_IN"
	// EventTypeIncidentRecovery là bản ghi đặc biệt đánh dấu khôi phục sau sự cố (TL-02b QT-13).
	EventTypeIncidentRecovery = "DANH_DAU_SU_CO"
)

var (
	// ErrPartitionLocked trả về khi phân vùng chuỗi băm đang bị khoá do phát hiện sai lệch (BR-13-11).
	ErrPartitionLocked = errors.New("ingest: phân vùng chuỗi băm đang bị khoá do sự cố (BR-13-11)")
	// ErrC2CleartextInMetadata trả về khi phát hiện trường nội dung C2/C3 dạng rõ trong metadata (ADR-06).
	ErrC2CleartextInMetadata = errors.New("ingest: từ chối ghi trường dữ liệu C2/C3 dạng rõ trong metadata (ADR-06)")
	// ErrEventNotInLegalCatalog trả về khi sự kiện không thuộc danh mục sự kiện pháp lý.
	ErrEventNotInLegalCatalog = errors.New("ingest: sự kiện không thuộc danh mục sự kiện pháp lý")
	// ErrUnauthorizedAdminCommand trả về khi lệnh quản trị thiếu thông tin người thực hiện hoặc lý do kiểm toán.
	ErrUnauthorizedAdminCommand = errors.New("ingest: lệnh đặc biệt yêu cầu thông tin quản trị viên và lý do kiểm toán hợp lệ")
)

// legalEventTypes là tập hợp mã sự kiện và tên sự kiện chuẩn thuộc danh mục pháp lý
// bắt buộc ghi sổ cái bất biến (EVT-01..05, EVT-07..09, EVT-11..13, EVT-18, EVT-22, EVT-25, sys.audit.v1).
var legalEventTypes = map[string]bool{
	// Theo mã EVT baseline
	"EVT-01": true,
	"EVT-02": true,
	"EVT-03": true,
	"EVT-04": true,
	"EVT-05": true,
	"EVT-07": true,
	"EVT-08": true,
	"EVT-09": true,
	"EVT-11": true,
	"EVT-12": true,
	"EVT-13": true,
	"EVT-18": true,
	"EVT-22": true,
	"EVT-25": true,
	// Theo tên sự kiện chuẩn trên Kafka
	"task.assigned.v1":               true, // EVT-01
	"task.Accepted.v1":               true, // EVT-02 (tương thích hoa/thường)
	"task.accepted.v1":               true, // EVT-02
	"task.progress.updated.v1":       true, // EVT-03
	"task.submitted.v1":              true, // EVT-04
	"task.reviewed.v1":               true, // EVT-05
	"okr.objective.approved.v1":      true, // EVT-07
	"okr.kr.checkin.v1":              true, // EVT-08
	"kpi.period.locked.v1":           true, // EVT-09
	"eval.self.submitted.v1":         true, // EVT-11
	"eval.council.scored.v1":         true, // EVT-12
	"eval.classification.decided.v1": true, // EVT-13
	"esign.signature.completed.v1":   true, // EVT-18
	"dossier.archived.v1":            true, // EVT-22
	"SyncCompleted.v1":               true, // EVT-25
	"sync.completed.v1":              true, // EVT-25
	"sys.audit.v1":                   true, // Nhật ký kiểm toán hệ thống
	// Bản ghi đặc biệt nội bộ của sổ cái
	EventTypeTenantMovedIn:    true,
	EventTypeIncidentRecovery: true,
}

// allowedC1MetadataKeys liệt kê các khoá siêu dữ liệu C1 (trạng thái trước/sau, mã tham chiếu)
// được phép xuất hiện trong metadata của bản ghi sổ cái đối với sự kiện phân loại C2/C3 (ADR-06).
var allowedC1MetadataKeys = map[string]bool{
	"before_status":          true,
	"after_status":           true,
	"ref_id":                 true,
	"reason_code":            true,
	"seal_id":                true,
	"origin_cell":            true,
	"origin_last_entry_hash": true,
	"incident_ref":           true,
	"last_valid_seal_id":     true,
	"classification":         true,
	"source_topic":           true,
	"schema_version":         true,
}

// forbiddenMetadataSubstrings nhận diện các trường nhạy cảm C2/C3 nếu vô tình truyền vào metadata ở mọi cấp.
var forbiddenMetadataSubstrings = []string{
	"c2_cleartext",
	"directive_body",
	"comment_body",
	"secret_note",
	"private_content",
	"raw_attachment",
	"decrypted_",
}

// IsLegalEvent kiểm tra event_type có thuộc danh mục sự kiện pháp lý phải ghi sổ cái hay không.
func IsLegalEvent(eventType string) bool {
	trimmed := strings.TrimSpace(eventType)
	if trimmed == "" {
		return false
	}
	return legalEventTypes[trimmed]
}

// ValidateMetadataC2 kiểm tra metadata không chứa nội dung C2/C3 dạng rõ theo ADR-06.
func ValidateMetadataC2(classification string, metadata map[string]any) error {
	if len(metadata) == 0 {
		return nil
	}
	cls := strings.ToUpper(strings.TrimSpace(classification))
	if rawCls, ok := metadata["classification"].(string); ok && cls == "" {
		cls = strings.ToUpper(strings.TrimSpace(rawCls))
	}

	for k, v := range metadata {
		lowerKey := strings.ToLower(strings.TrimSpace(k))
		for _, forbidden := range forbiddenMetadataSubstrings {
			if strings.Contains(lowerKey, forbidden) {
				return fmt.Errorf("%w: khoá cấm %q", ErrC2CleartextInMetadata, k)
			}
		}
		if cls == "C2" || cls == "C3" {
			if !allowedC1MetadataKeys[k] {
				return fmt.Errorf("%w: khoá %q không nằm trong danh sách C1 cho phép của sự kiện %s", ErrC2CleartextInMetadata, k, cls)
			}
			// Không cho phép lồng object/map phức tạp hoặc chuỗi quá dài trong metadata của sự kiện C2/C3
			switch val := v.(type) {
			case map[string]any, []any:
				return fmt.Errorf("%w: giá trị phức hợp tại khoá %q không được phép trong sự kiện %s", ErrC2CleartextInMetadata, k, cls)
			case string:
				if len(val) > 256 {
					return fmt.Errorf("%w: giá trị chuỗi tại khoá %q vượt quá 256 ký tự cho sự kiện %s", ErrC2CleartextInMetadata, k, cls)
				}
			}
		}
	}
	return nil
}

// EventInput biểu diễn một sự kiện đầu vào cần ghi vào sổ cái.
type EventInput struct {
	EventID                uuid.UUID      `json:"event_id"`
	TenantID               uuid.UUID      `json:"tenant_id"`
	EventType              string         `json:"event_type"`
	AggregateType          string         `json:"aggregate_type"`
	AggregateID            uuid.UUID      `json:"aggregate_id"`
	ActorID                *uuid.UUID     `json:"actor_id,omitempty"`
	ActorKind              string         `json:"actor_kind"`
	OccurredAt             time.Time      `json:"occurred_at"`
	Classification         string         `json:"classification,omitempty"`
	Payload                any            `json:"payload,omitempty"`
	PrecomputedPayloadHash []byte         `json:"precomputed_payload_hash,omitempty"`
	Metadata               map[string]any `json:"metadata,omitempty"`
	// OriginLastEntryHash chỉ dùng cho bản ghi đặc biệt TENANT_MOVED_IN.
	OriginLastEntryHash []byte `json:"origin_last_entry_hash,omitempty"`
}

// PartitionKey trả về khoá phân vùng Kafka chuẩn "<tenant_id>:<chain_partition>" trên topic sys.ledger.append.v1.
func (e EventInput) PartitionKey() string {
	cp := canon.ChainPartition(e.AggregateID)
	return fmt.Sprintf("%s:%d", e.TenantID.String(), cp)
}

// ChainPartition trả về phân vùng chuỗi băm 0..63 của sự kiện.
func (e EventInput) ChainPartition() int16 {
	return canon.ChainPartition(e.AggregateID)
}

// AppendedEntry chứa thông tin bản ghi sổ cái vừa được ghi thành công.
type AppendedEntry struct {
	ID             uuid.UUID
	TenantID       uuid.UUID
	RecordedMonth  int
	ChainPartition int16
	SeqNo          int64
	EventID        uuid.UUID
	EventType      string
	AggregateType  string
	AggregateID    uuid.UUID
	PayloadHash    []byte
	MetadataHash   []byte
	PrevHash       []byte
	EntryHash      []byte
	OccurredAt     time.Time
	RecordedAt     time.Time
}

// BatchResult phản ánh kết quả xử lý một lô ghi sổ cái.
type BatchResult struct {
	TenantID          uuid.UUID
	ChainPartition    int16
	Appended          []AppendedEntry
	DuplicatesSkipped int
	RejectedC2        int
	LastSeqNo         int64
	LastHash          []byte
	Duration          time.Duration
}

// PartitionMetrics lưu thống kê vận hành theo phân vùng.
type PartitionMetrics struct {
	BatchesTotal      int64
	EntriesTotal      int64
	DuplicatesTotal   int64
	RejectedC2Total   int64
	LastSeqNo         int64
	EntriesPerSec     float64
	P99LatencyMs      float64
	ChainHeadLagMs    float64
	LastCommitTimeUTC time.Time
}

// TenantMovedInCmd chứa tham số lệnh quản trị ghi nhận bản ghi TENANT_MOVED_IN khi di trú tenant giữa các cell.
type TenantMovedInCmd struct {
	TenantID            uuid.UUID
	ChainPartition      int16
	OriginCell          string
	LastSealID          uuid.UUID
	OriginLastEntryHash []byte
	AdminActorID        uuid.UUID
	AuditReason         string
	OccurredAt          time.Time
}

// IncidentRecoveryCmd chứa tham số lệnh quản trị mở khoá phân vùng chuỗi băm sau sự cố (TL-02b QT-13).
type IncidentRecoveryCmd struct {
	TenantID        uuid.UUID
	ChainPartition  int16
	LastValidSealID uuid.UUID
	IncidentRef     string
	AdminActorID    uuid.UUID
	AuditReason     string
	OccurredAt      time.Time
}

// Ingester thực thi việc ghi lô chuỗi băm và quản lý trạng thái phân vùng.
type Ingester struct {
	pool *pgxpool.Pool
	otel *otel.Provider
	log  *slog.Logger

	partMu          sync.RWMutex
	ensuredPartKeys map[string]bool

	metricsMu    sync.Mutex
	startTime    time.Time
	totalEntries int64
	latenciesMs  []float64
	partStats    map[int16]*PartitionMetrics
}

// NewIngester khởi tạo bộ ghi chuỗi băm cho SVC-09.
func NewIngester(pool *pgxpool.Pool, otelProv *otel.Provider, log *slog.Logger) *Ingester {
	if log == nil {
		log = slog.Default()
	}
	return &Ingester{
		pool:            pool,
		otel:            otelProv,
		log:             log,
		ensuredPartKeys: make(map[string]bool),
		startTime:       time.Now().UTC(),
		partStats:       make(map[int16]*PartitionMetrics),
	}
}

func (ing *Ingester) ensurePartitionCached(ctx context.Context, tenantID uuid.UUID, recordedMonth int) error {
	key := fmt.Sprintf("%s:%d", tenantID.String(), recordedMonth)
	ing.partMu.RLock()
	ok := ing.ensuredPartKeys[key]
	ing.partMu.RUnlock()
	if ok {
		return nil
	}

	nextMonth, err := partition.NextRecordedMonth(recordedMonth)
	if err != nil {
		nextMonth = recordedMonth
	}
	if _, err := partition.EnsureTenantPartitions(ctx, ing.pool, tenantID, recordedMonth, nextMonth); err != nil {
		return err
	}

	ing.partMu.Lock()
	ing.ensuredPartKeys[key] = true
	ing.partMu.Unlock()
	return nil
}

// deterministicAggregateForPartition tìm một UUID tất định có ChainPartition(u) == targetPartition
// dùng cho các bản ghi đặc biệt cấp phân vùng (TENANT_MOVED_IN, DANH_DAU_SU_CO).
func deterministicAggregateForPartition(tenantID uuid.UUID, targetPartition int16) uuid.UUID {
	for i := 0; i < 10000; i++ {
		u := uuid.NewSHA1(tenantID, []byte(fmt.Sprintf("chain-partition-%d-%d", targetPartition, i)))
		if canon.ChainPartition(u) == targetPartition {
			return u
		}
	}
	return tenantID
}

// AppendBatch ghi một lô tối đa 200 sự kiện của cùng một (tenant_id, chain_partition) trong một giao dịch.
func (ing *Ingester) AppendBatch(ctx context.Context, tenantID uuid.UUID, chainPartition int16, events []EventInput) (*BatchResult, error) {
	if len(events) == 0 {
		return &BatchResult{TenantID: tenantID, ChainPartition: chainPartition}, nil
	}
	if len(events) > MaxBatchSize {
		return nil, fmt.Errorf("ingest: kích thước lô %d vượt quá giới hạn %d", len(events), MaxBatchSize)
	}
	if chainPartition < 0 || chainPartition >= canon.NumChainPartitions {
		return nil, fmt.Errorf("ingest: chain_partition %d ngoài miền 0..63", chainPartition)
	}

	batchStart := time.Now()
	nowUTC := batchStart.UTC().Truncate(time.Microsecond)

	// Bước 1: Lọc và kiểm tra hợp lệ từng sự kiện (danh mục pháp lý, ADR-06 C2 metadata, phân vùng).
	validEvents := make([]EventInput, 0, len(events))
	seenInBatch := make(map[uuid.UUID]bool, len(events))
	res := &BatchResult{
		TenantID:       tenantID,
		ChainPartition: chainPartition,
	}

	monthsNeeded := make(map[int]bool, 2)
	for _, ev := range events {
		if ev.TenantID != tenantID {
			return nil, fmt.Errorf("ingest: sự kiện %s có tenant_id %s lệch với tenant lô %s", ev.EventID, ev.TenantID, tenantID)
		}
		if !IsLegalEvent(ev.EventType) {
			return nil, fmt.Errorf("%w: %s", ErrEventNotInLegalCatalog, ev.EventType)
		}
		if err := ValidateMetadataC2(ev.Classification, ev.Metadata); err != nil {
			res.RejectedC2++
			if ing.otel != nil {
				ing.otel.AddCounter("ledger.ingest.rejected_c2_total", 1)
			}
			return res, err
		}
		if ev.EventID == uuid.Nil {
			return nil, errors.New("ingest: event_id không được rỗng")
		}
		if seenInBatch[ev.EventID] {
			res.DuplicatesSkipped++
			if ing.otel != nil {
				ing.otel.AddCounter("ledger.ingest.duplicates_total", 1)
			}
			continue
		}
		seenInBatch[ev.EventID] = true

		occ := ev.OccurredAt.UTC().Truncate(time.Microsecond)
		if ev.OccurredAt.IsZero() {
			occ = nowUTC
		}
		ev.OccurredAt = occ
		if strings.TrimSpace(ev.ActorKind) == "" {
			ev.ActorKind = "HE_THONG"
		}
		if strings.TrimSpace(ev.AggregateType) == "" {
			ev.AggregateType = "SYSTEM"
		}
		validEvents = append(validEvents, ev)
		monthsNeeded[partition.RecordedMonth(occ)] = true
	}

	// Đảm bảo phân vùng bảng cho mọi tháng có trong lô
	for m := range monthsNeeded {
		if err := ing.ensurePartitionCached(ctx, tenantID, m); err != nil {
			return nil, err
		}
	}

	// Bước 2: Mở giao dịch và đặt ngữ cảnh RLS app.tenant_id.
	tx, err := ing.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, fmt.Errorf("ingest: mở giao dịch thất bại: %w", err)
	}
	defer func() {
		_ = tx.Rollback(ctx)
	}()

	if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenantID.String()); err != nil {
		return nil, fmt.Errorf("ingest: thiết lập app.tenant_id thất bại: %w", err)
	}

	// Bước 3: Khởi tạo (nếu chưa có) và khoá dòng chain_head bằng SELECT ... FOR UPDATE.
	if _, err := tx.Exec(
		ctx,
		`INSERT INTO ledger.chain_head (tenant_id, chain_partition, last_seq_no, last_hash, updated_at)
		 VALUES ($1, $2, 0, $3, $4)
		 ON CONFLICT (tenant_id, chain_partition) DO NOTHING`,
		tenantID,
		chainPartition,
		canon.ZeroHash(),
		nowUTC,
	); err != nil {
		return nil, fmt.Errorf("ingest: khởi tạo chain_head thất bại: %w", err)
	}

	var (
		lastSeqNo    int64
		lastHash     []byte
		isLocked     bool
		lockedReason string
	)
	if err := tx.QueryRow(
		ctx,
		`SELECT last_seq_no, last_hash, is_locked, COALESCE(locked_reason, '')
		 FROM ledger.chain_head
		 WHERE tenant_id = $1 AND chain_partition = $2
		 FOR UPDATE`,
		tenantID,
		chainPartition,
	).Scan(&lastSeqNo, &lastHash, &isLocked, &lockedReason); err != nil {
		return nil, fmt.Errorf("ingest: khoá chain_head thất bại: %w", err)
	}

	if isLocked {
		if ing.otel != nil {
			ing.otel.AddCounter("ledger.ingest.incidents_total", 1)
		}
		return nil, fmt.Errorf("%w (tenant=%s, partition=%d, reason=%s)", ErrPartitionLocked, tenantID, chainPartition, lockedReason)
	}

	if len(validEvents) == 0 {
		res.LastSeqNo = lastSeqNo
		res.LastHash = lastHash
		res.Duration = time.Since(batchStart)
		return res, nil
	}

	// Bước 4: Kiểm tra chống trùng lặp event_id đã tồn tại trong DB để không làm hổng dãy seq_no.
	candidateIDs := make([]uuid.UUID, len(validEvents))
	for i, ev := range validEvents {
		candidateIDs[i] = ev.EventID
	}

	existingIDs := make(map[uuid.UUID]bool)
	rows, err := tx.Query(
		ctx,
		`SELECT event_id FROM ledger.ledger_entry WHERE tenant_id = $1 AND event_id = ANY($2)`,
		tenantID,
		candidateIDs,
	)
	if err != nil {
		return nil, fmt.Errorf("ingest: truy vấn kiểm tra trùng event_id thất bại: %w", err)
	}
	for rows.Next() {
		var eid uuid.UUID
		if err := rows.Scan(&eid); err != nil {
			rows.Close()
			return nil, err
		}
		existingIDs[eid] = true
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return nil, err
	}
	rows.Close()

	// Bước 5: Tính toán tuần tự seq_no, payload_hash, metadata_hash, prev_hash, entry_hash.
	runningSeq := lastSeqNo
	runningHash := make([]byte, 32)
	if len(lastHash) == 32 {
		copy(runningHash, lastHash)
	}

	appended := make([]AppendedEntry, 0, len(validEvents))
	pgBatch := &pgx.Batch{}

	for _, ev := range validEvents {
		if existingIDs[ev.EventID] {
			res.DuplicatesSkipped++
			if ing.otel != nil {
				ing.otel.AddCounter("ledger.ingest.duplicates_total", 1)
			}
			continue
		}

		var pHash []byte
		if len(ev.PrecomputedPayloadHash) == 32 {
			pHash = make([]byte, 32)
			copy(pHash, ev.PrecomputedPayloadHash)
		} else {
			payloadVal := ev.Payload
			if payloadVal == nil {
				payloadVal = map[string]any{}
			}
			pHash, err = canon.PayloadHash(payloadVal)
			if err != nil {
				return nil, fmt.Errorf("ingest: băm payload sự kiện %s thất bại: %w", ev.EventID, err)
			}
		}

		actorStr := ""
		if ev.ActorID != nil && *ev.ActorID != uuid.Nil {
			actorStr = strings.ToLower(ev.ActorID.String())
		}

		metaMap := ev.Metadata
		if metaMap == nil {
			metaMap = map[string]any{}
		}
		metaBytes, err := canon.CanonicalizeValue(metaMap)
		if err != nil {
			return nil, fmt.Errorf("ingest: chuẩn hoá metadata sự kiện %s thất bại: %w", ev.EventID, err)
		}

		mHash, err := canon.MetadataHash(canon.MetadataHeader{
			TenantID:      strings.ToLower(ev.TenantID.String()),
			EventID:       strings.ToLower(ev.EventID.String()),
			EventType:     ev.EventType,
			AggregateType: ev.AggregateType,
			AggregateID:   strings.ToLower(ev.AggregateID.String()),
			ActorID:       actorStr,
			ActorKind:     ev.ActorKind,
			OccurredAt:    canon.FormatTimestamp(ev.OccurredAt),
			Metadata:      metaMap,
		})
		if err != nil {
			return nil, fmt.Errorf("ingest: băm metadata sự kiện %s thất bại: %w", ev.EventID, err)
		}

		prevHash := make([]byte, 32)
		if ev.EventType == EventTypeTenantMovedIn && len(ev.OriginLastEntryHash) == 32 {
			copy(prevHash, ev.OriginLastEntryHash)
		} else {
			copy(prevHash, runningHash)
		}

		eHash, err := canon.EntryHash(prevHash, pHash, mHash)
		if err != nil {
			return nil, fmt.Errorf("ingest: tính entry_hash sự kiện %s thất bại: %w", ev.EventID, err)
		}

		runningSeq++
		runningHash = eHash
		entryID := uuid.New()
		recMonth := partition.RecordedMonth(ev.OccurredAt)

		pgBatch.Queue(
			`INSERT INTO ledger.ledger_entry (
				tenant_id, recorded_month, chain_partition, seq_no, id,
				event_id, event_type, aggregate_type, aggregate_id,
				actor_id, actor_kind, occurred_at, recorded_at,
				payload_hash, metadata, metadata_hash, prev_hash, entry_hash
			) VALUES (
				$1, $2, $3, $4, $5,
				$6, $7, $8, $9,
				$10, $11, $12, $13,
				$14, $15::jsonb, $16, $17, $18
			) ON CONFLICT (tenant_id, recorded_month, event_id) DO NOTHING`,
			tenantID,
			recMonth,
			chainPartition,
			runningSeq,
			entryID,
			ev.EventID,
			ev.EventType,
			ev.AggregateType,
			ev.AggregateID,
			ev.ActorID,
			ev.ActorKind,
			ev.OccurredAt,
			nowUTC,
			pHash,
			string(metaBytes),
			mHash,
			prevHash,
			eHash,
		)

		appended = append(appended, AppendedEntry{
			ID:             entryID,
			TenantID:       tenantID,
			RecordedMonth:  recMonth,
			ChainPartition: chainPartition,
			SeqNo:          runningSeq,
			EventID:        ev.EventID,
			EventType:      ev.EventType,
			AggregateType:  ev.AggregateType,
			AggregateID:    ev.AggregateID,
			PayloadHash:    pHash,
			MetadataHash:   mHash,
			PrevHash:       prevHash,
			EntryHash:      eHash,
			OccurredAt:     ev.OccurredAt,
			RecordedAt:     nowUTC,
		})
	}

	if len(appended) > 0 {
		br := tx.SendBatch(ctx, pgBatch)
		for i := 0; i < len(appended); i++ {
			ct, err := br.Exec()
			if err != nil {
				_ = br.Close()
				return nil, fmt.Errorf("ingest: chèn bản ghi sổ cái thứ %d thất bại: %w", i, err)
			}
			if ct.RowsAffected() == 0 {
				_ = br.Close()
				return nil, fmt.Errorf("ingest: xung đột đồng thời trên event_id %s", appended[i].EventID)
			}
		}
		if err := br.Close(); err != nil {
			return nil, fmt.Errorf("ingest: đóng batch chèn sổ cái thất bại: %w", err)
		}

		if _, err := tx.Exec(
			ctx,
			`UPDATE ledger.chain_head
			 SET last_seq_no = $3, last_hash = $4, updated_at = $5
			 WHERE tenant_id = $1 AND chain_partition = $2`,
			tenantID,
			chainPartition,
			runningSeq,
			runningHash,
			nowUTC,
		); err != nil {
			return nil, fmt.Errorf("ingest: cập nhật chain_head thất bại: %w", err)
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return nil, fmt.Errorf("ingest: commit giao dịch sổ cái thất bại: %w", err)
	}

	duration := time.Since(batchStart)
	res.Appended = appended
	res.LastSeqNo = runningSeq
	res.LastHash = runningHash
	res.Duration = duration

	ing.recordTelemetry(chainPartition, len(appended), res.DuplicatesSkipped, runningSeq, duration, validEvents)
	return res, nil
}

func (ing *Ingester) recordTelemetry(chainPartition int16, entriesCount, dupCount int, lastSeqNo int64, dur time.Duration, events []EventInput) {
	if ing.otel != nil {
		ing.otel.AddCounter("ledger.ingest.batches_total", 1)
		if entriesCount > 0 {
			ing.otel.AddCounter("ledger.ingest.entries_total", int64(entriesCount))
		}
	}

	durMs := float64(dur.Microseconds()) / 1000.0
	var maxLagMs float64
	now := time.Now().UTC()
	for _, ev := range events {
		if !ev.OccurredAt.IsZero() {
			lag := float64(now.Sub(ev.OccurredAt).Milliseconds())
			if lag > maxLagMs {
				maxLagMs = lag
			}
		}
	}

	ing.metricsMu.Lock()
	defer ing.metricsMu.Unlock()

	ing.totalEntries += int64(entriesCount)
	ing.latenciesMs = append(ing.latenciesMs, durMs)
	if len(ing.latenciesMs) > 2048 {
		ing.latenciesMs = ing.latenciesMs[len(ing.latenciesMs)-2048:]
	}

	elapsedSec := time.Since(ing.startTime).Seconds()
	if elapsedSec <= 0 {
		elapsedSec = 0.001
	}

	p99 := computeP99(ing.latenciesMs)
	pm, ok := ing.partStats[chainPartition]
	if !ok {
		pm = &PartitionMetrics{}
		ing.partStats[chainPartition] = pm
	}
	pm.BatchesTotal++
	pm.EntriesTotal += int64(entriesCount)
	pm.DuplicatesTotal += int64(dupCount)
	pm.LastSeqNo = lastSeqNo
	pm.EntriesPerSec = float64(pm.EntriesTotal) / elapsedSec
	pm.P99LatencyMs = p99
	pm.ChainHeadLagMs = maxLagMs
	pm.LastCommitTimeUTC = now
}

func computeP99(samples []float64) float64 {
	if len(samples) == 0 {
		return 0
	}
	cp := make([]float64, len(samples))
	copy(cp, samples)
	sort.Float64s(cp)
	idx := int(math.Ceil(0.99*float64(len(cp)))) - 1
	if idx < 0 {
		idx = 0
	}
	if idx >= len(cp) {
		idx = len(cp) - 1
	}
	return cp[idx]
}

// SnapshotMetrics trả về tổng số bản ghi đã ghi, thông lượng tổng (entries/sec), độ trễ p99 (ms)
// và thống kê theo từng phân vùng.
func (ing *Ingester) SnapshotMetrics() (totalEntries int64, overallEntriesPerSec float64, p99LatencyMs float64, byPartition map[int16]PartitionMetrics) {
	ing.metricsMu.Lock()
	defer ing.metricsMu.Unlock()

	elapsed := time.Since(ing.startTime).Seconds()
	if elapsed <= 0 {
		elapsed = 0.001
	}
	totalEntries = ing.totalEntries
	overallEntriesPerSec = float64(ing.totalEntries) / elapsed
	p99LatencyMs = computeP99(ing.latenciesMs)
	byPartition = make(map[int16]PartitionMetrics, len(ing.partStats))
	for k, v := range ing.partStats {
		byPartition[k] = *v
	}
	return
}

// AppendTenantMovedIn ghi bản ghi đặc biệt TENANT_MOVED_IN tại cell đích để mở phân đoạn chuỗi băm mới
// nối tiếp từ origin_last_entry_hash và last_seal_id của cell nguồn (TL-01 §3.6, TL-03 §1.1).
// Lệnh bắt buộc có thông tin quản trị viên và lý do kiểm toán, đồng thời phát sự kiện kiểm toán ra outbox.
func (ing *Ingester) AppendTenantMovedIn(ctx context.Context, cmd TenantMovedInCmd) (*AppendedEntry, error) {
	if cmd.TenantID == uuid.Nil || cmd.AdminActorID == uuid.Nil || strings.TrimSpace(cmd.AuditReason) == "" {
		return nil, ErrUnauthorizedAdminCommand
	}
	if strings.TrimSpace(cmd.OriginCell) == "" || cmd.LastSealID == uuid.Nil || len(cmd.OriginLastEntryHash) != 32 {
		return nil, errors.New("ingest: TENANT_MOVED_IN yêu cầu origin_cell, last_seal_id và origin_last_entry_hash 32 byte")
	}

	occ := cmd.OccurredAt.UTC().Truncate(time.Microsecond)
	if cmd.OccurredAt.IsZero() {
		occ = time.Now().UTC().Truncate(time.Microsecond)
	}
	aggID := deterministicAggregateForPartition(cmd.TenantID, cmd.ChainPartition)
	actorID := cmd.AdminActorID
	originHashHex := hex.EncodeToString(cmd.OriginLastEntryHash)

	ev := EventInput{
		EventID:       uuid.New(),
		TenantID:      cmd.TenantID,
		EventType:     EventTypeTenantMovedIn,
		AggregateType: "TENANT_CELL_MIGRATION",
		AggregateID:   aggID,
		ActorID:       &actorID,
		ActorKind:     "NGUOI",
		OccurredAt:    occ,
		Payload: map[string]any{
			"action":                 EventTypeTenantMovedIn,
			"origin_cell":            cmd.OriginCell,
			"last_seal_id":           strings.ToLower(cmd.LastSealID.String()),
			"origin_last_entry_hash": originHashHex,
			"audit_reason":           cmd.AuditReason,
		},
		Metadata: map[string]any{
			"origin_cell":            cmd.OriginCell,
			"seal_id":                strings.ToLower(cmd.LastSealID.String()),
			"origin_last_entry_hash": originHashHex,
			"reason_code":            cmd.AuditReason,
		},
		OriginLastEntryHash: cmd.OriginLastEntryHash,
	}

	batchRes, err := ing.AppendBatch(ctx, cmd.TenantID, cmd.ChainPartition, []EventInput{ev})
	if err != nil {
		return nil, err
	}
	if len(batchRes.Appended) == 0 {
		return nil, errors.New("ingest: không ghi được bản ghi TENANT_MOVED_IN")
	}
	entry := batchRes.Appended[0]

	if err := ing.insertAuditOutbox(ctx, cmd.TenantID, cmd.AdminActorID, EventTypeTenantMovedIn, entry.ID, map[string]any{
		"tenant_id":              cmd.TenantID.String(),
		"chain_partition":        cmd.ChainPartition,
		"origin_cell":            cmd.OriginCell,
		"last_seal_id":           cmd.LastSealID.String(),
		"origin_last_entry_hash": originHashHex,
		"audit_reason":           cmd.AuditReason,
		"entry_hash":             hex.EncodeToString(entry.EntryHash),
	}); err != nil {
		return nil, err
	}
	return &entry, nil
}

// LockPartitionOnMismatch khoá ngay phân vùng chuỗi băm (is_locked = true) khi phát hiện sai lệch (BR-13-11),
// đồng thời ghi sự kiện cảnh báo ra outbox để kích hoạt cảnh báo P1.
func (ing *Ingester) LockPartitionOnMismatch(ctx context.Context, tenantID uuid.UUID, chainPartition int16, reason string) error {
	if strings.TrimSpace(reason) == "" {
		reason = "CHAIN_HASH_MISMATCH"
	}
	tx, err := ing.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenantID.String()); err != nil {
		return err
	}

	nowUTC := time.Now().UTC().Truncate(time.Microsecond)
	if _, err := tx.Exec(
		ctx,
		`INSERT INTO ledger.chain_head (tenant_id, chain_partition, last_seq_no, last_hash, is_locked, locked_reason, updated_at)
		 VALUES ($1, $2, 0, $3, true, $4, $5)
		 ON CONFLICT (tenant_id, chain_partition)
		 DO UPDATE SET is_locked = true, locked_reason = EXCLUDED.locked_reason, updated_at = EXCLUDED.updated_at`,
		tenantID,
		chainPartition,
		canon.ZeroHash(),
		reason,
		nowUTC,
	); err != nil {
		return fmt.Errorf("ingest: khoá chain_head thất bại: %w", err)
	}

	payloadBytes, _ := json.Marshal(map[string]any{
		"tenant_id":       tenantID.String(),
		"chain_partition": chainPartition,
		"locked_reason":   reason,
		"detected_at":     canon.FormatTimestamp(nowUTC),
	})
	if _, err := tx.Exec(
		ctx,
		`INSERT INTO ledger.outbox_event (id, event_type, aggregate_type, aggregate_id, tenant_id, payload, created_at)
		 VALUES ($1, 'ledger.anomaly.detected.v1', 'CHAIN_HEAD', $2, $3, $4::jsonb, $5)`,
		uuid.New(),
		deterministicAggregateForPartition(tenantID, chainPartition),
		tenantID,
		string(payloadBytes),
		nowUTC,
	); err != nil {
		return fmt.Errorf("ingest: ghi outbox cảnh báo lệch chuỗi băm thất bại: %w", err)
	}

	if err := tx.Commit(ctx); err != nil {
		return err
	}
	if ing.otel != nil {
		ing.otel.AddCounter("ledger.ingest.incidents_total", 1)
	}
	return nil
}

// AppendIncidentRecoveryMarker mở khoá phân vùng đang bị khoá sự cố và ghi bản ghi đặc biệt DANH_DAU_SU_CO
// tham chiếu đến phong bì niêm phong hợp lệ cuối cùng (last_valid_seal_id) để mở phân đoạn chuỗi mới (TL-02b QT-13).
func (ing *Ingester) AppendIncidentRecoveryMarker(ctx context.Context, cmd IncidentRecoveryCmd) (*AppendedEntry, error) {
	if cmd.TenantID == uuid.Nil || cmd.AdminActorID == uuid.Nil || strings.TrimSpace(cmd.AuditReason) == "" {
		return nil, ErrUnauthorizedAdminCommand
	}
	if cmd.LastValidSealID == uuid.Nil || strings.TrimSpace(cmd.IncidentRef) == "" {
		return nil, errors.New("ingest: DANH_DAU_SU_CO yêu cầu last_valid_seal_id và incident_ref")
	}

	occ := cmd.OccurredAt.UTC().Truncate(time.Microsecond)
	if cmd.OccurredAt.IsZero() {
		occ = time.Now().UTC().Truncate(time.Microsecond)
	}
	recMonth := partition.RecordedMonth(occ)
	if err := ing.ensurePartitionCached(ctx, cmd.TenantID, recMonth); err != nil {
		return nil, err
	}

	tx, err := ing.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, cmd.TenantID.String()); err != nil {
		return nil, err
	}

	var isLocked bool
	if err := tx.QueryRow(
		ctx,
		`SELECT is_locked FROM ledger.chain_head WHERE tenant_id = $1 AND chain_partition = $2 FOR UPDATE`,
		cmd.TenantID,
		cmd.ChainPartition,
	).Scan(&isLocked); err != nil {
		return nil, fmt.Errorf("ingest: truy vấn chain_head khi khôi phục sự cố thất bại: %w", err)
	}
	if !isLocked {
		return nil, errors.New("ingest: chỉ được ghi DANH_DAU_SU_CO trên phân vùng đang bị khoá sự cố")
	}

	// Mở khoá chain_head trong giao dịch trước khi ghi bản ghi đánh dấu sự cố
	if _, err := tx.Exec(
		ctx,
		`UPDATE ledger.chain_head SET is_locked = false, locked_reason = NULL, updated_at = $3
		 WHERE tenant_id = $1 AND chain_partition = $2`,
		cmd.TenantID,
		cmd.ChainPartition,
		occ,
	); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}

	aggID := deterministicAggregateForPartition(cmd.TenantID, cmd.ChainPartition)
	actorID := cmd.AdminActorID
	ev := EventInput{
		EventID:       uuid.New(),
		TenantID:      cmd.TenantID,
		EventType:     EventTypeIncidentRecovery,
		AggregateType: "LEDGER_INCIDENT_RECOVERY",
		AggregateID:   aggID,
		ActorID:       &actorID,
		ActorKind:     "NGUOI",
		OccurredAt:    occ,
		Payload: map[string]any{
			"action":             EventTypeIncidentRecovery,
			"incident_ref":       cmd.IncidentRef,
			"last_valid_seal_id": strings.ToLower(cmd.LastValidSealID.String()),
			"audit_reason":       cmd.AuditReason,
		},
		Metadata: map[string]any{
			"incident_ref":       cmd.IncidentRef,
			"last_valid_seal_id": strings.ToLower(cmd.LastValidSealID.String()),
			"reason_code":        cmd.AuditReason,
		},
	}

	batchRes, err := ing.AppendBatch(ctx, cmd.TenantID, cmd.ChainPartition, []EventInput{ev})
	if err != nil {
		return nil, err
	}
	if len(batchRes.Appended) == 0 {
		return nil, errors.New("ingest: không ghi được bản ghi DANH_DAU_SU_CO")
	}
	entry := batchRes.Appended[0]

	if err := ing.insertAuditOutbox(ctx, cmd.TenantID, cmd.AdminActorID, EventTypeIncidentRecovery, entry.ID, map[string]any{
		"tenant_id":          cmd.TenantID.String(),
		"chain_partition":    cmd.ChainPartition,
		"incident_ref":       cmd.IncidentRef,
		"last_valid_seal_id": cmd.LastValidSealID.String(),
		"audit_reason":       cmd.AuditReason,
		"entry_hash":         hex.EncodeToString(entry.EntryHash),
	}); err != nil {
		return nil, err
	}
	return &entry, nil
}

func (ing *Ingester) insertAuditOutbox(ctx context.Context, tenantID, adminActorID uuid.UUID, action string, entryID uuid.UUID, details map[string]any) error {
	payloadBytes, err := json.Marshal(details)
	if err != nil {
		return err
	}
	_, err = ing.pool.Exec(
		ctx,
		`INSERT INTO ledger.outbox_event (id, event_type, aggregate_type, aggregate_id, tenant_id, payload, created_at)
		 VALUES ($1, 'sys.audit.v1', $2, $3, $4, $5::jsonb, $6)`,
		uuid.New(),
		action,
		entryID,
		tenantID,
		string(payloadBytes),
		time.Now().UTC().Truncate(time.Microsecond),
	)
	return err
}

// VerifyPartitionChain kiểm tra tính toàn vẹn chuỗi băm của một (tenant_id, chain_partition)
// từ seq_no = 1 đến hết; trả về số bản ghi đã xác minh hoặc lỗi nếu phát hiện đứt gãy / sai băm.
func (ing *Ingester) VerifyPartitionChain(ctx context.Context, tenantID uuid.UUID, chainPartition int16) (int64, error) {
	tx, err := ing.pool.BeginTx(ctx, pgx.TxOptions{AccessMode: pgx.ReadOnly})
	if err != nil {
		return 0, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenantID.String()); err != nil {
		return 0, err
	}

	rows, err := tx.Query(
		ctx,
		`SELECT seq_no, event_type, payload_hash, metadata_hash, prev_hash, entry_hash
		 FROM ledger.ledger_entry
		 WHERE tenant_id = $1 AND chain_partition = $2
		 ORDER BY seq_no ASC`,
		tenantID,
		chainPartition,
	)
	if err != nil {
		return 0, err
	}
	defer rows.Close()

	var (
		expectedSeq  int64 = 1
		expectedPrev       = canon.ZeroHash()
		count        int64
	)

	for rows.Next() {
		var (
			seqNo      int64
			eventType  string
			pHash      []byte
			mHash      []byte
			prevHash   []byte
			entryHash  []byte
		)
		if err := rows.Scan(&seqNo, &eventType, &pHash, &mHash, &prevHash, &entryHash); err != nil {
			return count, err
		}
		if seqNo != expectedSeq {
			return count, fmt.Errorf("ingest: đứt gãy seq_no tại partition %d: kỳ vọng %d, thực tế %d", chainPartition, expectedSeq, seqNo)
		}
		if eventType != EventTypeTenantMovedIn && !bytes.Equal(prevHash, expectedPrev) {
			return count, fmt.Errorf("ingest: lệch prev_hash tại seq_no=%d partition=%d", seqNo, chainPartition)
		}
		computed, err := canon.EntryHash(prevHash, pHash, mHash)
		if err != nil {
			return count, err
		}
		if !bytes.Equal(computed, entryHash) {
			return count, fmt.Errorf("ingest: lệch entry_hash tại seq_no=%d partition=%d", seqNo, chainPartition)
		}
		expectedSeq++
		expectedPrev = entryHash
		count++
	}
	if err := rows.Err(); err != nil {
		return count, err
	}
	return count, nil
}

// PublishLegalEvents định tuyến các sự kiện thuộc danh mục pháp lý vào topic sys.ledger.append.v1
// với khoá phân vùng "<tenant_id>:<chain_partition>". Các sự kiện không thuộc danh mục pháp lý được bỏ qua.
func PublishLegalEvents(ctx context.Context, cl *kgo.Client, events []EventInput) (int, error) {
	records := make([]*kgo.Record, 0, len(events))
	for _, ev := range events {
		if !IsLegalEvent(ev.EventType) {
			continue
		}
		raw, err := json.Marshal(ev)
		if err != nil {
			return 0, err
		}
		records = append(records, &kgo.Record{
			Topic: TopicLedgerAppend,
			Key:   []byte(ev.PartitionKey()),
			Value: raw,
		})
	}
	if len(records) == 0 {
		return 0, nil
	}
	if err := cl.ProduceSync(ctx, records...).FirstErr(); err != nil {
		return 0, err
	}
	return len(records), nil
}

// Consumer quản lý vòng lặp tiêu thụ Kafka của consumer group ledger-ingester.
type Consumer struct {
	client   *kgo.Client
	ingester *Ingester
	log      *slog.Logger
}

// NewConsumer tạo Kafka consumer thuộc nhóm ledger-ingester đọc từ topic sys.ledger.append.v1.
func NewConsumer(brokers []string, ingester *Ingester, log *slog.Logger) (*Consumer, error) {
	if log == nil {
		log = slog.Default()
	}
	cl, err := kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(ConsumerGroupLedgerIngester),
		kgo.ConsumeTopics(TopicLedgerAppend),
		kgo.DisableAutoCommit(),
		kgo.FetchMaxWait(25*time.Millisecond),
		kgo.FetchMaxBytes(8*1024*1024),
	)
	if err != nil {
		return nil, fmt.Errorf("ingest: khởi tạo franz-go client thất bại: %w", err)
	}
	return &Consumer{
		client:   cl,
		ingester: ingester,
		log:      log,
	}, nil
}

// Close đóng kết nối Kafka consumer.
func (c *Consumer) Close() {
	if c.client != nil {
		c.client.Close()
	}
}

type partitionBucketKey struct {
	TenantID       uuid.UUID
	ChainPartition int16
}

type partitionBucket struct {
	events  []EventInput
	records []*kgo.Record
}

// PollAndIngestOnce đọc tối đa maxPollRecords từ Kafka, gom theo (tenant_id, chain_partition)
// thành các lô <= 200 bản ghi, ghi vào PostgreSQL và chỉ commit offset sau khi giao dịch thành công.
func (c *Consumer) PollAndIngestOnce(ctx context.Context, maxPollRecords int) (int, error) {
	if maxPollRecords <= 0 {
		maxPollRecords = 2000
	}
	fetches := c.client.PollRecords(ctx, maxPollRecords)
	if errs := fetches.Errors(); len(errs) > 0 {
		for _, fe := range errs {
			if errors.Is(fe.Err, context.Canceled) || errors.Is(fe.Err, context.DeadlineExceeded) {
				return 0, fe.Err
			}
			c.log.Error("lỗi đọc Kafka trên sys.ledger.append.v1", "topic", fe.Topic, "partition", fe.Partition, "err", fe.Err)
		}
	}

	records := fetches.Records()
	if len(records) == 0 {
		return 0, nil
	}

	buckets := make(map[partitionBucketKey]*partitionBucket)
	orderedKeys := make([]partitionBucketKey, 0, 64)
	skippedRecords := make([]*kgo.Record, 0)

	for _, rec := range records {
		var ev EventInput
		if err := json.Unmarshal(rec.Value, &ev); err != nil {
			c.log.Error("bản ghi Kafka không đúng định dạng JSON EventInput", "offset", rec.Offset, "err", err)
			skippedRecords = append(skippedRecords, rec)
			continue
		}
		if !IsLegalEvent(ev.EventType) {
			skippedRecords = append(skippedRecords, rec)
			continue
		}
		cp := ev.ChainPartition()
		bk := partitionBucketKey{TenantID: ev.TenantID, ChainPartition: cp}
		b, ok := buckets[bk]
		if !ok {
			b = &partitionBucket{}
			buckets[bk] = b
			orderedKeys = append(orderedKeys, bk)
		}
		b.events = append(b.events, ev)
		b.records = append(b.records, rec)
	}

	if len(skippedRecords) > 0 {
		if err := c.client.CommitRecords(ctx, skippedRecords...); err != nil {
			return 0, err
		}
	}

	totalAppended := 0
	for _, bk := range orderedKeys {
		b := buckets[bk]
		for start := 0; start < len(b.events); start += MaxBatchSize {
			end := start + MaxBatchSize
			if end > len(b.events) {
				end = len(b.events)
			}
			chunkEvents := b.events[start:end]
			chunkRecords := b.records[start:end]

			batchRes, err := c.ingester.AppendBatch(ctx, bk.TenantID, bk.ChainPartition, chunkEvents)
			if err != nil {
				if errors.Is(err, ErrPartitionLocked) {
					// Tạm dừng phân vùng Kafka bị khoá sự cố và phát cảnh báo
					pauseMap := make(map[string][]int32)
					for _, r := range chunkRecords {
						pauseMap[r.Topic] = append(pauseMap[r.Topic], r.Partition)
					}
					c.client.PauseFetchPartitions(pauseMap)
					c.log.Error("tạm dừng tiêu thụ phân vùng do chuỗi băm đang bị khoá sự cố (BR-13-11)",
						"tenant_id", bk.TenantID,
						"chain_partition", bk.ChainPartition,
						"err", err,
					)
				}
				return totalAppended, err
			}

			totalAppended += len(batchRes.Appended)
			// Chỉ commit offset sau khi giao dịch DB đã commit thành công!
			if err := c.client.CommitRecords(ctx, chunkRecords...); err != nil {
				return totalAppended, fmt.Errorf("ingest: commit offset Kafka thất bại sau khi ghi DB: %w", err)
			}
		}
	}

	return totalAppended, nil
}

// Run chạy vòng lặp tiêu thụ liên tục cho đến khi ctx bị huỷ.
func (c *Consumer) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
			if _, err := c.PollAndIngestOnce(ctx, 2000); err != nil {
				if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
					return nil
				}
				c.log.Error("lỗi xử lý lô sự kiện sổ cái", "err", err)
			}
		}
	}
}
