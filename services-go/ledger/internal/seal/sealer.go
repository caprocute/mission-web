package seal

import (
	"context"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/linkedin/goavro/v2"

	"vn.viettel.okrkpi/services-go/vtplatform/db"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/outbox"
	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

// Loại niêm phong.
const (
	KindPeriodic = "DINH_KY"
	KindAdHoc    = "DOT_XUAT"

	// TopicSealCreated là topic EVT-19.
	TopicSealCreated = "ledger.seal.created.v1"
)

//go:embed SoCaiDaNiemPhong.avsc
var sealSchema string

var sealCodec = func() *goavro.Codec {
	c, err := goavro.NewCodec(sealSchema)
	if err != nil {
		panic(fmt.Sprintf("seal: lược đồ EVT-19: %v", err))
	}
	return c
}()

// ErrTSAUnavailable: TSA không cấp được token; cửa sổ chưa ghi và sẽ được gộp vào lần sau.
var ErrTSAUnavailable = errors.New("seal: TSA chưa cấp dấu thời gian, cửa sổ gộp vào lần niêm phong sau")

// ChainRange là khoảng seq_no mới của một chuỗi trong cửa sổ.
type ChainRange struct {
	Partition int16  `json:"p"`
	From      int64  `json:"from"`
	To        int64  `json:"to"`
	Tip       string `json:"tip"`
}

// TenantSeal là niêm phong mức tenant của một cửa sổ.
type TenantSeal struct {
	ID        uuid.UUID
	TenantID  uuid.UUID
	Ranges    []ChainRange
	Entries   int64
	Root      [32]byte
	PrevID    *uuid.UUID
	PrevRoot  [32]byte
	LeafIndex int
	Path      []Step
}

// Result là kết quả một lần niêm phong.
type Result struct {
	CellSealID  uuid.UUID
	WindowStart time.Time
	WindowEnd   time.Time
	CellRoot    [32]byte
	PrevRoot    [32]byte
	Tenants     []TenantSeal
	Signature   Signature
	Token       Token
	Late        bool
	// Existing là true khi cửa sổ đã được niêm phong trước đó (chạy lại không tạo trùng).
	Existing bool
}

// Sealer dựng cây, ký, lấy dấu thời gian rồi ghi cell_seal, merkle_seal, chain_head và EVT-19 trong
// một giao dịch, đúng thứ tự TL-03.
type Sealer struct {
	Pool     *pgxpool.Pool
	Signer   Signer
	TSA      TSA
	Cell     string
	Interval time.Duration
	// Schedule (tuỳ chọn) quyết định độ dài cửa sổ theo thời điểm; nil thì dùng Interval.
	Schedule *Schedule
	OTel     *otel.Provider
	Log      *slog.Logger
	Now      func() time.Time

	outbox *outbox.Writer
}

func (s *Sealer) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Sealer) log() *slog.Logger {
	if s.Log != nil {
		return s.Log
	}
	return slog.Default()
}

func (s *Sealer) count(name string) {
	if s.OTel != nil {
		s.OTel.AddCounter(name, 1)
	}
}

// lastCellSeal là cửa sổ đã niêm phong gần nhất của cell.
func (s *Sealer) lastCellSeal(ctx context.Context) (*uuid.UUID, time.Time, [32]byte, error) {
	var (
		id   uuid.UUID
		end  time.Time
		r    []byte
		root [32]byte
	)
	err := s.Pool.QueryRow(ctx,
		`SELECT id, window_end, cell_root_hash FROM ledger.cell_seal WHERE cell_code = $1 ORDER BY window_end DESC LIMIT 1`,
		s.Cell).Scan(&id, &end, &r)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, time.Time{}, root, nil
	}
	if err != nil {
		return nil, time.Time{}, root, err
	}
	copy(root[:], r)
	return &id, end, root, nil
}

// Tenants là danh sách tenant có phân vùng sổ cái trong cell, đọc từ danh mục phân vùng LIST của
// ledger.ledger_entry vì danh bạ tenant không nằm ở SVC-09 và RLS chặn đọc chéo tenant (suy luận).
func (s *Sealer) Tenants(ctx context.Context) ([]uuid.UUID, error) {
	rows, err := s.Pool.Query(ctx, `
		SELECT (regexp_match(pg_get_expr(c.relpartbound, c.oid), '''([0-9a-fA-F-]{36})'''))[1]::uuid
		FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
		WHERE i.inhparent = 'ledger.ledger_entry'::regclass`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []uuid.UUID
	for rows.Next() {
		var id uuid.UUID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].String() < out[j].String() })
	return out, rows.Err()
}

func inTenantRead(ctx context.Context, pool *pgxpool.Pool, t uuid.UUID, fn func(pgx.Tx) error) error {
	return pgx.BeginTxFunc(ctx, pool, pgx.TxOptions{AccessMode: pgx.ReadOnly}, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, t.String()); err != nil {
			return err
		}
		return fn(tx)
	})
}

// pending đọc khoảng seq_no chưa niêm phong (ghi trước windowEnd) của mọi chuỗi một tenant.
// oldest là thời điểm ghi sớm nhất trong các bản ghi đó.
func (s *Sealer) pending(ctx context.Context, t uuid.UUID, windowEnd time.Time) ([]ChainRange, int64, time.Time, error) {
	var ranges []ChainRange
	var total int64
	var oldest time.Time
	err := inTenantRead(ctx, s.Pool, t, func(tx pgx.Tx) error {
		ranges, total, oldest = nil, 0, time.Time{}
		rows, err := tx.Query(ctx, `
			WITH r AS (
				SELECT h.chain_partition, h.last_sealed_seq_no AS sealed, min(e.seq_no) AS lo, max(e.seq_no) AS hi,
					min(e.recorded_at) AS first
				FROM ledger.chain_head h
				JOIN ledger.ledger_entry e ON e.tenant_id = h.tenant_id AND e.chain_partition = h.chain_partition
				WHERE h.tenant_id = $1 AND e.seq_no > h.last_sealed_seq_no AND e.recorded_at < $2
				GROUP BY h.chain_partition, h.last_sealed_seq_no)
			SELECT r.chain_partition, r.sealed, r.lo, r.hi, r.first, tip.entry_hash
			FROM r JOIN ledger.ledger_entry tip
				ON tip.tenant_id = $1 AND tip.chain_partition = r.chain_partition AND tip.seq_no = r.hi
			ORDER BY r.chain_partition`, t, windowEnd)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var (
				r      ChainRange
				sealed int64
				lo     int64
				first  time.Time
				tip    []byte
			)
			if err := rows.Scan(&r.Partition, &sealed, &lo, &r.To, &first, &tip); err != nil {
				return err
			}
			// Khoảng bắt đầu ngay sau phần đã niêm phong; chuỗi chuyển cell (TENANT_MOVED_IN) bắt đầu
			// ở seq_no cuối của cell nguồn cộng một.
			r.From = sealed + 1
			if sealed == 0 {
				r.From = lo
			}
			r.Tip = hex.EncodeToString(tip)
			if oldest.IsZero() || first.Before(oldest) {
				oldest = first
			}
			total += r.To - r.From + 1
			ranges = append(ranges, r)
		}
		return rows.Err()
	})
	return ranges, total, oldest, err
}

// TenantRoot là gốc cây mức tenant: lá là SHA-256(0x01 || entry_hash cuối) của từng chuỗi có bản ghi
// mới, sắp theo chain_partition.
func TenantRoot(ranges []ChainRange) ([32]byte, error) {
	leaves := make([][32]byte, 0, len(ranges))
	for _, r := range ranges {
		tip, err := hex.DecodeString(r.Tip)
		if err != nil || len(tip) != 32 {
			return [32]byte{}, fmt.Errorf("seal: entry_hash cuối của chuỗi %d không hợp lệ", r.Partition)
		}
		leaves = append(leaves, Leaf(tip))
	}
	return Root(leaves)
}

// SealWindow niêm phong mọi bản ghi ghi trước windowEnd chưa được niêm phong. Cửa sổ bắt đầu ở cuối lần
// niêm phong trước; chạy lại cùng windowEnd trả lần đã có. TSA lỗi thì không ghi gì và trả
// ErrTSAUnavailable: lần sau gộp cửa sổ và đánh dấu "dấu thời gian trễ".
func (s *Sealer) SealWindow(ctx context.Context, windowEnd time.Time, kind string) (*Result, error) {
	windowEnd = windowEnd.UTC().Truncate(time.Microsecond)
	if kind == "" {
		kind = KindPeriodic
	}
	interval := s.Interval
	if s.Schedule != nil {
		interval = s.Schedule.IntervalAt(windowEnd.Add(-time.Microsecond))
	}
	if interval <= 0 {
		interval = time.Hour
	}
	prevID, prevEnd, prevRoot, err := s.lastCellSeal(ctx)
	if err != nil {
		return nil, err
	}
	if prevID != nil && !windowEnd.After(prevEnd) {
		return &Result{CellSealID: *prevID, WindowEnd: prevEnd, CellRoot: prevRoot, Existing: true}, nil
	}
	start := windowEnd.Add(-interval)
	if prevID != nil {
		start = prevEnd
	}
	tenants, err := s.Tenants(ctx)
	if err != nil {
		return nil, err
	}
	res := &Result{CellSealID: uuid.Must(uuid.NewV7()), WindowStart: start, WindowEnd: windowEnd, PrevRoot: prevRoot}
	var oldest time.Time
	for _, t := range tenants {
		ranges, n, first, err := s.pending(ctx, t, windowEnd)
		if err != nil {
			return nil, fmt.Errorf("seal: đọc chuỗi tenant %s: %w", t, err)
		}
		if len(ranges) == 0 {
			continue
		}
		if oldest.IsZero() || first.Before(oldest) {
			oldest = first
		}
		root, err := TenantRoot(ranges)
		if err != nil {
			return nil, err
		}
		ts := TenantSeal{ID: uuid.Must(uuid.NewV7()), TenantID: t, Ranges: ranges, Entries: n, Root: root}
		if ts.PrevID, ts.PrevRoot, err = s.prevTenantSeal(ctx, t); err != nil {
			return nil, err
		}
		res.Tenants = append(res.Tenants, ts)
	}
	if len(res.Tenants) == 0 {
		return res, nil
	}
	cellLeaves := make([][32]byte, len(res.Tenants))
	for i, t := range res.Tenants {
		cellLeaves[i] = Leaf(t.Root[:])
	}
	res.CellRoot, _ = Root(cellLeaves)
	for i := range res.Tenants {
		res.Tenants[i].LeafIndex = i
		res.Tenants[i].Path, _ = Path(cellLeaves, i)
	}
	if res.Signature, err = s.Signer.Sign(ctx, SignedMessage(res.CellRoot, prevRoot, windowEnd)); err != nil {
		return nil, fmt.Errorf("seal: ký gốc cell: %w", err)
	}
	if res.Token, err = s.TSA.Timestamp(ctx, res.CellRoot); err != nil {
		s.count("ledger.seal.tsa_failures_total")
		s.log().Warn("TSA chưa cấp dấu thời gian, gộp cửa sổ vào lần sau", "window_start", start, "window_end", windowEnd, "err", err)
		if windowEnd.Sub(oldest) > 24*time.Hour {
			s.count("ledger.seal.tsa_overdue_total")
			s.log().Error("quá 24 giờ chưa có dấu thời gian cho niêm phong sổ cái", "window_start", start)
		}
		return nil, fmt.Errorf("%w: %v", ErrTSAUnavailable, err)
	}
	// "Dấu thời gian trễ": có bản ghi lẽ ra thuộc cửa sổ trước (TSA lỗi hoặc job không chạy); giờ trống
	// không có bản ghi thì không tính là trễ.
	res.Late = oldest.Before(windowEnd.Add(-interval))
	if err := s.write(ctx, res, prevID, kind); err != nil {
		var pe *pgconn.PgError
		if errors.As(err, &pe) && pe.Code == "23505" {
			// Lần chạy song song đã niêm phong cùng cửa sổ.
			if id, end, root, e := s.lastCellSeal(ctx); e == nil && id != nil && !windowEnd.After(end) {
				return &Result{CellSealID: *id, WindowEnd: end, CellRoot: root, Existing: true}, nil
			}
		}
		return nil, err
	}
	s.count("ledger.seal.cell_seals_total")
	return res, nil
}

func (s *Sealer) prevTenantSeal(ctx context.Context, t uuid.UUID) (*uuid.UUID, [32]byte, error) {
	var (
		root [32]byte
		id   uuid.UUID
		r    []byte
	)
	err := inTenantRead(ctx, s.Pool, t, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `SELECT id, tenant_root_hash FROM ledger.merkle_seal
			WHERE tenant_id = $1 ORDER BY window_end DESC LIMIT 1`, t).Scan(&id, &r)
	})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, root, nil
	}
	if err != nil {
		return nil, root, err
	}
	copy(root[:], r)
	return &id, root, nil
}

type pathStep struct {
	Sibling string `json:"s"`
	Left    bool   `json:"l"`
}

func (s *Sealer) write(ctx context.Context, res *Result, prevID *uuid.UUID, kind string) error {
	if s.outbox == nil {
		w, err := outbox.NewWriter("ledger", "urn:vt-okr:"+s.Cell+":ledger")
		if err != nil {
			return err
		}
		s.outbox = w
	}
	var total int64
	for _, t := range res.Tenants {
		total += t.Entries
	}
	return pgx.BeginFunc(ctx, s.Pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `INSERT INTO ledger.cell_seal (id, cell_code, window_start, window_end, cell_root_hash,
				tenant_count, total_entries, signer_key_id, signature, signer_cert_sha256, rfc3161_tst,
				prev_cell_seal_id, prev_cell_root_hash, tsa_gen_time, tsa_late, seal_kind)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)`,
			res.CellSealID, s.Cell, res.WindowStart, res.WindowEnd, res.CellRoot[:], len(res.Tenants), total,
			res.Signature.KeyID, res.Signature.Value, res.Signature.CertSHA256[:], res.Token.DER,
			prevID, res.PrevRoot[:], res.Token.GenTime, res.Late, kind); err != nil {
			return fmt.Errorf("seal: ghi cell_seal: %w", err)
		}
		for _, t := range res.Tenants {
			if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, t.TenantID.String()); err != nil {
				return err
			}
			ranges, _ := json.Marshal(t.Ranges)
			steps := make([]pathStep, len(t.Path))
			for i, p := range t.Path {
				steps[i] = pathStep{Sibling: hex.EncodeToString(p.Sibling[:]), Left: p.Left}
			}
			proof, _ := json.Marshal(map[string]any{"cellLeafIndex": t.LeafIndex, "path": steps})
			if _, err := tx.Exec(ctx, `INSERT INTO ledger.merkle_seal (id, tenant_id, cell_seal_id, window_start,
					window_end, seq_ranges, leaf_count, tenant_root_hash, inclusion_proof, prev_seal_id, prev_root_hash)
				VALUES ($1,$2,$3,$4,$5,$6::jsonb,$7,$8,$9::jsonb,$10,$11)`,
				t.ID, t.TenantID, res.CellSealID, res.WindowStart, res.WindowEnd, string(ranges), t.Entries,
				t.Root[:], string(proof), t.PrevID, t.PrevRoot[:]); err != nil {
				return fmt.Errorf("seal: ghi merkle_seal tenant %s: %w", t.TenantID, err)
			}
			for _, r := range t.Ranges {
				ct, err := tx.Exec(ctx, `UPDATE ledger.chain_head SET last_sealed_seq_no = $3
					WHERE tenant_id = $1 AND chain_partition = $2 AND last_sealed_seq_no < $3`,
					t.TenantID, r.Partition, r.To)
				if err != nil {
					return err
				}
				if ct.RowsAffected() != 1 {
					return fmt.Errorf("seal: đầu chuỗi %d của tenant %s đã đổi trong lúc niêm phong", r.Partition, t.TenantID)
				}
			}
			if err := s.emit(ctx, tx, res, t, kind); err != nil {
				return err
			}
		}
		return nil
	})
}

// emit ghi EVT-19 cho niêm phong mức tenant vào ledger.outbox_event cùng giao dịch: lược đồ
// SoCaiDaNiemPhong mang sealId = merkle_seal.id và khoá tenant:sealId, nên mỗi tenant một sự kiện.
func (s *Sealer) emit(ctx context.Context, tx pgx.Tx, res *Result, t TenantSeal, kind string) error {
	var prev any
	if t.PrevID != nil {
		prev = goavro.Union("bytes", t.PrevRoot[:])
	}
	payload, err := sealCodec.BinaryFromNative(nil, map[string]any{
		"sealId":           t.ID.String(),
		"sealKind":         kind,
		"windowStart":      res.WindowStart,
		"windowEnd":        res.WindowEnd,
		"merkleRoot":       t.Root[:],
		"prevSealRoot":     prev,
		"tsaSerial":        res.Token.Serial,
		"aggregateVersion": int32(1),
	})
	if err != nil {
		return err
	}
	tctx, err := tenant.WithTenantID(ctx, t.TenantID.String())
	if err != nil {
		return err
	}
	_, err = s.outbox.Write(tctx, pgxTx{tx}, outbox.Envelope{
		ID: t.ID.String(), AggregateType: "MerkleSeal", AggregateID: t.ID.String(), AggregateVersion: 1,
		EventType: "SoCaiDaNiemPhong", Topic: TopicSealCreated, Payload: payload, Classification: outbox.ClassificationC1,
		CreatedAt: s.now().UTC(),
	})
	return err
}

// pgxTx chuyển pgx.Tx sang giao diện db.Tx của vtplatform cho outbox.Writer.
type pgxTx struct{ pgx.Tx }

func (t pgxTx) Exec(ctx context.Context, sql string, args ...any) (db.CommandTag, error) {
	ct, err := t.Tx.Exec(ctx, sql, args...)
	return db.NewCommandTag(sql, ct.RowsAffected()), err
}

func (t pgxTx) QueryRow(ctx context.Context, sql string, args ...any) db.Row {
	return t.Tx.QueryRow(ctx, sql, args...)
}
