// Package deliver chạy worker gửi kênh ngoài của SVC-12 (DV-12-03). Mỗi kênh (EMAIL, SMS, PUSH) có vòng nhận việc
// riêng trên bảng notify.delivery trạng thái CHO, bulkhead riêng (số lần gửi đồng thời) và ngắt mạch riêng, nên cổng
// SMS lỗi chỉ làm việc SMS xếp hàng chờ, không làm chậm kênh khác hay thông báo trong ứng dụng (đã ghi ngay khi
// nhận sự kiện). Lúc gửi, worker xét lại tuỳ chọn kênh và giờ yên lặng, lấy địa chỉ trong bộ nhớ, dựng nội dung
// theo mẫu của kênh, rồi ghi DA_GUI, hẹn thử lại có giới hạn với backoff, hoặc THAT_BAI, BO_QUA.
package deliver

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/notification/internal/channel"
	"mission/services-go/notification/internal/crypt"
	"mission/services-go/notification/internal/directory"
	"mission/services-go/notification/internal/prefs"
	"mission/services-go/notification/internal/ratelimit"
	"mission/services-go/notification/internal/render"
	vdb "vn.viettel.okrkpi/services-go/vtplatform/db"
)

// Cột dùng làm khoá con chỉ mục mù của địa chỉ nhận.
const addressColumn = "notify.delivery.address_bidx"

// Defaults.
var (
	DefaultBackoff = []time.Duration{30 * time.Second, 2 * time.Minute, 10 * time.Minute, 30 * time.Minute}
	// MaxAge là tuổi tối đa của việc chờ: thông báo cũ hơn không gửi kênh ngoài nữa (suy luận).
	MaxAge = 72 * time.Hour
)

// Lane là cấu hình một kênh.
type Lane struct {
	Channel     string
	Sender      channel.Sender
	Concurrency int // bulkhead
	Breaker     *channel.Breaker
}

// Worker gửi các kênh ngoài.
type Worker struct {
	Pool        *pgxpool.Pool
	Prefs       prefs.Store
	Directory   directory.Directory
	Field       crypt.Field
	Lanes       []Lane
	Batch       int
	Lease       time.Duration
	Poll        time.Duration
	MaxAttempts int
	Backoff     []time.Duration
	Log         *slog.Logger
	Now         func() time.Time
	// Counter nhận bộ đếm vận hành (tuỳ chọn), ví dụ otel.Provider.AddCounter.
	Counter func(name string, delta int64)
	// Limiter giới hạn tốc độ việc của đợt hàng loạt theo kênh (DV-12-04); nil là không giới hạn.
	Limiter ratelimit.Limiter
	// Limits dùng để ước tính thời gian còn lại của đợt.
	Limits ratelimit.Limits

	pending sync.Map // kênh → *atomic.Int64
}

// Tên bộ đếm.
const (
	MetricSent    = "notify_delivery_sent_total"
	MetricFailed  = "notify_delivery_failed_total"
	MetricRetried = "notify_delivery_retried_total"
	MetricSkipped = "notify_delivery_skipped_total"
)

// MetricNames là các bộ đếm /metrics hiển thị: tổng và theo từng kênh (hậu tố _email, _sms, _push).
var MetricNames = func() []string {
	var out []string
	for _, m := range []string{MetricSent, MetricFailed, MetricRetried, MetricSkipped} {
		out = append(out, m, m+"_email", m+"_sms", m+"_push")
	}
	return out
}()

func (w *Worker) defaults() {
	if w.Batch <= 0 {
		w.Batch = 50
	}
	if w.Lease <= 0 {
		w.Lease = 2 * time.Minute
	}
	if w.Poll <= 0 {
		w.Poll = time.Second
	}
	if w.MaxAttempts <= 0 {
		w.MaxAttempts = 5
	}
	if len(w.Backoff) == 0 {
		w.Backoff = DefaultBackoff
	}
	if w.Log == nil {
		w.Log = slog.Default()
	}
	if w.Now == nil {
		w.Now = time.Now
	}
}

func (w *Worker) count(name, ch string) {
	if w.Counter != nil {
		w.Counter(name, 1)
		w.Counter(name+"_"+strings.ToLower(ch), 1)
	}
}

// Run chạy vòng nhận việc của mọi kênh tới khi ctx dừng.
func (w *Worker) Run(ctx context.Context) {
	w.defaults()
	var wg sync.WaitGroup
	for _, l := range w.Lanes {
		wg.Add(1)
		go func() {
			defer wg.Done()
			w.lane(ctx, l)
		}()
	}
	go w.gauge(ctx)
	wg.Wait()
}

func (w *Worker) lane(ctx context.Context, l Lane) {
	if l.Concurrency <= 0 {
		l.Concurrency = 4
	}
	sem := make(chan struct{}, l.Concurrency)
	for ctx.Err() == nil {
		n := 0
		if l.Breaker == nil || !l.Breaker.IsOpen() {
			var err error
			n, err = w.RunOnce(ctx, l, sem)
			if err != nil && ctx.Err() == nil {
				w.Log.Error("nhận việc gửi thất bại", "channel", l.Channel, "err", err.Error())
			}
		}
		if n == 0 {
			select {
			case <-ctx.Done():
			case <-time.After(w.Poll):
			}
		}
	}
}

type key struct {
	tenant, id string
	day        time.Time
	bulk       bool
}

// RunOnce nhận một lô việc thường rồi một lô việc hàng loạt (trong giới hạn tốc độ còn lại) của kênh và gửi hết,
// song song tối đa theo bulkhead; trả số việc đã nhận.
func (w *Worker) RunOnce(ctx context.Context, l Lane, sem chan struct{}) (int, error) {
	w.defaults()
	if sem == nil {
		sem = make(chan struct{}, max(1, l.Concurrency))
	}
	n, err := w.claimAndSend(ctx, l, sem, false, w.Batch)
	if err != nil {
		return n, err
	}
	// Xem trước số lượt còn trong cửa sổ để chỉ nhận chừng đó việc hàng loạt; lượt thật được giữ ngay trước mỗi lần
	// gửi, nên thời điểm tính giới hạn sát thời điểm cổng nhận tin.
	granted := w.Batch
	if w.Limiter != nil {
		var release func(int)
		if granted, release, err = w.Limiter.Reserve(ctx, l.Channel, w.Batch); err != nil {
			return n, err
		}
		release(granted)
	}
	if granted == 0 {
		return n, nil
	}
	m, err := w.claimAndSend(ctx, l, sem, true, granted)
	return n + m, err
}

func (w *Worker) claimAndSend(ctx context.Context, l Lane, sem chan struct{}, bulk bool, limit int) (int, error) {
	rows, err := w.Pool.Query(ctx, `SELECT tenant_id::text, created_day, id::text FROM notify.fn_claim_deliveries($1, $2, $3, $4)`,
		l.Channel, limit, w.Lease, bulk)
	if err != nil {
		return 0, err
	}
	keys, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (key, error) {
		var k key
		return k, r.Scan(&k.tenant, &k.day, &k.id)
	})
	if err != nil {
		return 0, err
	}
	var wg sync.WaitGroup
	for _, k := range keys {
		k.bulk = bulk
		sem <- struct{}{}
		wg.Add(1)
		go func() {
			defer func() { <-sem; wg.Done() }()
			if err := w.one(ctx, l, k); err != nil && ctx.Err() == nil {
				w.Log.Error("gửi thông báo thất bại", "channel", l.Channel, "delivery", k.id, "err", err.Error())
			}
		}()
	}
	wg.Wait()
	return len(keys), nil
}

type job struct {
	channel, recipient, template, priority string
	deepLink                               *string
	params                                 map[string]any
	attempts                               int
	createdAt                              time.Time
}

func (w *Worker) inTenant(ctx context.Context, tenant string, fn func(pgx.Tx) error) error {
	tx, err := w.Pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, vdb.SetLocalTenantSQL, tenant); err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (w *Worker) load(ctx context.Context, k key) (*job, error) {
	var j job
	var raw []byte
	err := w.inTenant(ctx, k.tenant, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `
			SELECT d.channel, d.attempts, n.recipient_user_id::text, n.template_code, n.priority, n.deep_link,
			       n.params, n.created_at
			  FROM notify.delivery d
			  JOIN notify.notification n ON n.tenant_id = d.tenant_id AND n.id = d.notification_id
			 WHERE d.created_day = $1 AND d.id = $2 AND d.status = 'CHO'`, k.day, k.id).
			Scan(&j.channel, &j.attempts, &j.recipient, &j.template, &j.priority, &j.deepLink, &raw, &j.createdAt)
	})
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(raw, &j.params); err != nil {
		return nil, err
	}
	return &j, nil
}

// outcome là kết quả ghi lại một việc.
type outcome struct {
	status   string // DA_GUI, THAT_BAI, BO_QUA, CHO
	next     time.Time
	attempt  bool
	provider string
	errCode  string
	bidx     []byte
}

func (w *Worker) one(ctx context.Context, l Lane, k key) error {
	j, err := w.load(ctx, k)
	if err != nil || j == nil {
		return err
	}
	now := w.Now()
	if now.Sub(j.createdAt) > MaxAge {
		return w.finish(ctx, k, outcome{status: "BO_QUA", errCode: "EXPIRED"}, l.Channel, MetricSkipped)
	}
	v, err := w.Prefs.Decide(ctx, k.tenant, j.recipient, j.template, j.priority, l.Channel, now)
	if err != nil {
		return w.retry(ctx, k, j, l, "PREFS", err)
	}
	if v.Off {
		return w.finish(ctx, k, outcome{status: "BO_QUA", errCode: "CHANNEL_OFF"}, l.Channel, MetricSkipped)
	}
	if !v.ResumeAt.IsZero() {
		return w.finish(ctx, k, outcome{status: "CHO", next: v.ResumeAt, errCode: "QUIET_HOURS"}, l.Channel, "")
	}
	subject, body, err := w.content(ctx, k.tenant, j.template, l.Channel, j.params)
	if err != nil {
		return w.finish(ctx, k, outcome{status: "THAT_BAI", errCode: "TEMPLATE"}, l.Channel, MetricFailed)
	}
	msg := channel.Message{Subject: subject, Body: body, High: j.priority == "CAO", Key: k.id}
	if j.deepLink != nil {
		msg.DeepLink = *j.deepLink
	}
	var targets []channel.Message
	switch l.Channel {
	case channel.Push:
		for _, d := range v.Devices {
			if d.Platform == "WEB_PUSH" {
				continue // Web Push (RFC 8291) chưa hiện thực ở DV-12-03, thông báo web đi qua SSE
			}
			m := msg
			m.To, m.Platform = d.Token, d.Platform
			targets = append(targets, m)
		}
	default:
		c, err := w.Directory.Contact(ctx, k.tenant, j.recipient)
		if errors.Is(err, directory.ErrNotFound) {
			return w.finish(ctx, k, outcome{status: "BO_QUA", errCode: "NO_ADDRESS"}, l.Channel, MetricSkipped)
		}
		if err != nil {
			return w.retry(ctx, k, j, l, "DIRECTORY", err)
		}
		msg.To = c.Email
		if l.Channel == channel.SMS {
			msg.To = c.Phone
		}
		if msg.To != "" {
			targets = append(targets, msg)
		}
	}
	if len(targets) == 0 {
		return w.finish(ctx, k, outcome{status: "BO_QUA", errCode: "NO_ADDRESS"}, l.Channel, MetricSkipped)
	}
	if l.Breaker != nil && !l.Breaker.Allow() {
		// Mạch mở: trả việc về hàng đợi, không tính lần thử; gửi lại khi cổng phục hồi.
		return w.finish(ctx, k, outcome{status: "CHO", next: now.Add(w.Poll)}, l.Channel, "")
	}
	if k.bulk && w.Limiter != nil {
		got, release, err := w.Limiter.Reserve(ctx, l.Channel, len(targets))
		if err != nil || got < len(targets) {
			release(got)
			// Hết lượt trong cửa sổ (hoặc lượt chỉ đủ một phần): trả việc về hàng đợi, không tính lần thử.
			if l.Breaker != nil {
				l.Breaker.Release()
			}
			return w.finish(ctx, k, outcome{status: "CHO", next: now.Add(w.Poll)}, l.Channel, "")
		}
	}
	var sentID string
	var lastErr error
	sent := 0
	for i, m := range targets {
		id, err := l.Sender.Send(ctx, m)
		switch {
		case err == nil:
			sent++
			if sentID == "" {
				sentID = id
			}
		case errors.Is(err, channel.ErrUnregistered) && l.Channel == channel.Push:
			if rerr := w.Prefs.RevokeDevice(ctx, k.tenant, v.Devices[deviceIndex(v.Devices, targets, i)].DeviceID); rerr != nil {
				w.Log.Error("không thu hồi được thiết bị", "delivery", k.id, "err", rerr.Error())
			}
			lastErr = err
		default:
			lastErr = err
		}
	}
	var transient channel.Transient
	if l.Breaker != nil {
		if sent > 0 || !errors.As(lastErr, &transient) {
			l.Breaker.Success()
		} else {
			l.Breaker.Failure()
		}
	}
	if sent > 0 && !k.bulk && w.Limiter != nil {
		w.Limiter.Note(ctx, l.Channel)
	}
	if sent > 0 {
		o := outcome{status: "DA_GUI", attempt: true, provider: trim(sentID, 128)}
		if l.Channel != channel.Push {
			o.bidx, _ = w.Field.BlindIndex(k.tenant, addressColumn, targets[0].To)
		}
		return w.finish(ctx, k, o, l.Channel, MetricSent)
	}
	if errors.As(lastErr, &transient) {
		return w.retry(ctx, k, j, l, channel.Code(lastErr), nil)
	}
	return w.finish(ctx, k, outcome{status: "THAT_BAI", attempt: true, errCode: channel.Code(lastErr)}, l.Channel,
		MetricFailed)
}

// deviceIndex tìm thiết bị ứng với đích thứ i (đã bỏ thiết bị WEB_PUSH).
func deviceIndex(devs []prefs.PushTarget, targets []channel.Message, i int) int {
	for j, d := range devs {
		if d.Token == targets[i].To {
			return j
		}
	}
	return 0
}

func (w *Worker) retry(ctx context.Context, k key, j *job, l Lane, code string, cause error) error {
	if cause != nil {
		w.Log.Warn("lỗi tạm thời khi chuẩn bị gửi", "channel", l.Channel, "delivery", k.id, "code", code,
			"err", cause.Error())
	}
	n := j.attempts + 1
	if n >= w.MaxAttempts {
		return w.finish(ctx, k, outcome{status: "THAT_BAI", attempt: true, errCode: code}, l.Channel, MetricFailed)
	}
	wait := w.Backoff[min(n-1, len(w.Backoff)-1)]
	return w.finish(ctx, k, outcome{status: "CHO", attempt: true, next: w.Now().Add(wait), errCode: code}, l.Channel,
		MetricRetried)
}

func (w *Worker) finish(ctx context.Context, k key, o outcome, ch, metric string) error {
	inc := 0
	if o.attempt {
		inc = 1
	}
	var sentAt *time.Time
	if o.status == "DA_GUI" {
		t := w.Now()
		sentAt = &t
	}
	var next *time.Time
	if !o.next.IsZero() {
		next = &o.next
	}
	var errCode *string
	if o.errCode != "" {
		errCode = &o.errCode
	}
	var provider *string
	if o.provider != "" {
		provider = &o.provider
	}
	err := w.inTenant(ctx, k.tenant, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `
			UPDATE notify.delivery
			   SET status = $3, attempts = attempts + $4, next_attempt_at = coalesce($5, next_attempt_at),
			       last_error = $6, provider_msg_id = coalesce($7, provider_msg_id), sent_at = $8,
			       address_bidx = coalesce($9, address_bidx), locked_until = NULL
			 WHERE created_day = $1 AND id = $2 AND status = 'CHO'`,
			k.day, k.id, o.status, inc, next, errCode, provider, sentAt, o.bidx)
		return err
	})
	if err != nil {
		return fmt.Errorf("deliver: ghi kết quả: %w", err)
	}
	if metric != "" {
		w.count(metric, ch)
	}
	return nil
}

// content dựng tiêu đề, nội dung theo mẫu của kênh; chưa có mẫu riêng của kênh thì dùng mẫu INAPP. Mẫu riêng của
// tenant thắng mẫu chung.
func (w *Worker) content(ctx context.Context, tenant, code, ch string, params map[string]any) (string, string, error) {
	var t render.Template
	err := w.inTenant(ctx, tenant, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, `
			SELECT subject, body FROM notify.template
			 WHERE template_code = $1 AND channel IN ($2, 'INAPP') AND locale = 'vi'
			 ORDER BY channel = $2 DESC, tenant_id NULLS LAST LIMIT 1`, code, ch).Scan(&t.Subject, &t.Body)
	})
	if errors.Is(err, pgx.ErrNoRows) {
		return "Đồng chí có thông báo mới", "Đề nghị đồng chí mở ứng dụng Mission để xem chi tiết.", nil
	}
	if err != nil {
		return "", "", err
	}
	t.Code = code
	return t.Render(params)
}

func (w *Worker) gauge(ctx context.Context) {
	for ctx.Err() == nil {
		if err := w.refreshPending(ctx); err != nil && ctx.Err() == nil {
			w.Log.Warn("không đọc được độ dài hàng đợi", "err", err.Error())
		}
		select {
		case <-ctx.Done():
		case <-time.After(15 * time.Second):
		}
	}
}

func (w *Worker) refreshPending(ctx context.Context) error {
	rows, err := w.Pool.Query(ctx, `SELECT channel, due, bulk_pending FROM notify.fn_pending_counts()`)
	if err != nil {
		return err
	}
	seen, bulk := map[string]int64{}, map[string]int64{}
	for rows.Next() {
		var ch string
		var n, b int64
		if err := rows.Scan(&ch, &n, &b); err != nil {
			rows.Close()
			return err
		}
		seen[ch], bulk[ch] = n, b
	}
	rows.Close()
	for _, l := range w.Lanes {
		w.counter(l.Channel).Store(seen[l.Channel])
		w.counter("bulk:" + l.Channel).Store(bulk[l.Channel])
	}
	return rows.Err()
}

func (w *Worker) counter(ch string) *atomic.Int64 {
	v, _ := w.pending.LoadOrStore(ch, new(atomic.Int64))
	return v.(*atomic.Int64)
}

// Gauges trả số việc đến hạn đang chờ của từng kênh cho /metrics (KEDA co giãn worker theo kênh).
func (w *Worker) Gauges() map[string]func() int64 {
	out := map[string]func() int64{}
	for _, l := range w.Lanes {
		c := w.counter(l.Channel)
		out["notify_delivery_pending_"+strings.ToLower(l.Channel)] = c.Load
		b := w.counter("bulk:" + l.Channel)
		out["notify_bulk_pending_"+strings.ToLower(l.Channel)] = b.Load
		rate := w.Limits.Rate(l.Channel)
		out["notify_bulk_eta_seconds_"+strings.ToLower(l.Channel)] = func() int64 {
			return ETA(b.Load(), rate)
		}
	}
	return out
}

func trim(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

// ETA ước tính số giây để gửi hết remaining việc hàng loạt ở tốc độ rate lần mỗi giây (giới hạn kênh); không giới
// hạn thì trả 0. Công thức: thời gian ≈ ⌈còn lại / (giới hạn trong cửa sổ / độ dài cửa sổ)⌉.
func ETA(remaining int64, rate float64) int64 {
	if rate <= 0 || remaining <= 0 {
		return 0
	}
	return int64(float64(remaining)/rate + 0.999)
}
