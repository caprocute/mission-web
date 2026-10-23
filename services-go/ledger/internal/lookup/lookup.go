// Package lookup hiện thực phần tra cứu của DV-09-05 (QT-13, SCR-1302): đọc bản ghi sổ cái theo bộ lọc,
// chi tiết một bản ghi kèm niêm phong và bản neo, và bằng chứng bao hàm hai mức của một sự kiện
// (TL-03 chương 8 "Niêm phong Merkle và dấu thời gian", ADR-26). Mọi truy vấn chạy trong giao dịch chỉ đọc
// có app.tenant_id để RLS lọc theo tenant.
package lookup

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/ledger/internal/partition"
	"mission/services-go/ledger/internal/seal"
)

var (
	// ErrNotFound: không có bản ghi trong tenant.
	ErrNotFound = errors.New("lookup: không tìm thấy bản ghi sổ cái")
	// ErrNotSealed: bản ghi chưa nằm trong lần niêm phong nào (LEDGER-409-NOT-SEALED).
	ErrNotSealed = errors.New("lookup: bản ghi chưa được niêm phong")
	// ErrRangeTooLong: khoảng tra cứu quá 2 năm (LEDGER-422-RANGE-TOO-LONG).
	ErrRangeTooLong = errors.New("lookup: khoảng thời gian quá 2 năm")
	// ErrBadCursor: con trỏ trang không đọc được.
	ErrBadCursor = errors.New("lookup: con trỏ trang không hợp lệ")
)

// Hằng số tra cứu theo SCR-1302 và baseline mục 13.
const (
	DefaultRange = 90 * 24 * time.Hour
	MaxRange     = 2 * 366 * 24 * time.Hour
	MaxLimit     = 200
)

// Entry là một dòng ledger.ledger_entry.
type Entry struct {
	ID            uuid.UUID
	Chain         int16
	SeqNo         int64
	EventID       uuid.UUID
	EventType     string
	AggregateType string
	AggregateID   uuid.UUID
	ActorID       *uuid.UUID
	ActorKind     string
	OccurredAt    time.Time
	RecordedAt    time.Time
	Metadata      map[string]any
	PayloadHash   []byte
	MetadataHash  []byte
	PrevHash      []byte
	EntryHash     []byte
	sealed        bool
}

// Seal là niêm phong mức tenant chứa bản ghi cùng niêm phong cell của nó.
type Seal struct {
	ID             uuid.UUID
	WindowStart    time.Time
	WindowEnd      time.Time
	EntryCount     int64
	TenantRoot     []byte
	PrevTenantRoot []byte // nil khi là lần niêm phong đầu của tenant
	Ranges         []seal.ChainRange
	CellLeafIndex  int
	CellPath       []seal.Step
	CellSealID     uuid.UUID
	CellRoot       []byte
	PrevCellRoot   []byte
	Signature      []byte
	SignerCertSHA  []byte
	SignatureValid *bool // nil khi không có chứng thư để kiểm
	TSAToken       []byte
	TSAGenTime     *time.Time
	TSALate        bool
}

// Anchor là một biên nhận neo của niêm phong cell.
type Anchor struct {
	ID         uuid.UUID
	Target     string
	Receipt    []byte
	AnchoredAt time.Time
}

// Link là một mắt chuỗi giữa bản ghi được hỏi và đầu chuỗi đã niêm phong: entry_hash(k) =
// SHA-256(entry_hash(k-1) || payload_hash(k) || metadata_hash(k)).
type Link struct {
	SeqNo        int64
	PayloadHash  []byte
	MetadataHash []byte
	EntryHash    []byte
}

// Proof là bằng chứng bao hàm của một sự kiện.
type Proof struct {
	Entry      Entry
	Links      []Link // seq_no+1 .. đầu chuỗi đã niêm phong
	LeafHash   [32]byte
	TenantPath []seal.Step
	Seal       Seal
	Anchors    []Anchor
}

// Filter là bộ lọc listLedgerEntries.
type Filter struct {
	AggregateType string
	AggregateID   *uuid.UUID
	EventType     string
	Chain         *int16
	From, To      time.Time
	Limit         int
	Cursor        string
}

// Service đọc sổ cái; Certs là chứng thư khoá niêm phong theo SHA-256 để báo chữ ký hợp lệ.
type Service struct {
	Pool  *pgxpool.Pool
	Certs map[[32]byte]*x509.Certificate
	Now   func() time.Time
}

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Service) read(ctx context.Context, tenant uuid.UUID, fn func(pgx.Tx) error) error {
	return pgx.BeginTxFunc(ctx, s.Pool, pgx.TxOptions{AccessMode: pgx.ReadOnly}, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tenant.String()); err != nil {
			return err
		}
		return fn(tx)
	})
}

// Normalize điền khoảng mặc định 90 ngày và kiểm giới hạn 2 năm.
func (s *Service) Normalize(f *Filter) error {
	if f.To.IsZero() {
		f.To = s.now()
	}
	if f.From.IsZero() {
		f.From = f.To.Add(-DefaultRange)
	}
	f.From, f.To = f.From.UTC(), f.To.UTC()
	if f.To.Sub(f.From) > MaxRange {
		return ErrRangeTooLong
	}
	if f.Limit <= 0 {
		f.Limit = 20
	}
	if f.Limit > MaxLimit {
		f.Limit = MaxLimit
	}
	return nil
}

// months là các tháng ghi (recorded_month) phủ [from, to] để cắt tỉa phân vùng.
func months(from, to time.Time) []int32 {
	var out []int32
	m := time.Date(from.Year(), from.Month(), 1, 0, 0, 0, 0, time.UTC)
	for !m.After(to) {
		out = append(out, int32(partition.RecordedMonth(m)))
		m = m.AddDate(0, 1, 0)
	}
	return out
}

type cursor struct {
	At time.Time `json:"t"`
	ID uuid.UUID `json:"i"`
}

func encodeCursor(e Entry) string {
	b, _ := json.Marshal(cursor{At: e.RecordedAt, ID: e.ID})
	return base64.RawURLEncoding.EncodeToString(b)
}

const entryCols = `id, chain_partition, seq_no, event_id, event_type, aggregate_type, aggregate_id, actor_id,
	actor_kind, occurred_at, recorded_at, metadata, payload_hash, metadata_hash, prev_hash, entry_hash`

func scanEntry(r pgx.Row) (Entry, error) {
	var e Entry
	var meta []byte
	err := r.Scan(&e.ID, &e.Chain, &e.SeqNo, &e.EventID, &e.EventType, &e.AggregateType, &e.AggregateID, &e.ActorID,
		&e.ActorKind, &e.OccurredAt, &e.RecordedAt, &meta, &e.PayloadHash, &e.MetadataHash, &e.PrevHash, &e.EntryHash)
	if err != nil {
		return e, err
	}
	if len(meta) > 0 {
		_ = json.Unmarshal(meta, &e.Metadata)
	}
	return e, nil
}

// List trả một trang bản ghi mới nhất trước theo (recorded_at, id) và con trỏ trang kế tiếp.
func (s *Service) List(ctx context.Context, tenant uuid.UUID, f Filter) ([]Entry, string, error) {
	if err := s.Normalize(&f); err != nil {
		return nil, "", err
	}
	where := []string{"tenant_id = $1", "recorded_month = ANY($2)", "recorded_at >= $3", "recorded_at <= $4"}
	args := []any{tenant, months(f.From, f.To), f.From, f.To}
	add := func(cond string, v any) {
		args = append(args, v)
		where = append(where, fmt.Sprintf(cond, len(args)))
	}
	if f.AggregateType != "" {
		add("aggregate_type = $%d", strings.ToUpper(f.AggregateType))
	}
	if f.AggregateID != nil {
		add("aggregate_id = $%d", *f.AggregateID)
	}
	if f.EventType != "" {
		add("event_type = $%d", f.EventType)
	}
	if f.Chain != nil {
		add("chain_partition = $%d", *f.Chain)
	}
	if f.Cursor != "" {
		raw, err := base64.RawURLEncoding.DecodeString(f.Cursor)
		var c cursor
		if err != nil || json.Unmarshal(raw, &c) != nil || c.ID == uuid.Nil {
			return nil, "", ErrBadCursor
		}
		args = append(args, c.At, c.ID)
		where = append(where, fmt.Sprintf("(recorded_at, id) < ($%d, $%d)", len(args)-1, len(args)))
	}
	q := `SELECT ` + entryCols + ` FROM ledger.ledger_entry WHERE ` + strings.Join(where, " AND ") +
		fmt.Sprintf(` ORDER BY recorded_at DESC, id DESC LIMIT %d`, f.Limit+1)
	var out []Entry
	err := s.read(ctx, tenant, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, q, args...)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			e, err := scanEntry(rows)
			if err != nil {
				return err
			}
			out = append(out, e)
		}
		return rows.Err()
	})
	if err != nil {
		return nil, "", err
	}
	next := ""
	if len(out) > f.Limit {
		out = out[:f.Limit]
		next = encodeCursor(out[len(out)-1])
	}
	return out, next, nil
}

func (s *Service) entryBy(ctx context.Context, tx pgx.Tx, tenant uuid.UUID, col string, id uuid.UUID) (Entry, error) {
	e, err := scanEntry(tx.QueryRow(ctx, `SELECT `+entryCols+` FROM ledger.ledger_entry WHERE tenant_id = $1 AND `+col+` = $2
		ORDER BY recorded_at LIMIT 1`, tenant, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return e, ErrNotFound
	}
	if err != nil {
		return e, err
	}
	var sealedTo int64
	if err := tx.QueryRow(ctx, `SELECT last_sealed_seq_no FROM ledger.chain_head WHERE tenant_id = $1 AND chain_partition = $2`,
		tenant, e.Chain).Scan(&sealedTo); err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return e, err
	}
	e.sealed = e.SeqNo <= sealedTo
	return e, nil
}

// sealOf tìm niêm phong mức tenant có khoảng seq_no của chuỗi chứa bản ghi.
func (s *Service) sealOf(ctx context.Context, tx pgx.Tx, tenant uuid.UUID, e Entry) (*Seal, error) {
	var (
		sl             Seal
		ranges, proof  []byte
		prevSeal       *uuid.UUID
		prevTenantRoot []byte
	)
	err := tx.QueryRow(ctx, `SELECT ms.id, ms.window_start, ms.window_end, ms.leaf_count, ms.tenant_root_hash, ms.prev_seal_id,
			ms.prev_root_hash, ms.seq_ranges, ms.inclusion_proof, cs.id, cs.cell_root_hash, cs.prev_cell_root_hash,
			cs.signature, cs.signer_cert_sha256, cs.rfc3161_tst, cs.tsa_gen_time, cs.tsa_late
		FROM ledger.merkle_seal ms
		JOIN ledger.cell_seal cs ON cs.id = ms.cell_seal_id
		WHERE ms.tenant_id = $1 AND ms.window_end > $2
		  AND EXISTS (SELECT 1 FROM jsonb_array_elements(ms.seq_ranges) r
		              WHERE (r->>'p')::int = $3 AND (r->>'from')::bigint <= $4 AND (r->>'to')::bigint >= $4)
		ORDER BY ms.window_end LIMIT 1`, tenant, e.RecordedAt, e.Chain, e.SeqNo).
		Scan(&sl.ID, &sl.WindowStart, &sl.WindowEnd, &sl.EntryCount, &sl.TenantRoot, &prevSeal, &prevTenantRoot,
			&ranges, &proof, &sl.CellSealID, &sl.CellRoot, &sl.PrevCellRoot, &sl.Signature, &sl.SignerCertSHA,
			&sl.TSAToken, &sl.TSAGenTime, &sl.TSALate)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotSealed
	}
	if err != nil {
		return nil, err
	}
	if prevSeal != nil {
		sl.PrevTenantRoot = prevTenantRoot
	}
	if err := json.Unmarshal(ranges, &sl.Ranges); err != nil {
		return nil, fmt.Errorf("lookup: seq_ranges của niêm phong %s: %w", sl.ID, err)
	}
	var ip struct {
		Index int `json:"cellLeafIndex"`
		Path  []struct {
			S string `json:"s"`
			L bool   `json:"l"`
		} `json:"path"`
	}
	if err := json.Unmarshal(proof, &ip); err != nil {
		return nil, fmt.Errorf("lookup: inclusion_proof của niêm phong %s: %w", sl.ID, err)
	}
	sl.CellLeafIndex = ip.Index
	for _, p := range ip.Path {
		h, err := hex.DecodeString(p.S)
		if err != nil || len(h) != 32 {
			return nil, fmt.Errorf("lookup: đường đi cell của niêm phong %s hỏng", sl.ID)
		}
		sl.CellPath = append(sl.CellPath, seal.Step{Sibling: [32]byte(h), Left: p.L})
	}
	if cert, ok := s.Certs[[32]byte(sl.SignerCertSHA)]; ok {
		msg := seal.SignedMessage([32]byte(sl.CellRoot), [32]byte(sl.PrevCellRoot), sl.WindowEnd)
		v := seal.VerifySignature(cert, msg, sl.Signature)
		sl.SignatureValid = &v
	}
	return &sl, nil
}

func (s *Service) anchors(ctx context.Context, tx pgx.Tx, cellSeal uuid.UUID) ([]Anchor, error) {
	rows, err := tx.Query(ctx, `SELECT id, anchor_target, receipt, anchored_at FROM ledger.anchor_receipt
		WHERE cell_seal_id = $1 ORDER BY anchored_at, anchor_target`, cellSeal)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Anchor
	for rows.Next() {
		var a Anchor
		if err := rows.Scan(&a.ID, &a.Target, &a.Receipt, &a.AnchoredAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// Get trả bản ghi theo id; bản ghi chưa niêm phong có seal nil.
func (s *Service) Get(ctx context.Context, tenant, id uuid.UUID) (Entry, *Seal, []Anchor, error) {
	var (
		e   Entry
		sl  *Seal
		anc []Anchor
	)
	err := s.read(ctx, tenant, func(tx pgx.Tx) error {
		var err error
		if e, err = s.entryBy(ctx, tx, tenant, "id", id); err != nil {
			return err
		}
		if !e.sealed {
			return nil
		}
		if sl, err = s.sealOf(ctx, tx, tenant, e); err != nil {
			return err
		}
		anc, err = s.anchors(ctx, tx, sl.CellSealID)
		return err
	})
	if errors.Is(err, ErrNotSealed) {
		err = nil
	}
	return e, sl, anc, err
}

// Proof dựng bằng chứng bao hàm của sự kiện: mắt chuỗi tới đầu chuỗi đã niêm phong, đường đi Merkle mức
// tenant của lá SHA-256(0x01 || entry_hash cuối), đường đi mức cell đã lưu, chữ ký, token TSA, bản neo.
func (s *Service) Proof(ctx context.Context, tenant, eventID uuid.UUID) (*Proof, error) {
	p := &Proof{}
	err := s.read(ctx, tenant, func(tx pgx.Tx) error {
		var err error
		if p.Entry, err = s.entryBy(ctx, tx, tenant, "event_id", eventID); err != nil {
			return err
		}
		if !p.Entry.sealed {
			return ErrNotSealed
		}
		sl, err := s.sealOf(ctx, tx, tenant, p.Entry)
		if err != nil {
			return err
		}
		p.Seal = *sl
		idx, to := -1, int64(0)
		leaves := make([][32]byte, len(sl.Ranges))
		for i, r := range sl.Ranges {
			tip, err := hex.DecodeString(r.Tip)
			if err != nil || len(tip) != 32 {
				return fmt.Errorf("lookup: đầu chuỗi %d của niêm phong %s hỏng", r.Partition, sl.ID)
			}
			leaves[i] = seal.Leaf(tip)
			if r.Partition == p.Entry.Chain {
				idx, to = i, r.To
				p.LeafHash = leaves[i]
			}
		}
		if idx < 0 {
			return ErrNotSealed
		}
		if p.TenantPath, err = seal.Path(leaves, idx); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT seq_no, payload_hash, metadata_hash, entry_hash FROM ledger.ledger_entry
			WHERE tenant_id = $1 AND chain_partition = $2 AND seq_no > $3 AND seq_no <= $4 ORDER BY seq_no`,
			tenant, p.Entry.Chain, p.Entry.SeqNo, to)
		if err != nil {
			return err
		}
		for rows.Next() {
			var l Link
			if err := rows.Scan(&l.SeqNo, &l.PayloadHash, &l.MetadataHash, &l.EntryHash); err != nil {
				rows.Close()
				return err
			}
			p.Links = append(p.Links, l)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		p.Anchors, err = s.anchors(ctx, tx, sl.CellSealID)
		return err
	})
	if err != nil {
		return nil, err
	}
	return p, nil
}

// Check kiểm bằng chứng như ledger-verify: nối mắt chuỗi tới đầu chuỗi, lá lên gốc tenant, gốc tenant lên
// gốc cell. Trả mô tả bước sai đầu tiên, rỗng khi khớp.
func (p *Proof) Check() string {
	h := p.Entry.EntryHash
	for _, l := range p.Links {
		sum := sha256.Sum256(append(append(append([]byte{}, h...), l.PayloadHash...), l.MetadataHash...))
		if !bytes.Equal(sum[:], l.EntryHash) {
			return fmt.Sprintf("mắt chuỗi seq_no %d không khớp", l.SeqNo)
		}
		h = l.EntryHash
	}
	if seal.Leaf(h) != p.LeafHash {
		return "lá Merkle không khớp đầu chuỗi"
	}
	if !seal.Verify(p.LeafHash, p.TenantPath, [32]byte(p.Seal.TenantRoot)) {
		return "đường đi mức tenant không ra gốc tenant"
	}
	if !seal.Verify(seal.Leaf(p.Seal.TenantRoot), p.Seal.CellPath, [32]byte(p.Seal.CellRoot)) {
		return "đường đi mức cell không ra gốc cell"
	}
	return ""
}
