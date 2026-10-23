package verify

import (
	"context"
	"encoding/json"
	"math/rand/v2"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"mission/services-go/ledger/internal/seal"
)

// runArchive chọn ngẫu nhiên SampleRate (mặc định 5%, ít nhất một tệp) các phân đoạn lưu trữ của tenant, đọc
// lại tệp qua ArchiveReader và so entry_hash cuối mỗi chuỗi với seq_ranges đã ghi khi lưu trữ.
func (e *Engine) runArchive(ctx context.Context, req Request, rep *Report) error {
	if e.Archive == nil {
		return ErrArchiveUnavailable
	}
	type segment struct {
		id     uuid.UUID
		key    string
		ranges []byte
	}
	var segs []segment
	err := inTenant(ctx, e.Pool, req.TenantID, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, `SELECT id, object_key, seq_ranges FROM ledger.archive_segment WHERE tenant_id = $1`, req.TenantID)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var s segment
			if err := rows.Scan(&s.id, &s.key, &s.ranges); err != nil {
				return err
			}
			segs = append(segs, s)
		}
		return rows.Err()
	})
	if err != nil || len(segs) == 0 {
		return err
	}
	rate := e.SampleRate
	if rate <= 0 {
		rate = 0.05
	}
	rand.Shuffle(len(segs), func(i, j int) { segs[i], segs[j] = segs[j], segs[i] })
	n := max(1, int(float64(len(segs))*rate+0.5))
	for _, s := range segs[:min(n, len(segs))] {
		var ranges []seal.ChainRange
		if err := json.Unmarshal(s.ranges, &ranges); err != nil {
			return err
		}
		want := map[int16]seal.ChainRange{}
		for _, r := range ranges {
			want[r.Partition] = r
		}
		rep.PartitionsTotal++
		before := rep.Mismatches
		err := e.Archive.ReadSegment(ctx, s.key, func(a ArchivedEntry) error {
			rep.EntriesChecked++
			if r, ok := want[a.ChainPartition]; ok && a.SeqNo == r.To && hx(a.EntryHash) != r.Tip {
				p, seq := a.ChainPartition, a.SeqNo
				rep.add(Mismatch{TenantID: req.TenantID, ChainPartition: &p, SeqNo: &seq, StoredHash: r.Tip,
					RecomputedHash: hx(a.EntryHash), Reason: ReasonArchiveHash})
			}
			return nil
		})
		if err != nil {
			return err
		}
		if rep.Mismatches == before {
			rep.PartitionsMatched++
		}
	}
	return nil
}
