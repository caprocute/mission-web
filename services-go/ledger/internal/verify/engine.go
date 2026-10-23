// Package verify kiểm tra toàn vẹn sổ cái (DV-09-04, TL-03 "Kiểm tra toàn vẹn", BR-13-10, BR-13-11): quét
// song song theo chuỗi, kiểm seq_no liên tục, prev_hash, tính lại metadata_hash và entry_hash, dựng lại gốc
// tenant tại mỗi niêm phong và đường đi lên gốc cell, kiểm chữ ký và token TSA; phá vỡ thì khoá ghi chuỗi
// và phát EVT-20 mức Cao cùng giao dịch ghi verify_run.
package verify

import (
	"bytes"
	"context"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/ingest"
	"mission/services-go/ledger/internal/seal"
)

// Bốn loại kiểm tra (ck_verify_run__kind).
const (
	KindHead    = "DAU_CHUOI"
	KindSeal    = "NIEM_PHONG"
	KindFull    = "TOAN_BO"
	KindArchive = "LUU_TRU"
)

// Lý do sai lệch ghi trong first_mismatch (suy luận, chỉ dùng nội bộ và nhật ký).
const (
	ReasonSeqGap      = "LO_SO_THU_TU"
	ReasonPrevHash    = "SAI_BAM_TRUOC"
	ReasonMetadata    = "SAI_BAM_SIEU_DU_LIEU"
	ReasonEntryHash   = "SAI_BAM_BAN_GHI"
	ReasonHead        = "SAI_DAU_CHUOI"
	ReasonSealTip     = "SAI_DAU_CHUOI_NIEM_PHONG"
	ReasonTenantRoot  = "SAI_GOC_TENANT"
	ReasonCellPath    = "SAI_DUONG_DI_CELL"
	ReasonSignature   = "SAI_CHU_KY"
	ReasonTSA         = "SAI_DAU_THOI_GIAN"
	ReasonArchiveHash = "SAI_BAN_LUU_TRU"
)

// ErrArchiveUnavailable: chưa có trình đọc tệp Parquet lưu trữ (DL-12, DL-13).
var ErrArchiveUnavailable = errors.New("verify: chưa có trình đọc bản lưu trữ")

// Request là phạm vi một lần kiểm tra. TenantID = uuid.Nil chỉ dùng cho NIEM_PHONG toàn cell.
type Request struct {
	TenantID uuid.UUID
	Kind     string
	Chain    *int16
	From     time.Time
	To       time.Time
}

// Mismatch là một sai lệch.
type Mismatch struct {
	ChainPartition *int16     `json:"chainPartition,omitempty"`
	SeqNo          *int64     `json:"seqNo,omitempty"`
	EntryID        *uuid.UUID `json:"entryId,omitempty"`
	StoredHash     string     `json:"storedHash,omitempty"`
	RecomputedHash string     `json:"recomputedHash,omitempty"`
	SealID         *uuid.UUID `json:"sealId,omitempty"`
	AnchorMatches  *bool      `json:"anchorMatches,omitempty"`
	Reason         string     `json:"reason"`
	TenantID       uuid.UUID  `json:"-"`
}

// Link là một mắt chuỗi cho sơ đồ khối SCR-1302 trường 5.
type Link struct {
	SeqNo          int64     `json:"seqNo"`
	EntryID        uuid.UUID `json:"entryId"`
	PrevHash       string    `json:"prevHash"`
	StoredHash     string    `json:"storedHash"`
	RecomputedHash string    `json:"recomputedHash"`
	Matches        bool      `json:"matches"`
}

// Report là kết quả kiểm tra.
type Report struct {
	EntriesChecked    int64
	SealsChecked      int64
	PartitionsTotal   int
	PartitionsMatched int
	Mismatches        int
	First             *Mismatch
	Chain             []Link
	// Broken là các chuỗi (tenant, phân vùng) có sai lệch cấp bản ghi, cần khoá ghi.
	Broken map[uuid.UUID][]int16
}

func (r *Report) add(m Mismatch) {
	r.Mismatches++
	if r.First == nil || less(m, *r.First) {
		mm := m
		r.First = &mm
	}
	if m.ChainPartition != nil && m.SeqNo != nil {
		if r.Broken == nil {
			r.Broken = map[uuid.UUID][]int16{}
		}
		for _, p := range r.Broken[m.TenantID] {
			if p == *m.ChainPartition {
				return
			}
		}
		r.Broken[m.TenantID] = append(r.Broken[m.TenantID], *m.ChainPartition)
	}
}

// less: sai lệch cấp bản ghi đứng trước sai lệch niêm phong; cùng cấp thì theo phân vùng rồi seq_no.
func less(a, b Mismatch) bool {
	ka, kb := a.SeqNo != nil, b.SeqNo != nil
	if ka != kb {
		return ka
	}
	if !ka {
		return false
	}
	if *a.ChainPartition != *b.ChainPartition {
		return *a.ChainPartition < *b.ChainPartition
	}
	return *a.SeqNo < *b.SeqNo
}

// ArchiveReader đọc lại bản ghi của một tệp Parquet lưu trữ (DL-12, DL-13 hiện thực).
type ArchiveReader interface {
	ReadSegment(ctx context.Context, objectKey string, fn func(e ArchivedEntry) error) error
}

// ArchivedEntry là một dòng trong tệp lưu trữ.
type ArchivedEntry struct {
	ChainPartition int16
	SeqNo          int64
	EntryHash      []byte
}

// Engine chạy kiểm tra.
type Engine struct {
	Pool    *pgxpool.Pool
	Workers int
	// Certs là chứng thư khoá niêm phong theo SHA-256; thiếu chứng thư thì bỏ qua kiểm chữ ký.
	Certs   map[[32]byte]*x509.Certificate
	Archive ArchiveReader
	// SampleRate là tỉ lệ tệp lưu trữ chọn ngẫu nhiên cho LUU_TRU (mặc định 5%).
	SampleRate float64
}

func (e *Engine) workers() int {
	if e.Workers > 0 {
		return e.Workers
	}
	return 8
}

func inTenant(ctx context.Context, pool *pgxpool.Pool, t uuid.UUID, fn func(pgx.Tx) error) error {
	return pgx.BeginTxFunc(ctx, pool, pgx.TxOptions{AccessMode: pgx.ReadOnly}, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, t.String()); err != nil {
			return err
		}
		return fn(tx)
	})
}

// Run thực hiện một lần kiểm tra.
func (e *Engine) Run(ctx context.Context, req Request) (*Report, error) {
	rep := &Report{}
	switch req.Kind {
	case KindHead:
		return rep, e.runChains(ctx, req, rep, true)
	case KindFull:
		if err := e.runChains(ctx, req, rep, false); err != nil {
			return nil, err
		}
		return rep, e.runSeals(ctx, req, rep)
	case KindSeal:
		return rep, e.runSeals(ctx, req, rep)
	case KindArchive:
		return rep, e.runArchive(ctx, req, rep)
	}
	return nil, fmt.Errorf("verify: loại kiểm tra %q không hợp lệ", req.Kind)
}

type chainJob struct {
	tenant uuid.UUID
	chain  int16
}

type chainResult struct {
	job     chainJob
	entries int64
	bad     []Mismatch
	links   []Link
	err     error
}

// runChains kiểm từng chuỗi song song. head=true: chỉ phần chưa niêm phong (từ đầu chuỗi đã niêm phong) và
// so chain_head với bản ghi cuối.
func (e *Engine) runChains(ctx context.Context, req Request, rep *Report, head bool) error {
	var jobs []chainJob
	err := inTenant(ctx, e.Pool, req.TenantID, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, `SELECT chain_partition FROM ledger.chain_head WHERE tenant_id = $1 ORDER BY 1`, req.TenantID)
		if err != nil {
			return err
		}
		ps, err := pgx.CollectRows(rows, pgx.RowTo[int16])
		for _, p := range ps {
			if req.Chain == nil || *req.Chain == p {
				jobs = append(jobs, chainJob{req.TenantID, p})
			}
		}
		return err
	})
	if err != nil {
		return err
	}
	in := make(chan chainJob)
	out := make(chan chainResult, len(jobs))
	var wg sync.WaitGroup
	for i := 0; i < min(e.workers(), max(1, len(jobs))); i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range in {
				r := chainResult{job: j}
				r.entries, r.bad, r.links, r.err = e.verifyChain(ctx, j.tenant, j.chain, req.From, req.To, head)
				out <- r
			}
		}()
	}
	for _, j := range jobs {
		in <- j
	}
	close(in)
	wg.Wait()
	close(out)
	results := make([]chainResult, 0, len(jobs))
	for r := range out {
		if r.err != nil {
			return fmt.Errorf("verify: chuỗi %d: %w", r.job.chain, r.err)
		}
		results = append(results, r)
	}
	sort.Slice(results, func(i, j int) bool { return results[i].job.chain < results[j].job.chain })
	for _, r := range results {
		rep.EntriesChecked += r.entries
		rep.PartitionsTotal++
		if len(r.bad) == 0 {
			rep.PartitionsMatched++
		}
		for _, m := range r.bad {
			before := rep.First
			rep.add(m)
			if rep.First != before && rep.First.SeqNo != nil && *rep.First.ChainPartition == r.job.chain {
				rep.Chain = r.links
			}
		}
	}
	return nil
}

type row struct {
	id                                   uuid.UUID
	seq                                  int64
	eventType, aggType                   string
	aggID                                uuid.UUID
	actorID                              *uuid.UUID
	actorKind                            string
	occurred, recorded                   time.Time
	metadata, payload, meta, prev, entry []byte
}

func hx(b []byte) string { return hex.EncodeToString(b) }

// verifyChain quét một chuỗi theo seq_no. Sai lệch ở một bản ghi không lan sang bản ghi sau: bản ghi sau
// được so với entry_hash đã lưu của bản ghi trước, nên chỉ bản ghi bị sửa bị chỉ ra.
func (e *Engine) verifyChain(ctx context.Context, t uuid.UUID, p int16, from, to time.Time, head bool) (int64, []Mismatch, []Link, error) {
	var (
		count int64
		bad   []Mismatch
		links []Link
	)
	err := inTenant(ctx, e.Pool, t, func(tx pgx.Tx) error {
		var lastSeq, sealedSeq int64
		var lastHash []byte
		if err := tx.QueryRow(ctx, `SELECT last_seq_no, last_hash, last_sealed_seq_no FROM ledger.chain_head
			WHERE tenant_id = $1 AND chain_partition = $2`, t, p).Scan(&lastSeq, &lastHash, &sealedSeq); err != nil {
			return err
		}
		q := `SELECT id, seq_no, event_type, aggregate_type, aggregate_id, actor_id, actor_kind, occurred_at, recorded_at,
				metadata, payload_hash, metadata_hash, prev_hash, entry_hash
			FROM ledger.ledger_entry WHERE tenant_id = $1 AND chain_partition = $2`
		args := []any{t, p}
		if head {
			q += ` AND seq_no >= $3`
			args = append(args, max(sealedSeq, 1))
		} else {
			if !from.IsZero() {
				args = append(args, from)
				q += fmt.Sprintf(` AND recorded_at >= $%d`, len(args))
			}
			if !to.IsZero() {
				args = append(args, to)
				q += fmt.Sprintf(` AND recorded_at <= $%d`, len(args))
			}
		}
		rows, err := tx.Query(ctx, q+` ORDER BY seq_no`, args...)
		if err != nil {
			return err
		}
		defer rows.Close()
		var (
			expected []byte
			next     int64 = -1
			window   []Link
			tail     int
			maxSeq   int64
			maxHash  []byte
		)
		for rows.Next() {
			var r row
			if err := rows.Scan(&r.id, &r.seq, &r.eventType, &r.aggType, &r.aggID, &r.actorID, &r.actorKind, &r.occurred,
				&r.recorded, &r.metadata, &r.payload, &r.meta, &r.prev, &r.entry); err != nil {
				return err
			}
			first := next < 0
			if first {
				next = r.seq
				switch {
				case r.seq == 1:
					expected = canon.ZeroHash[:]
				case r.eventType == ingest.EventTypeTenantMovedIn, head && r.seq == sealedSeq:
					// TENANT_MOVED_IN nối tiếp cell nguồn; ở DAU_CHUOI bản ghi đã niêm phong làm mốc, phần
					// trước đó do NIEM_PHONG, TOAN_BO kiểm.
					expected = r.prev
				default:
					// Dải bắt đầu giữa chuỗi: so với entry_hash của bản ghi liền trước.
					err := tx.QueryRow(ctx, `SELECT entry_hash FROM ledger.ledger_entry
						WHERE tenant_id = $1 AND chain_partition = $2 AND seq_no = $3`, t, p, r.seq-1).Scan(&expected)
					if errors.Is(err, pgx.ErrNoRows) {
						expected = r.prev // bản ghi trước đã chuyển lưu trữ: LUU_TRU kiểm
					} else if err != nil {
						return err
					}
				}
			}
			m, link := check(t, p, r, next, expected)
			count++
			switch {
			case m != nil && len(bad) == 0:
				bad = append(bad, *m)
				links = append(append([]Link{}, window...), link)
				tail = 2
			case m != nil:
				bad = append(bad, *m)
				fallthrough
			case tail > 0:
				if tail > 0 {
					links = append(links, link)
					tail--
				}
			}
			window = append(window, link)
			if len(window) > 2 {
				window = window[1:]
			}
			expected = r.entry
			next = r.seq + 1
			maxSeq, maxHash = r.seq, r.entry
		}
		if err := rows.Err(); err != nil {
			return err
		}
		if head && (maxSeq != lastSeq || !bytes.Equal(maxHash, lastHash)) && maxSeq > 0 {
			s := maxSeq
			pp := p
			bad = append(bad, Mismatch{TenantID: t, ChainPartition: &pp, SeqNo: &s, StoredHash: hx(lastHash),
				RecomputedHash: hx(maxHash), Reason: ReasonHead})
		}
		return nil
	})
	return count, bad, links, err
}

// check kiểm một bản ghi: seq_no, prev_hash, metadata_hash tính lại từ cột (kể cả cột metadata), entry_hash tính lại.
func check(t uuid.UUID, p int16, r row, next int64, expectedPrev []byte) (*Mismatch, Link) {
	var actor *string
	if r.actorID != nil {
		s := r.actorID.String()
		actor = &s
	}
	var meta [32]byte
	digest, err := canon.MetadataDigestJSON(r.metadata)
	if err == nil {
		meta, _, err = canon.MetadataHash(canon.MetadataHeader{
			TenantID: t.String(), ChainPartition: p, SeqNo: r.seq, EventType: r.eventType, AggregateType: r.aggType,
			AggregateID: r.aggID.String(), ActorID: actor, ActorKind: r.actorKind,
			OccurredAt: canon.FormatTimestamp(r.occurred), RecordedAt: canon.FormatTimestamp(r.recorded),
			MetadataSHA256: digest,
		})
	}
	recomputed := canon.EntryHash([32]byte(r.prev), [32]byte(r.payload), meta)
	link := Link{SeqNo: r.seq, EntryID: r.id, PrevHash: hx(r.prev), StoredHash: hx(r.entry), RecomputedHash: hx(recomputed[:])}
	seq, pp, id := r.seq, p, r.id
	mk := func(reason string) *Mismatch {
		return &Mismatch{TenantID: t, ChainPartition: &pp, SeqNo: &seq, EntryID: &id, StoredHash: hx(r.entry),
			RecomputedHash: hx(recomputed[:]), Reason: reason}
	}
	switch {
	case r.seq != next:
		return mk(ReasonSeqGap), link
	case !bytes.Equal(r.prev, expectedPrev):
		m := mk(ReasonPrevHash)
		m.StoredHash, m.RecomputedHash = hx(r.prev), hx(expectedPrev)
		return m, link
	case err != nil || !bytes.Equal(meta[:], r.meta):
		return mk(ReasonMetadata), link
	case !bytes.Equal(recomputed[:], r.entry):
		return mk(ReasonEntryHash), link
	}
	link.Matches = true
	return nil, link
}

type sealRow struct {
	id, cellID         uuid.UUID
	tenant             uuid.UUID
	ranges, proof      []byte
	tenantRoot         []byte
	cellRoot, prevCell []byte
	windowEnd          time.Time
	sig, certSHA, tst  []byte
}

// runSeals dựng lại gốc tenant từ entry_hash hiện có tại mỗi niêm phong trong phạm vi, kiểm đường đi lên gốc
// cell, chữ ký gốc cell (khi có chứng thư) và token TSA.
func (e *Engine) runSeals(ctx context.Context, req Request, rep *Report) error {
	tenants := []uuid.UUID{req.TenantID}
	if req.TenantID == uuid.Nil {
		var err error
		if tenants, err = (&seal.Sealer{Pool: e.Pool}).Tenants(ctx); err != nil {
			return err
		}
	}
	checkedCells := map[uuid.UUID]bool{}
	for _, t := range tenants {
		var seals []sealRow
		err := inTenant(ctx, e.Pool, t, func(tx pgx.Tx) error {
			rows, err := tx.Query(ctx, `SELECT m.id, m.cell_seal_id, m.tenant_id, m.seq_ranges, m.inclusion_proof, m.tenant_root_hash,
					c.cell_root_hash, c.prev_cell_root_hash, c.window_end, c.signature, c.signer_cert_sha256, c.rfc3161_tst
				FROM ledger.merkle_seal m JOIN ledger.cell_seal c ON c.id = m.cell_seal_id
				WHERE m.tenant_id = $1 AND ($2::timestamptz IS NULL OR m.window_end > $2) AND ($3::timestamptz IS NULL OR m.window_start < $3)
				ORDER BY m.window_end`, t, nullTime(req.From), nullTime(req.To))
			if err != nil {
				return err
			}
			defer rows.Close()
			for rows.Next() {
				var s sealRow
				if err := rows.Scan(&s.id, &s.cellID, &s.tenant, &s.ranges, &s.proof, &s.tenantRoot, &s.cellRoot, &s.prevCell,
					&s.windowEnd, &s.sig, &s.certSHA, &s.tst); err != nil {
					return err
				}
				seals = append(seals, s)
			}
			if err := rows.Err(); err != nil {
				return err
			}
			for _, s := range seals {
				if err := e.checkSeal(ctx, tx, s, req.Chain, rep, !checkedCells[s.cellID]); err != nil {
					return err
				}
				checkedCells[s.cellID] = true
			}
			return nil
		})
		if err != nil {
			return err
		}
	}
	return nil
}

func nullTime(t time.Time) any {
	if t.IsZero() {
		return nil
	}
	return t
}

type proofDoc struct {
	CellLeafIndex int `json:"cellLeafIndex"`
	Path          []struct {
		S string `json:"s"`
		L bool   `json:"l"`
	} `json:"path"`
}

func (e *Engine) checkSeal(ctx context.Context, tx pgx.Tx, s sealRow, only *int16, rep *Report, cellLevel bool) error {
	rep.SealsChecked++
	sid := s.id
	var ranges []seal.ChainRange
	if err := json.Unmarshal(s.ranges, &ranges); err != nil {
		rep.add(Mismatch{TenantID: s.tenant, SealID: &sid, Reason: ReasonTenantRoot})
		return nil
	}
	parts := make([]int16, len(ranges))
	seqs := make([]int64, len(ranges))
	for i, r := range ranges {
		parts[i], seqs[i] = r.Partition, r.To
	}
	rows, err := tx.Query(ctx, `SELECT e.chain_partition, e.seq_no, e.id, e.entry_hash FROM ledger.ledger_entry e
		JOIN unnest($2::smallint[], $3::bigint[]) AS k(p, s) ON e.chain_partition = k.p AND e.seq_no = k.s
		WHERE e.tenant_id = $1`, s.tenant, parts, seqs)
	if err != nil {
		return err
	}
	type tip struct {
		id   uuid.UUID
		hash []byte
	}
	tips := map[int16]tip{}
	for rows.Next() {
		var (
			p   int16
			seq int64
			t   tip
		)
		if err := rows.Scan(&p, &seq, &t.id, &t.hash); err != nil {
			rows.Close()
			return err
		}
		tips[p] = t
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	live := make([]seal.ChainRange, len(ranges))
	tipOK := true
	for i, r := range ranges {
		live[i] = r
		t, ok := tips[r.Partition]
		if !ok {
			continue // bản ghi đã chuyển lưu trữ
		}
		live[i].Tip = hx(t.hash)
		if live[i].Tip != r.Tip && (only == nil || *only == r.Partition) {
			tipOK = false
			p, seq, id := r.Partition, r.To, t.id
			rep.add(Mismatch{TenantID: s.tenant, ChainPartition: &p, SeqNo: &seq, EntryID: &id, StoredHash: r.Tip,
				RecomputedHash: live[i].Tip, SealID: &sid, Reason: ReasonSealTip})
		}
	}
	root, err := seal.TenantRoot(live)
	if err != nil || (!bytes.Equal(root[:], s.tenantRoot) && tipOK) {
		rep.add(Mismatch{TenantID: s.tenant, SealID: &sid, StoredHash: hx(s.tenantRoot), RecomputedHash: hx(root[:]), Reason: ReasonTenantRoot})
	}
	var proof proofDoc
	_ = json.Unmarshal(s.proof, &proof)
	steps := make([]seal.Step, len(proof.Path))
	for i, st := range proof.Path {
		b, _ := hex.DecodeString(st.S)
		copy(steps[i].Sibling[:], b)
		steps[i].Left = st.L
	}
	var cellRoot [32]byte
	copy(cellRoot[:], s.cellRoot)
	if !seal.Verify(seal.Leaf(s.tenantRoot), steps, cellRoot) {
		rep.add(Mismatch{TenantID: s.tenant, SealID: &sid, StoredHash: hx(s.cellRoot), Reason: ReasonCellPath})
	}
	if !cellLevel {
		return nil
	}
	var prev [32]byte
	copy(prev[:], s.prevCell)
	if cert, ok := e.Certs[[32]byte(s.certSHA)]; ok {
		if !seal.VerifySignature(cert, seal.SignedMessage(cellRoot, prev, s.windowEnd), s.sig) {
			rep.add(Mismatch{TenantID: s.tenant, SealID: &sid, Reason: ReasonSignature})
		}
	}
	if _, err := seal.ParseToken(s.tst, cellRoot); err != nil {
		rep.add(Mismatch{TenantID: s.tenant, SealID: &sid, Reason: ReasonTSA})
	}
	return nil
}
