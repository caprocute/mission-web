// Package export hiện thực tác vụ xuất gói bằng chứng sổ cái của DV-09-05 (oversight.yaml createLedgerExport,
// BR-13-12, MSG-13-07): gom bản ghi đã niêm phong trong phạm vi lọc cùng các đoạn chuỗi nối tới đầu chuỗi đã
// niêm phong, niêm phong, bản neo, chứng thư và lần kiểm tra toàn vẹn, dựng gói theo package bundle, ký kê
// khai bằng khoá của ledger-service và lưu vào kho đối tượng.
package export

import (
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/ledger/internal/bundle"
	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/partition"
	"mission/services-go/ledger/internal/seal"
)

// Trạng thái tác vụ (ck_export_job__status) và thông điệp.
const (
	StatusPending = "CHO"
	StatusRunning = "DANG_CHAY"
	StatusDone    = "HOAN_TAT"
	StatusFailed  = "THAT_BAI"

	// MsgReady là MSG-13-07 của TL-02b SCR-1302.
	MsgReady = "Tệp bằng chứng đã sẵn sàng. Có thể kiểm tra độc lập bằng công cụ dòng lệnh ledger-verify."
	msgFail  = "Không xuất được gói bằng chứng, vui lòng thử lại sau."
)

var (
	// ErrNotFound: không có tác vụ trong tenant.
	ErrNotFound = errors.New("export: không tìm thấy tác vụ xuất")
	// ErrIdempotencyMismatch: Idempotency-Key đã dùng với thân khác (COMMON-422-IDEMPOTENCY-MISMATCH).
	ErrIdempotencyMismatch = errors.New("export: Idempotency-Key đã dùng với yêu cầu khác")
)

// Request là phạm vi xuất.
type Request struct {
	AggregateType      string     `json:"aggregateType,omitempty"`
	AggregateID        *uuid.UUID `json:"aggregateId,omitempty"`
	EventType          string     `json:"eventType,omitempty"`
	From               time.Time  `json:"from"`
	To                 time.Time  `json:"to"`
	IncludeVerifyRunID *uuid.UUID `json:"includeVerifyRunId,omitempty"`
}

// Job là một dòng ledger.export_job.
type Job struct {
	ID          uuid.UUID
	TenantID    uuid.UUID
	RequestedBy uuid.UUID
	Status      string
	Detail      string
	ObjectKey   string
	SHA256      []byte
	Size        int64
	Entries     int64
	CreatedAt   time.Time
	FinishedAt  *time.Time
}

// Store lưu gói vào kho đối tượng và cấp đường dẫn tải về.
type Store interface {
	Put(ctx context.Context, key string, body io.Reader, size int64) error
	Location(ctx context.Context, key string) (string, error)
}

// DirStore lưu gói ra thư mục cục bộ; dùng ở dev và kiểm thử thay bucket kho đối tượng. URLBase là tiền tố
// đường dẫn tải về (rỗng thì trả đường dẫn tệp).
type DirStore struct {
	Dir     string
	URLBase string
}

// Put ghi tệp quyền 0440, không ghi đè.
func (d DirStore) Put(_ context.Context, key string, body io.Reader, _ int64) error {
	p := filepath.Join(d.Dir, filepath.FromSlash(key))
	if err := os.MkdirAll(filepath.Dir(p), 0o750); err != nil {
		return err
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o440)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, body); err != nil {
		_ = f.Close()
		return err
	}
	return f.Close()
}

// Location là URLBase + khoá, hoặc đường dẫn tệp.
func (d DirStore) Location(_ context.Context, key string) (string, error) {
	if d.URLBase != "" {
		return strings.TrimRight(d.URLBase, "/") + "/" + key, nil
	}
	return filepath.Join(d.Dir, filepath.FromSlash(key)), nil
}

// Service tạo và chạy tác vụ xuất.
type Service struct {
	Pool   *pgxpool.Pool
	Cell   string
	Signer bundle.ManifestSigner
	// Certs là chứng thư khoá niêm phong theo SHA-256, đưa vào gói.
	Certs map[[32]byte]*x509.Certificate
	Store Store
	Log   *slog.Logger
	Now   func() time.Time
	wg    sync.WaitGroup
}

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Service) log() *slog.Logger {
	if s.Log != nil {
		return s.Log
	}
	return slog.Default()
}

func inTenant(ctx context.Context, pool *pgxpool.Pool, t uuid.UUID, fn func(pgx.Tx) error) error {
	return pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, t.String()); err != nil {
			return err
		}
		return fn(tx)
	})
}

const jobCols = `id, tenant_id, requested_by, status, coalesce(status_detail, ''), coalesce(object_key, ''), bundle_sha256,
	coalesce(size_bytes, 0), entry_count, created_at, finished_at`

func scanJob(r pgx.Row) (*Job, error) {
	j := &Job{}
	err := r.Scan(&j.ID, &j.TenantID, &j.RequestedBy, &j.Status, &j.Detail, &j.ObjectKey, &j.SHA256, &j.Size, &j.Entries,
		&j.CreatedAt, &j.FinishedAt)
	return j, err
}

// Create ghi tác vụ theo Idempotency-Key và chạy nền; cùng khoá cùng thân trả tác vụ cũ (existing=true).
// requestHash là SHA-256 của thân yêu cầu gốc.
func (s *Service) Create(ctx context.Context, tenant, actor uuid.UUID, key string, req Request, requestHash [32]byte) (*Job, bool, error) {
	filter, _ := json.Marshal(req)
	var (
		job      *Job
		existing bool
		stored   []byte
	)
	err := inTenant(ctx, s.Pool, tenant, func(tx pgx.Tx) error {
		id := uuid.Must(uuid.NewV7())
		ct, err := tx.Exec(ctx, `INSERT INTO ledger.export_job (id, tenant_id, requested_by, idempotency_key, request_hash,
				filter_json, include_verify_run_id) VALUES ($1,$2,$3,$4,$5,$6::jsonb,$7)
			ON CONFLICT (tenant_id, requested_by, idempotency_key) DO NOTHING`,
			id, tenant, actor, key, requestHash[:], string(filter), req.IncludeVerifyRunID)
		if err != nil {
			return err
		}
		existing = ct.RowsAffected() == 0
		row := tx.QueryRow(ctx, `SELECT `+jobCols+`, request_hash FROM ledger.export_job
			WHERE tenant_id = $1 AND requested_by = $2 AND idempotency_key = $3`, tenant, actor, key)
		job = &Job{}
		return row.Scan(&job.ID, &job.TenantID, &job.RequestedBy, &job.Status, &job.Detail, &job.ObjectKey, &job.SHA256,
			&job.Size, &job.Entries, &job.CreatedAt, &job.FinishedAt, &stored)
	})
	if err != nil {
		return nil, false, err
	}
	if existing && string(stored) != string(requestHash[:]) {
		return nil, true, ErrIdempotencyMismatch
	}
	if !existing {
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.Execute(context.WithoutCancel(ctx), job.TenantID, job.ID, req)
		}()
	}
	return job, existing, nil
}

// Wait chờ các tác vụ đang chạy nền (kiểm thử, tắt dịch vụ).
func (s *Service) Wait() { s.wg.Wait() }

// Get đọc tác vụ trong tenant.
func (s *Service) Get(ctx context.Context, tenant, id uuid.UUID) (*Job, error) {
	var j *Job
	err := inTenant(ctx, s.Pool, tenant, func(tx pgx.Tx) error {
		var err error
		j, err = scanJob(tx.QueryRow(ctx, `SELECT `+jobCols+` FROM ledger.export_job WHERE tenant_id = $1 AND id = $2`, tenant, id))
		return err
	})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return j, err
}

// Location trả đường dẫn tải gói của tác vụ đã xong.
func (s *Service) Location(ctx context.Context, j *Job) (string, error) {
	if j.Status != StatusDone || j.ObjectKey == "" || s.Store == nil {
		return "", nil
	}
	return s.Store.Location(ctx, j.ObjectKey)
}

func (s *Service) setStatus(ctx context.Context, tenant, id uuid.UUID, status, detail string, extra func(pgx.Tx) error) error {
	return inTenant(ctx, s.Pool, tenant, func(tx pgx.Tx) error {
		fin := any(nil)
		if status == StatusDone || status == StatusFailed {
			fin = s.now().UTC()
		}
		if _, err := tx.Exec(ctx, `UPDATE ledger.export_job SET status = $3, status_detail = $4, finished_at = $5
			WHERE tenant_id = $1 AND id = $2`, tenant, id, status, detail, fin); err != nil {
			return err
		}
		if extra != nil {
			return extra(tx)
		}
		return nil
	})
}

// Execute dựng gói, lưu kho đối tượng và ghi kết quả; lỗi đánh dấu THAT_BAI.
func (s *Service) Execute(ctx context.Context, tenant, id uuid.UUID, req Request) {
	if err := s.setStatus(ctx, tenant, id, StatusRunning, "Đang xuất gói bằng chứng.", nil); err != nil {
		s.log().Error("không cập nhật được tác vụ xuất", "err", err.Error())
		return
	}
	if err := s.execute(ctx, tenant, id, req); err != nil {
		s.log().Error("xuất gói bằng chứng thất bại", "job", id, "err", err.Error())
		if err := s.setStatus(ctx, tenant, id, StatusFailed, msgFail, nil); err != nil {
			s.log().Error("không cập nhật được tác vụ xuất", "err", err.Error())
		}
	}
}

func (s *Service) execute(ctx context.Context, tenant, id uuid.UUID, req Request) error {
	b, certs, err := s.Build(ctx, tenant, req)
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp("", "ledger-export-*.tar")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()
	h := sha256.New()
	if err := bundle.Write(io.MultiWriter(tmp, h), b, s.Signer, certs); err != nil {
		return err
	}
	size, err := tmp.Seek(0, io.SeekCurrent)
	if err != nil {
		return err
	}
	if _, err := tmp.Seek(0, io.SeekStart); err != nil {
		return err
	}
	key := fmt.Sprintf("ledger-export/%s/%s.tar", tenant, id)
	if s.Store == nil {
		return errors.New("export: chưa cấu hình kho lưu gói")
	}
	if err := s.Store.Put(ctx, key, tmp, size); err != nil {
		return fmt.Errorf("export: lưu gói: %w", err)
	}
	sum := h.Sum(nil)
	return s.setStatus(ctx, tenant, id, StatusDone, MsgReady, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `UPDATE ledger.export_job SET object_key = $3, bundle_sha256 = $4, size_bytes = $5, entry_count = $6
			WHERE tenant_id = $1 AND id = $2`, tenant, id, key, sum, size, b.Manifest.EntriesInScope)
		return err
	})
}

// SignerOf dùng khoá niêm phong trong tiến trình làm khoá ký kê khai (dev và kiểm thử; production gọi HSM qua
// esign-service như niêm phong, suy luận).
func SignerOf(k *seal.KeySigner) bundle.ManifestSigner {
	return bundle.ManifestSigner{KeyID: k.KeyID, CertDER: k.Cert.Raw, Sign: func(msg []byte) ([]byte, error) {
		s, err := k.Sign(context.Background(), msg)
		return s.Value, err
	}}
}

type interval struct{ from, to int64 }

// Build dựng nội dung gói (chưa ký). Trả cả chứng thư DER của các khoá niêm phong trong gói.
func (s *Service) Build(ctx context.Context, tenant uuid.UUID, req Request) (*bundle.Bundle, [][]byte, error) {
	b := &bundle.Bundle{Entries: bundle.Entries{Chains: []bundle.Chain{}}}
	filter := map[string]any{"from": canon.FormatTimestamp(req.From), "to": canon.FormatTimestamp(req.To)}
	if req.AggregateType != "" {
		filter["aggregateType"] = req.AggregateType
	}
	if req.AggregateID != nil {
		filter["aggregateId"] = req.AggregateID.String()
	}
	if req.EventType != "" {
		filter["eventType"] = req.EventType
	}
	b.Manifest = bundle.Manifest{TenantID: tenant.String(), CellCode: s.Cell, CreatedAt: canon.FormatTimestamp(s.now()), Filter: filter}
	var certs [][]byte
	err := inTenant(ctx, s.Pool, tenant, func(tx pgx.Tx) error {
		// 1. Bản ghi trong phạm vi lọc, theo chuỗi.
		where := []string{"tenant_id = $1", "recorded_month = ANY($2)", "recorded_at >= $3", "recorded_at <= $4"}
		args := []any{tenant, months(req.From, req.To), req.From, req.To}
		add := func(cond string, v any) {
			args = append(args, v)
			where = append(where, fmt.Sprintf(cond, len(args)))
		}
		if req.AggregateType != "" {
			add("aggregate_type = $%d", strings.ToUpper(req.AggregateType))
		}
		if req.AggregateID != nil {
			add("aggregate_id = $%d", *req.AggregateID)
		}
		if req.EventType != "" {
			add("event_type = $%d", req.EventType)
		}
		rows, err := tx.Query(ctx, `SELECT chain_partition, seq_no, recorded_at FROM ledger.ledger_entry WHERE `+
			strings.Join(where, " AND ")+` ORDER BY chain_partition, seq_no`, args...)
		if err != nil {
			return err
		}
		inScope := map[int16][]int64{}
		var minRec, maxRec time.Time
		for rows.Next() {
			var p int16
			var seq int64
			var rec time.Time
			if err := rows.Scan(&p, &seq, &rec); err != nil {
				rows.Close()
				return err
			}
			inScope[p] = append(inScope[p], seq)
			if minRec.IsZero() || rec.Before(minRec) {
				minRec = rec
			}
			if rec.After(maxRec) {
				maxRec = rec
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		if len(inScope) == 0 {
			return nil
		}
		// 2. Niêm phong có thể chứa các bản ghi đó: cửa sổ [window_start, window_end) giao khoảng ghi.
		seals, err := loadSeals(ctx, tx, tenant, minRec, maxRec)
		if err != nil {
			return err
		}
		// 3. Mỗi bản ghi đã niêm phong mở một đoạn [seq, đầu chuỗi của niêm phong chứa nó]; gộp các đoạn chồng nhau.
		used := map[uuid.UUID]bool{}
		chains := make([]int16, 0, len(inScope))
		for p := range inScope {
			chains = append(chains, p)
		}
		sort.Slice(chains, func(i, j int) bool { return chains[i] < chains[j] })
		for _, p := range chains {
			var ivs []interval
			scope := map[int64]bool{}
			for _, seq := range inScope[p] {
				to, ok := sealedTo(seals, p, seq)
				if !ok {
					b.Manifest.UnsealedExcluded++
					continue
				}
				scope[seq] = true
				b.Manifest.EntriesInScope++
				if n := len(ivs); n > 0 && seq <= ivs[n-1].to+1 {
					ivs[n-1].to = max(ivs[n-1].to, to)
					continue
				}
				ivs = append(ivs, interval{seq, to})
			}
			if len(ivs) == 0 {
				continue
			}
			ch := bundle.Chain{ChainPartition: p}
			for _, iv := range ivs {
				recs, err := loadRecords(ctx, tx, tenant, p, iv, scope)
				if err != nil {
					return err
				}
				ch.Segments = append(ch.Segments, bundle.Segment{Records: recs})
				for _, sl := range seals {
					for _, rg := range sl.Ranges {
						if rg.Partition == p && rg.To >= iv.from && rg.To <= iv.to {
							used[uuid.MustParse(sl.ID)] = true
						}
					}
				}
			}
			b.Entries.Chains = append(b.Entries.Chains, ch)
		}
		// 4. Niêm phong, bản neo, chứng thư.
		certSeen := map[string]bool{}
		for _, sl := range seals {
			if !used[uuid.MustParse(sl.ID)] {
				continue
			}
			b.Seals = append(b.Seals, sl)
			if !certSeen[sl.Cell.SignerCertSHA256] {
				certSeen[sl.Cell.SignerCertSHA256] = true
				if c, ok := s.Certs[[32]byte(mustHex(sl.Cell.SignerCertSHA256))]; ok {
					certs = append(certs, c.Raw)
				}
			}
			arows, err := tx.Query(ctx, `SELECT anchor_target, receipt, anchored_at FROM ledger.anchor_receipt
				WHERE cell_seal_id = $1 ORDER BY anchored_at, anchor_target`, uuid.MustParse(sl.Cell.ID))
			if err != nil {
				return err
			}
			for arows.Next() {
				a := bundle.Anchor{CellSealID: sl.Cell.ID}
				var at time.Time
				if err := arows.Scan(&a.Target, &a.Receipt, &at); err != nil {
					arows.Close()
					return err
				}
				a.AnchoredAt = canon.FormatTimestamp(at)
				b.Anchors = append(b.Anchors, a)
			}
			arows.Close()
		}
		return nil
	})
	if err != nil {
		return nil, nil, err
	}
	vr, err := s.verifyRun(ctx, tenant, req.IncludeVerifyRunID)
	if err != nil {
		return nil, nil, err
	}
	b.VerifyRun = vr
	return b, certs, nil
}

func mustHex(s string) []byte {
	b, _ := hex.DecodeString(s)
	if len(b) != 32 {
		return make([]byte, 32)
	}
	return b
}

func months(from, to time.Time) []int32 {
	var out []int32
	m := time.Date(from.UTC().Year(), from.UTC().Month(), 1, 0, 0, 0, 0, time.UTC)
	for !m.After(to) {
		out = append(out, int32(partition.RecordedMonth(m)))
		m = m.AddDate(0, 1, 0)
	}
	return out
}

func sealedTo(seals []bundle.TenantSeal, p int16, seq int64) (int64, bool) {
	for _, sl := range seals {
		for _, rg := range sl.Ranges {
			if rg.Partition == p && rg.From <= seq && seq <= rg.To {
				return rg.To, true
			}
		}
	}
	return 0, false
}

func loadSeals(ctx context.Context, tx pgx.Tx, tenant uuid.UUID, minRec, maxRec time.Time) ([]bundle.TenantSeal, error) {
	rows, err := tx.Query(ctx, `SELECT ms.id, ms.window_start, ms.window_end, ms.leaf_count, ms.tenant_root_hash, ms.prev_seal_id,
			ms.prev_root_hash, ms.seq_ranges, ms.inclusion_proof, cs.id, cs.cell_code, cs.window_start, cs.window_end,
			cs.cell_root_hash, cs.prev_cell_root_hash, cs.signer_key_id, cs.signer_cert_sha256, cs.signature, cs.rfc3161_tst,
			cs.tsa_gen_time, cs.tsa_late
		FROM ledger.merkle_seal ms JOIN ledger.cell_seal cs ON cs.id = ms.cell_seal_id
		WHERE ms.tenant_id = $1 AND ms.window_end > $2 AND ms.window_start <= $3
		ORDER BY ms.window_end`, tenant, minRec, maxRec)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []bundle.TenantSeal
	for rows.Next() {
		var (
			sl                               bundle.TenantSeal
			id, cellID                       uuid.UUID
			ws, we, cws, cwe                 time.Time
			root, prevRoot, cellRoot, prevCR []byte
			certSHA                          []byte
			prevSeal                         *uuid.UUID
			ranges, proof                    []byte
			gen                              *time.Time
		)
		if err := rows.Scan(&id, &ws, &we, &sl.EntryCount, &root, &prevSeal, &prevRoot, &ranges, &proof, &cellID, &sl.Cell.CellCode,
			&cws, &cwe, &cellRoot, &prevCR, &sl.Cell.SignerKeyID, &certSHA, &sl.Cell.Signature, &sl.Cell.TSAToken, &gen,
			&sl.Cell.TSALate); err != nil {
			return nil, err
		}
		sl.ID, sl.Cell.ID = id.String(), cellID.String()
		sl.WindowStart, sl.WindowEnd = canon.FormatTimestamp(ws), canon.FormatTimestamp(we)
		sl.Cell.WindowStart, sl.Cell.WindowEnd = canon.FormatTimestamp(cws), canon.FormatTimestamp(cwe)
		sl.TenantRoot, sl.Cell.CellRoot, sl.Cell.PrevCellRoot = hex.EncodeToString(root), hex.EncodeToString(cellRoot), hex.EncodeToString(prevCR)
		sl.Cell.SignerCertSHA256 = hex.EncodeToString(certSHA)
		if prevSeal != nil {
			v := hex.EncodeToString(prevRoot)
			sl.PrevTenantRoot = &v
		}
		if gen != nil {
			v := canon.FormatTimestamp(*gen)
			sl.Cell.TSAGenTime = &v
		}
		if err := json.Unmarshal(ranges, &sl.Ranges); err != nil {
			return nil, err
		}
		var ip struct {
			Index int `json:"cellLeafIndex"`
			Path  []struct {
				S string `json:"s"`
				L bool   `json:"l"`
			} `json:"path"`
		}
		if err := json.Unmarshal(proof, &ip); err != nil {
			return nil, err
		}
		sl.CellLeafIndex = ip.Index
		sl.CellPath = []bundle.Step{}
		for _, st := range ip.Path {
			side := "PHAI"
			if st.L {
				side = "TRAI"
			}
			sl.CellPath = append(sl.CellPath, bundle.Step{Side: side, Hash: st.S})
		}
		out = append(out, sl)
	}
	return out, rows.Err()
}

func loadRecords(ctx context.Context, tx pgx.Tx, tenant uuid.UUID, p int16, iv interval, scope map[int64]bool) ([]bundle.Record, error) {
	rows, err := tx.Query(ctx, `SELECT seq_no, id, event_id, event_type, aggregate_type, aggregate_id, actor_id, actor_kind,
			occurred_at, recorded_at, metadata, payload_hash, metadata_hash, prev_hash, entry_hash
		FROM ledger.ledger_entry WHERE tenant_id = $1 AND chain_partition = $2 AND seq_no BETWEEN $3 AND $4 ORDER BY seq_no`,
		tenant, p, iv.from, iv.to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []bundle.Record
	for rows.Next() {
		var (
			r                       bundle.Record
			id, ev, agg             uuid.UUID
			actor                   *uuid.UUID
			occ, rec                time.Time
			meta                    []byte
			ph, mh, prev, entryHash []byte
		)
		if err := rows.Scan(&r.SeqNo, &id, &ev, &r.EventType, &r.AggregateType, &agg, &actor, &r.ActorKind, &occ, &rec, &meta,
			&ph, &mh, &prev, &entryHash); err != nil {
			return nil, err
		}
		r.ID, r.EventID, r.AggregateID = id.String(), ev.String(), agg.String()
		if actor != nil {
			v := actor.String()
			r.ActorID = &v
		}
		r.OccurredAt, r.RecordedAt = canon.FormatTimestamp(occ), canon.FormatTimestamp(rec)
		r.PayloadHash, r.MetadataHash = hex.EncodeToString(ph), hex.EncodeToString(mh)
		r.PrevHash, r.EntryHash = hex.EncodeToString(prev), hex.EncodeToString(entryHash)
		if r.MetadataSHA256, err = canon.MetadataDigestJSON(meta); err != nil {
			return nil, fmt.Errorf("export: băm metadata bản ghi %d: %w", r.SeqNo, err)
		}
		r.InScope = scope[r.SeqNo]
		if r.InScope && len(meta) > 0 {
			_ = json.Unmarshal(meta, &r.Metadata)
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// verifyRun là lần kiểm tra toàn vẹn đính kèm: lần được chỉ định, hoặc lần hoàn tất gần nhất của tenant.
func (s *Service) verifyRun(ctx context.Context, tenant uuid.UUID, id *uuid.UUID) (map[string]any, error) {
	out := map[string]any{}
	err := inTenant(ctx, s.Pool, tenant, func(tx pgx.Tx) error {
		q := `SELECT id, verify_kind, range_from, range_to, entries_checked, mismatches, status, finished_at
			FROM ledger.verify_run WHERE (tenant_id = $1 OR tenant_id IS NULL) AND status = 'HOAN_TAT'`
		args := []any{tenant}
		if id != nil {
			q += ` AND id = $2`
			args = append(args, *id)
		}
		var (
			rid          uuid.UUID
			kind, status string
			from, to     time.Time
			entries      int64
			mismatches   int
			finished     *time.Time
		)
		err := tx.QueryRow(ctx, q+` ORDER BY started_at DESC LIMIT 1`, args...).
			Scan(&rid, &kind, &from, &to, &entries, &mismatches, &status, &finished)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		out = map[string]any{"id": rid.String(), "verifyKind": kind, "rangeFrom": canon.FormatTimestamp(from),
			"rangeTo": canon.FormatTimestamp(to), "entriesChecked": entries, "mismatches": mismatches, "status": status}
		if finished != nil {
			out["finishedAt"] = canon.FormatTimestamp(*finished)
		}
		return nil
	})
	return out, err
}
