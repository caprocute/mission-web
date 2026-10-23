package verify

import (
	"context"
	"crypto/sha256"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
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

// Trạng thái tác vụ (JobStatus của hợp đồng).
const (
	StatusPending = "CHO"
	StatusRunning = "DANG_CHAY"
	StatusDone    = "HOAN_TAT"
	StatusFailed  = "THAT_BAI"

	// TopicAnomalyDetected là topic EVT-20.
	TopicAnomalyDetected = "analytics.anomaly.detected.v1"
	// JobType là loại tác vụ dài của lần kiểm tra (suy luận).
	JobType = "LEDGER_VERIFY"

	signalCode   = "SUA_SAU_KHOA"
	modelVersion = "ledger-verify-1"
)

//go:embed DauHieuBatThuong.avsc
var anomalySchema string

var anomalyCodec = func() *goavro.Codec {
	c, err := goavro.NewCodec(anomalySchema)
	if err != nil {
		panic(fmt.Sprintf("verify: lược đồ EVT-20: %v", err))
	}
	return c
}()

// Lỗi nghiệp vụ.
var (
	ErrNotFound            = errors.New("verify: không có lần kiểm tra")
	ErrIdempotencyMismatch = errors.New("verify: Idempotency-Key đã dùng cho yêu cầu khác")
)

// Run là một dòng ledger.verify_run.
type Run struct {
	ID                uuid.UUID
	TenantID          *uuid.UUID
	Kind              string
	Chain             *int16
	From, To          time.Time
	EntriesChecked    int64
	SealsChecked      int64
	PartitionsTotal   int
	PartitionsMatched int
	Mismatches        int
	First             *Mismatch
	Links             []Link
	AlertID           *uuid.UUID
	Status            string
	Detail            string
	RequestedBy       *uuid.UUID
	StartedAt         time.Time
	FinishedAt        *time.Time
}

// Service lưu lần kiểm tra, chạy nền và xử lý phá vỡ.
type Service struct {
	Pool   *pgxpool.Pool
	Engine *Engine
	Cell   string
	OTel   *otel.Provider
	Log    *slog.Logger
	// MaxConcurrent giới hạn số lần kiểm tra chạy đồng thời (mặc định 2).
	MaxConcurrent int

	lastFinished atomic.Int64

	once   sync.Once
	sem    chan struct{}
	outbox *outbox.Writer
	wg     sync.WaitGroup
}

func (s *Service) init() {
	s.once.Do(func() {
		n := s.MaxConcurrent
		if n <= 0 {
			n = 2
		}
		s.sem = make(chan struct{}, n)
		w, err := outbox.NewWriter("ledger", "urn:vt-okr:"+s.Cell+":ledger")
		if err != nil {
			panic(err)
		}
		s.outbox = w
	})
}

func (s *Service) log() *slog.Logger {
	if s.Log != nil {
		return s.Log
	}
	return slog.Default()
}

func (s *Service) count(name string, n int64) {
	if s.OTel != nil && n > 0 {
		s.OTel.AddCounter(name, n)
	}
}

func inTenantRW(ctx context.Context, pool *pgxpool.Pool, t *uuid.UUID, fn func(pgx.Tx) error) error {
	return pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
		v := ""
		if t != nil {
			v = t.String()
		}
		if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, v); err != nil {
			return err
		}
		return fn(tx)
	})
}

// RequestHash là SHA-256 của yêu cầu chuẩn hoá (để phát hiện dùng lại Idempotency-Key với thân khác).
func RequestHash(req Request) []byte {
	var b strings.Builder
	b.WriteString(req.Kind)
	if req.Chain != nil {
		fmt.Fprintf(&b, "|p=%d", *req.Chain)
	}
	if !req.From.IsZero() {
		b.WriteString("|from=" + req.From.UTC().Format(time.RFC3339Nano))
	}
	if !req.To.IsZero() {
		b.WriteString("|to=" + req.To.UTC().Format(time.RFC3339Nano))
	}
	h := sha256.Sum256([]byte(b.String()))
	return h[:]
}

// Create ghi lần kiểm tra trạng thái CHO và chạy nền. Cùng (tenant, người yêu cầu, Idempotency-Key) trả lại
// lần đã có (existing = true); khoá đã dùng với thân khác trả ErrIdempotencyMismatch.
func (s *Service) Create(ctx context.Context, req Request, requestedBy *uuid.UUID, idemKey string) (*Run, bool, error) {
	s.init()
	hash := RequestHash(req) // trên thân yêu cầu gốc, trước khi đặt phạm vi mặc định theo giờ hiện tại
	req = s.normalize(ctx, req)
	t := tenantPtr(req.TenantID)
	run := &Run{ID: uuid.Must(uuid.NewV7()), TenantID: t, Kind: req.Kind, Chain: req.Chain, From: req.From, To: req.To,
		Status: StatusPending, RequestedBy: requestedBy, StartedAt: time.Now().UTC().Truncate(time.Microsecond)}
	var key any
	if idemKey != "" {
		key = idemKey
	}
	err := inTenantRW(ctx, s.Pool, t, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `INSERT INTO ledger.verify_run (id, tenant_id, verify_kind, range_from, range_to, entries_checked,
				mismatches, started_at, chain_partition, status, requested_by, idempotency_key, request_hash)
			VALUES ($1,$2,$3,$4,$5,0,0,$6,$7,$8,$9,$10,$11)`,
			run.ID, t, run.Kind, run.From, run.To, run.StartedAt, run.Chain, run.Status, requestedBy, key, hash)
		return err
	})
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23505" && idemKey != "" {
		var (
			id     uuid.UUID
			stored []byte
		)
		err = inTenantRW(ctx, s.Pool, t, func(tx pgx.Tx) error {
			return tx.QueryRow(ctx, `SELECT id, request_hash FROM ledger.verify_run
				WHERE tenant_id IS NOT DISTINCT FROM $1 AND requested_by IS NOT DISTINCT FROM $2 AND idempotency_key = $3`,
				t, requestedBy, idemKey).Scan(&id, &stored)
		})
		if err != nil {
			return nil, false, err
		}
		if hex.EncodeToString(stored) != hex.EncodeToString(hash) {
			return nil, false, ErrIdempotencyMismatch
		}
		r, err := s.Get(ctx, req.TenantID, id)
		return r, true, err
	}
	if err != nil {
		return nil, false, err
	}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		s.Execute(context.WithoutCancel(ctx), run.ID, req)
	}()
	return run, false, nil
}

// LastFinished là thời điểm (Unix giây) lần kiểm tra gần nhất kết thúc thành công; 0 nếu chưa có.
func (s *Service) LastFinished() int64 { return s.lastFinished.Load() }

// Wait chờ các lần kiểm tra đang chạy nền (dùng khi dừng dịch vụ và trong kiểm thử).
func (s *Service) Wait() { s.wg.Wait() }

// normalize đặt phạm vi mặc định: DAU_CHUOI từ cuối niêm phong gần nhất, NIEM_PHONG 2 giờ gần nhất, còn lại
// từ bản ghi sớm nhất tới hiện tại.
func (s *Service) normalize(ctx context.Context, req Request) Request {
	now := time.Now().UTC().Truncate(time.Microsecond)
	if req.To.IsZero() {
		req.To = now
	}
	if req.From.IsZero() {
		switch req.Kind {
		case KindSeal:
			req.From = req.To.Add(-2 * time.Hour)
		case KindHead:
			req.From = req.To.Add(-15 * time.Minute)
		default:
			var first *time.Time
			if req.TenantID != uuid.Nil {
				_ = inTenant(ctx, s.Pool, req.TenantID, func(tx pgx.Tx) error {
					return tx.QueryRow(ctx, `SELECT min(recorded_at) FROM ledger.ledger_entry WHERE tenant_id = $1`, req.TenantID).Scan(&first)
				})
			}
			req.From = req.To
			if first != nil {
				req.From = first.UTC()
			}
		}
	}
	return req
}

func tenantPtr(t uuid.UUID) *uuid.UUID {
	if t == uuid.Nil {
		return nil
	}
	return &t
}

// RunNow chạy đồng bộ một lần kiểm tra theo lịch (không có người yêu cầu).
func (s *Service) RunNow(ctx context.Context, req Request) (*Run, error) {
	s.init()
	req = s.normalize(ctx, req)
	run, _, err := s.createScheduled(ctx, req)
	if err != nil {
		return nil, err
	}
	s.Execute(ctx, run.ID, req)
	return s.Get(ctx, req.TenantID, run.ID)
}

func (s *Service) createScheduled(ctx context.Context, req Request) (*Run, bool, error) {
	t := tenantPtr(req.TenantID)
	run := &Run{ID: uuid.Must(uuid.NewV7()), TenantID: t, Kind: req.Kind, Chain: req.Chain, From: req.From, To: req.To,
		Status: StatusPending, StartedAt: time.Now().UTC().Truncate(time.Microsecond)}
	err := inTenantRW(ctx, s.Pool, t, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `INSERT INTO ledger.verify_run (id, tenant_id, verify_kind, range_from, range_to, entries_checked,
				mismatches, started_at, chain_partition, status) VALUES ($1,$2,$3,$4,$5,0,0,$6,$7,$8)`,
			run.ID, t, run.Kind, run.From, run.To, run.StartedAt, run.Chain, run.Status)
		return err
	})
	return run, false, err
}

// Execute chạy kiểm tra và ghi kết quả; phá vỡ thì khoá ghi chuỗi và phát EVT-20 trong cùng giao dịch.
func (s *Service) Execute(ctx context.Context, id uuid.UUID, req Request) {
	s.init()
	s.sem <- struct{}{}
	defer func() { <-s.sem }()
	t := tenantPtr(req.TenantID)
	started := time.Now()
	_ = inTenantRW(ctx, s.Pool, t, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `UPDATE ledger.verify_run SET status = $2 WHERE id = $1`, id, StatusRunning)
		return err
	})
	rep, err := s.Engine.Run(ctx, req)
	s.count("ledger.verify.runs_total", 1)
	if err != nil {
		detail := "Không hoàn tất được kiểm tra toàn vẹn. Vui lòng thử lại sau."
		if errors.Is(err, ErrArchiveUnavailable) {
			detail = "Chưa đọc được bản lưu trữ để kiểm tra."
		}
		s.log().Error("kiểm tra toàn vẹn sổ cái thất bại", "verify_run_id", id, "kind", req.Kind, "err", err)
		_ = inTenantRW(ctx, s.Pool, t, func(tx pgx.Tx) error {
			_, err := tx.Exec(ctx, `UPDATE ledger.verify_run SET status = $2, status_detail = $3, finished_at = clock_timestamp()
				WHERE id = $1`, id, StatusFailed, detail)
			return err
		})
		return
	}
	if err := s.finish(ctx, id, req, rep, time.Since(started)); err != nil {
		s.log().Error("không ghi được kết quả kiểm tra toàn vẹn", "verify_run_id", id, "err", err)
		return
	}
	s.lastFinished.Store(time.Now().Unix())
}

func (s *Service) finish(ctx context.Context, id uuid.UUID, req Request, rep *Report, took time.Duration) error {
	t := tenantPtr(req.TenantID)
	var first, chain any
	if rep.First != nil {
		b, _ := json.Marshal(rep.First)
		first = string(b)
	}
	if len(rep.Chain) > 0 {
		b, _ := json.Marshal(rep.Chain)
		chain = string(b)
	}
	detail := fmt.Sprintf("Kiểm tra toàn vẹn hoàn tất: %d/%d phân vùng khớp, %d bản ghi, %d giây.",
		rep.PartitionsMatched, rep.PartitionsTotal, rep.EntriesChecked, int(took.Round(time.Second).Seconds()))
	var alert *uuid.UUID
	if rep.Mismatches > 0 {
		f := rep.First
		where := "niêm phong"
		if f.SeqNo != nil {
			where = fmt.Sprintf("bản ghi %d của phân vùng %d", *f.SeqNo, *f.ChainPartition)
		}
		detail = fmt.Sprintf("Phát hiện chuỗi băm bị phá vỡ tại %s. Đã tạo cảnh báo mức Cao và khoá ghi phân vùng.", where)
		if f.SeqNo == nil {
			detail = "Phát hiện niêm phong sổ cái không khớp. Đã tạo cảnh báo mức Cao."
		}
	}
	err := inTenantRW(ctx, s.Pool, t, func(tx pgx.Tx) error {
		if rep.Mismatches > 0 {
			a, err := s.alert(ctx, tx, id, rep)
			if err != nil {
				return err
			}
			alert = &a
			for tn, parts := range rep.Broken {
				if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tn.String()); err != nil {
					return err
				}
				for _, p := range parts {
					if _, err := tx.Exec(ctx, `UPDATE ledger.chain_head SET is_locked = true, locked_reason = $3, updated_at = clock_timestamp()
						WHERE tenant_id = $1 AND chain_partition = $2 AND NOT is_locked`, tn, p,
						"Phá chuỗi băm do lần kiểm tra toàn vẹn "+id.String()+" phát hiện"); err != nil {
						return err
					}
				}
			}
			v := ""
			if t != nil {
				v = t.String()
			}
			if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, v); err != nil {
				return err
			}
		}
		_, err := tx.Exec(ctx, `UPDATE ledger.verify_run SET entries_checked = $2, mismatches = $3, first_mismatch = $4::jsonb,
				seals_checked = $5, partitions_total = $6, partitions_matched = $7, result_json = $8::jsonb, alert_id = $9,
				status = $10, status_detail = $11, finished_at = clock_timestamp()
			WHERE id = $1`, id, rep.EntriesChecked, rep.Mismatches, first, rep.SealsChecked, rep.PartitionsTotal,
			rep.PartitionsMatched, chain, alert, StatusDone, detail)
		return err
	})
	if err != nil {
		return err
	}
	if rep.Mismatches > 0 {
		s.count("ledger.verify.mismatches_total", int64(rep.Mismatches))
		s.count("ledger.verify.failures_total", 1)
		// Cảnh báo nghiêm trọng cho SOC: nhật ký kiểm toán mức lỗi, không chứa dữ liệu nghiệp vụ.
		s.log().Error("SOC: phát hiện phá vỡ toàn vẹn sổ cái", "audit", true, "verify_run_id", id,
			"tenant_id", req.TenantID, "kind", req.Kind, "mismatches", rep.Mismatches, "reason", rep.First.Reason,
			"chain_partition", rep.First.ChainPartition, "seq_no", rep.First.SeqNo, "alert_id", alert)
	}
	return nil
}

// alert ghi một EVT-20 mức Cao vào outbox cho cả lần kiểm tra. Mã sự kiện tất định theo alertKey (tenant, chuỗi,
// seq_no hoặc niêm phong đầu tiên), nên phát hiện lại cùng chỗ hỏng không tạo cảnh báo mới ở MOD-07.
func (s *Service) alert(ctx context.Context, tx pgx.Tx, runID uuid.UUID, rep *Report) (uuid.UUID, error) {
	f := rep.First
	tn := f.TenantID
	var loc string
	evidence := []string{}
	if f.SeqNo != nil {
		loc = fmt.Sprintf("%d-%d", *f.ChainPartition, *f.SeqNo)
		evidence = append(evidence, "ledger_chain:"+loc)
	} else {
		loc = "seal-" + f.SealID.String()
		evidence = append(evidence, "ledger_chain:"+loc)
	}
	if f.EntryID != nil {
		evidence = append(evidence, "ledger_entry:"+f.EntryID.String())
	}
	if f.SealID != nil {
		evidence = append(evidence, "merkle_seal:"+f.SealID.String())
	}
	evidence = append(evidence, "verify_run:"+runID.String())
	sum := sha256.Sum256([]byte("ledger-integrity|" + tn.String() + "|" + loc))
	key := hex.EncodeToString(sum[:])
	alertID := uuid.NewSHA1(uuid.NameSpaceOID, []byte(key))
	ev, _ := json.Marshal(evidence)
	payload, err := anomalyCodec.BinaryFromNative(nil, map[string]any{
		"alertKey": key, "signalCode": signalCode, "severity": "CAO", "subjectType": "DON_VI",
		"subjectId": tn.String(), "evidenceChain": string(ev), "modelVersion": modelVersion,
	})
	if err != nil {
		return uuid.Nil, err
	}
	if _, err := tx.Exec(ctx, `SELECT set_config('app.tenant_id', $1, true)`, tn.String()); err != nil {
		return uuid.Nil, err
	}
	tctx, err := tenant.WithTenantID(ctx, tn.String())
	if err != nil {
		return uuid.Nil, err
	}
	_, err = s.outbox.Write(tctx, pgxTx{tx}, outbox.Envelope{
		ID: alertID.String(), AggregateType: "DauHieuBatThuong", AggregateID: tn.String(), AggregateVersion: 1,
		EventType: "DauHieuBatThuong", Topic: TopicAnomalyDetected, Payload: payload,
		Classification: outbox.ClassificationC1, CreatedAt: time.Now().UTC(),
	})
	return alertID, err
}

// Get đọc một lần kiểm tra trong phạm vi tenant (lần chạy toàn cell có tenant_id NULL cũng thấy được).
func (s *Service) Get(ctx context.Context, t uuid.UUID, id uuid.UUID) (*Run, error) {
	var out *Run
	err := inTenantRW(ctx, s.Pool, tenantPtr(t), func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, selectRun+` WHERE id = $1`, id)
		if err != nil {
			return err
		}
		runs, err := scanRuns(rows)
		if len(runs) == 1 {
			out = runs[0]
		}
		return err
	})
	if err == nil && out == nil {
		err = ErrNotFound
	}
	return out, err
}

// Cursor là vị trí trang (started_at, id) mã hoá base64url.
func encodeCursor(r *Run) string {
	return base64.RawURLEncoding.EncodeToString([]byte(r.StartedAt.UTC().Format(time.RFC3339Nano) + "|" + r.ID.String()))
}

func decodeCursor(c string) (time.Time, uuid.UUID, error) {
	b, err := base64.RawURLEncoding.DecodeString(c)
	if err != nil {
		return time.Time{}, uuid.Nil, err
	}
	ts, id, ok := strings.Cut(string(b), "|")
	if !ok {
		return time.Time{}, uuid.Nil, errors.New("cursor")
	}
	at, err := time.Parse(time.RFC3339Nano, ts)
	if err != nil {
		return time.Time{}, uuid.Nil, err
	}
	u, err := uuid.Parse(id)
	return at, u, err
}

// ErrBadCursor: con trỏ trang không hợp lệ.
var ErrBadCursor = errors.New("verify: con trỏ trang không hợp lệ")

// List trả các lần kiểm tra mới nhất trước, phân trang con trỏ.
func (s *Service) List(ctx context.Context, t uuid.UUID, kind string, limit int, cursor string) ([]*Run, string, error) {
	q := selectRun + ` WHERE ($1 = '' OR verify_kind = $1)`
	args := []any{kind}
	if cursor != "" {
		at, id, err := decodeCursor(cursor)
		if err != nil {
			return nil, "", ErrBadCursor
		}
		q += ` AND (started_at, id) < ($2, $3)`
		args = append(args, at, id)
	}
	q += ` ORDER BY started_at DESC, id DESC LIMIT ` + strconv.Itoa(limit+1)
	var runs []*Run
	err := inTenantRW(ctx, s.Pool, tenantPtr(t), func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, q, args...)
		if err != nil {
			return err
		}
		runs, err = scanRuns(rows)
		return err
	})
	if err != nil {
		return nil, "", err
	}
	next := ""
	if len(runs) > limit {
		runs = runs[:limit]
		next = encodeCursor(runs[limit-1])
	}
	return runs, next, nil
}

const selectRun = `SELECT id, tenant_id, verify_kind, chain_partition, range_from, range_to, entries_checked, seals_checked,
	partitions_total, partitions_matched, mismatches, first_mismatch, result_json, alert_id, status, coalesce(status_detail, ''),
	requested_by, started_at, finished_at FROM ledger.verify_run`

func scanRuns(rows pgx.Rows) ([]*Run, error) {
	defer rows.Close()
	var out []*Run
	for rows.Next() {
		var (
			r            Run
			first, chain []byte
		)
		if err := rows.Scan(&r.ID, &r.TenantID, &r.Kind, &r.Chain, &r.From, &r.To, &r.EntriesChecked, &r.SealsChecked,
			&r.PartitionsTotal, &r.PartitionsMatched, &r.Mismatches, &first, &chain, &r.AlertID, &r.Status, &r.Detail,
			&r.RequestedBy, &r.StartedAt, &r.FinishedAt); err != nil {
			return nil, err
		}
		if len(first) > 0 {
			r.First = &Mismatch{}
			_ = json.Unmarshal(first, r.First)
		}
		if len(chain) > 0 {
			_ = json.Unmarshal(chain, &r.Links)
		}
		out = append(out, &r)
	}
	return out, rows.Err()
}

// pgxTx chuyển pgx.Tx sang db.Tx của vtplatform cho outbox.Writer.
type pgxTx struct{ pgx.Tx }

func (t pgxTx) Exec(ctx context.Context, sql string, args ...any) (db.CommandTag, error) {
	ct, err := t.Tx.Exec(ctx, sql, args...)
	return db.NewCommandTag(sql, ct.RowsAffected()), err
}

func (t pgxTx) QueryRow(ctx context.Context, sql string, args ...any) db.Row {
	return t.Tx.QueryRow(ctx, sql, args...)
}
