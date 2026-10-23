package seal

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Đích neo theo ràng buộc ck_anchor_receipt__target (TL-03 ledger.anchor_receipt).
const (
	AnchorOtherCell  = "CELL_KHAC"
	AnchorArchive    = "KHO_VPTW"
	AnchorSecondTSA  = "TSA_DOC_LAP"
	AnchorObjectLock = "OBJECT_LOCK"
)

var anchorTargets = map[string]bool{AnchorOtherCell: true, AnchorArchive: true, AnchorSecondTSA: true, AnchorObjectLock: true}

// ErrNoSealForDay: trong ngày chưa có niêm phong cell nào.
var ErrNoSealForDay = errors.New("seal: ngày chưa có niêm phong cell để neo")

// ObjectLockStore ghi bản neo vào bucket có Object Lock (đích OBJECT_LOCK do ledger ghi trực tiếp; bucket
// thuộc INF-12) và trả biên nhận.
type ObjectLockStore interface {
	PutLocked(ctx context.Context, key string, body []byte, retainUntil time.Time) ([]byte, error)
}

// DirStore ghi bản neo ra thư mục cục bộ chỉ đọc; dùng ở dev và kiểm thử thay bucket Object Lock.
type DirStore struct{ Dir string }

// PutLocked ghi tệp quyền 0444, không ghi đè; biên nhận là JSON {key, sha256}.
func (d DirStore) PutLocked(_ context.Context, key string, body []byte, retainUntil time.Time) ([]byte, error) {
	p := filepath.Join(d.Dir, filepath.FromSlash(key))
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o444)
	if errors.Is(err, os.ErrExist) {
		old, rerr := os.ReadFile(p)
		if rerr != nil {
			return nil, rerr
		}
		body = old
	} else if err != nil {
		return nil, err
	} else {
		if _, err := f.Write(body); err != nil {
			f.Close()
			return nil, err
		}
		if err := f.Close(); err != nil {
			return nil, err
		}
	}
	sum := sha256.Sum256(body)
	return json.Marshal(map[string]any{"key": key, "sha256": hex.EncodeToString(sum[:]), "retainUntil": retainUntil.UTC()})
}

// Anchorer chọn gốc cell cuối ngày làm bản neo và ghi biên nhận vào ledger.anchor_receipt. Việc gửi tới
// các đích ngoài (CELL_KHAC, KHO_VPTW, TSA_DOC_LAP) và nhận biên nhận thuộc SVC-13 (DV-13-09).
type Anchorer struct {
	Pool   *pgxpool.Pool
	Cell   string
	Loc    *time.Location
	Store  ObjectLockStore
	Retain time.Duration
}

func (a *Anchorer) loc() *time.Location {
	if a.Loc != nil {
		return a.Loc
	}
	return time.FixedZone("ICT", 7*3600)
}

// DailyAnchor là niêm phong cell có window_end muộn nhất không quá 0 giờ ngày hôm sau (giờ địa phương).
// SVC-13 dùng cùng quy tắc này để nhận ra EVT-19 của bản neo trong ngày (suy luận).
func (a *Anchorer) DailyAnchor(ctx context.Context, day time.Time) (*AnchorDocument, error) {
	d := time.Date(day.In(a.loc()).Year(), day.In(a.loc()).Month(), day.In(a.loc()).Day(), 0, 0, 0, 0, a.loc())
	end := d.AddDate(0, 0, 1)
	var (
		doc                        AnchorDocument
		root, prev, sig, cert, tst []byte
	)
	err := a.Pool.QueryRow(ctx, `SELECT id, window_start, window_end, cell_root_hash, prev_cell_root_hash,
			signer_key_id, signature, signer_cert_sha256, rfc3161_tst
		FROM ledger.cell_seal WHERE cell_code = $1 AND window_end > $2 AND window_end <= $3
		ORDER BY window_end DESC LIMIT 1`, a.Cell, d, end).
		Scan(&doc.CellSealID, &doc.WindowStart, &doc.WindowEnd, &root, &prev, &doc.SignerKeyID, &sig, &cert, &tst)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNoSealForDay
	}
	if err != nil {
		return nil, err
	}
	doc.Day = d.Format(time.DateOnly)
	doc.CellCode = a.Cell
	doc.WindowStart, doc.WindowEnd = doc.WindowStart.UTC(), doc.WindowEnd.UTC()
	doc.CellRoot, doc.PrevCellRoot = hex.EncodeToString(root), hex.EncodeToString(prev)
	doc.Signature = base64.StdEncoding.EncodeToString(sig)
	doc.SignerCertSHA256 = hex.EncodeToString(cert)
	doc.TSAToken = base64.StdEncoding.EncodeToString(tst)
	return &doc, nil
}

// AnchorDay ghi bản neo của ngày vào bucket Object Lock và lưu biên nhận OBJECT_LOCK; chạy lại không
// tạo trùng (UQ cell_seal_id, anchor_target).
func (a *Anchorer) AnchorDay(ctx context.Context, day time.Time) (*AnchorDocument, error) {
	doc, err := a.DailyAnchor(ctx, day)
	if err != nil || a.Store == nil {
		return doc, err
	}
	body, err := json.Marshal(doc)
	if err != nil {
		return nil, err
	}
	retain := a.Retain
	if retain <= 0 {
		retain = 10 * 365 * 24 * time.Hour
	}
	key := fmt.Sprintf("ledger-anchor/%s/%s.json", a.Cell, doc.Day)
	receipt, err := a.Store.PutLocked(ctx, key, body, doc.WindowEnd.Add(retain))
	if err != nil {
		return nil, fmt.Errorf("seal: ghi bản neo Object Lock: %w", err)
	}
	return doc, a.RecordReceipt(ctx, doc.CellSealID, AnchorObjectLock, receipt, time.Now())
}

// RecordReceipt lưu biên nhận của một đích neo; biên nhận đã có thì giữ nguyên.
func (a *Anchorer) RecordReceipt(ctx context.Context, cellSealID uuid.UUID, target string, receipt []byte, at time.Time) error {
	if !anchorTargets[target] {
		return fmt.Errorf("seal: đích neo %q không hợp lệ", target)
	}
	if len(receipt) == 0 {
		return errors.New("seal: biên nhận neo rỗng")
	}
	_, err := a.Pool.Exec(ctx, `INSERT INTO ledger.anchor_receipt (id, cell_seal_id, anchor_target, receipt, anchored_at)
		VALUES ($1,$2,$3,$4,$5) ON CONFLICT (cell_seal_id, anchor_target) DO NOTHING`,
		uuid.Must(uuid.NewV7()), cellSealID, target, receipt, at.UTC())
	return err
}

// Receipts là các đích đã có biên nhận của một niêm phong cell.
func (a *Anchorer) Receipts(ctx context.Context, cellSealID uuid.UUID) ([]string, error) {
	rows, err := a.Pool.Query(ctx, `SELECT anchor_target FROM ledger.anchor_receipt WHERE cell_seal_id = $1 ORDER BY 1`, cellSealID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[string])
}

// Run neo hằng ngày lúc 00:15 giờ địa phương cho ngày vừa qua (sau cửa sổ cuối ngày và grace niêm phong).
func (a *Anchorer) Run(ctx context.Context, log func(msg string, args ...any)) {
	for {
		now := time.Now().In(a.loc())
		next := time.Date(now.Year(), now.Month(), now.Day(), 0, 15, 0, 0, a.loc())
		if !next.After(now) {
			next = next.AddDate(0, 0, 1)
		}
		timer := time.NewTimer(time.Until(next))
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
		if _, err := a.AnchorDay(ctx, next.AddDate(0, 0, -1)); err != nil {
			log("neo sổ cái hằng ngày thất bại", "day", next.AddDate(0, 0, -1).Format(time.DateOnly), "err", err)
		}
	}
}
