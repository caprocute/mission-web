// Package store đọc ghi schema notify (TL-03 "Schema notify (SVC-12)"): ghi thông báo và lần gửi trong giao
// dịch của consumer (cùng giao dịch với inbox), đọc hộp thông báo và đánh dấu đã đọc cho API, quản lý
// phân vùng ngày giữ 90 ngày. Mọi giao dịch đặt app.tenant_id bằng SET LOCAL để RLS lọc theo tenant.
package store

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/notification/internal/prefs"
	"mission/services-go/notification/internal/render"
	vdb "vn.viettel.okrkpi/services-go/vtplatform/db"
)

// RetentionDays là thời hạn giữ thông báo (TL-03 "Thời hạn lưu giữ": notify 90 ngày).
const RetentionDays = 90

// ChannelInApp là kênh thông báo trong ứng dụng.
const ChannelInApp = "INAPP"

// DefaultLocale là ngôn ngữ mặc định của mẫu.
const DefaultLocale = "vi"

// ErrTemplateNotFound được trả khi không có mẫu chung lẫn mẫu riêng của tenant.
var ErrTemplateNotFound = errors.New("store: không tìm thấy mẫu thông báo")

// ErrInvalidCursor được trả khi con trỏ phân trang không hợp lệ.
var ErrInvalidCursor = errors.New("store: con trỏ phân trang không hợp lệ")

// Beginner bọc pgxpool.Pool thành vdb.TxBeginner cho thư viện vtplatform (NEN-017).
type Beginner struct{ Pool *pgxpool.Pool }

// Begin mở giao dịch pgx.
func (b Beginner) Begin(ctx context.Context) (vdb.Tx, error) {
	tx, err := b.Pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	return pgxTx{tx}, nil
}

type pgxTx struct{ tx pgx.Tx }

func (t pgxTx) Exec(ctx context.Context, sql string, args ...any) (vdb.CommandTag, error) {
	tag, err := t.tx.Exec(ctx, sql, args...)
	if err != nil {
		return vdb.CommandTag{}, err
	}
	return vdb.NewCommandTag(tag.String(), tag.RowsAffected()), nil
}

func (t pgxTx) QueryRow(ctx context.Context, sql string, args ...any) vdb.Row {
	return t.tx.QueryRow(ctx, sql, args...)
}

func (t pgxTx) Commit(ctx context.Context) error   { return t.tx.Commit(ctx) }
func (t pgxTx) Rollback(ctx context.Context) error { return t.tx.Rollback(ctx) }

// NewNotification là thông báo cần ghi cho một người nhận.
type NewNotification struct {
	TenantID      string
	RecipientID   string
	TemplateCode  string
	Params        map[string]any
	DeepLink      *string
	Priority      string
	SourceEventID string
	CreatedAt     time.Time
}

// NotificationID sinh mã tất định theo (sự kiện nguồn, người nhận) để giao lại không tạo dòng mới.
func NotificationID(sourceEventID, recipientID string) uuid.UUID {
	return uuid.NewSHA1(uuid.NameSpaceOID, []byte("notify:"+sourceEventID+":"+recipientID))
}

// Writer ghi thông báo trong giao dịch consumer.
type Writer struct {
	mu   sync.Mutex
	days map[string]bool
	// External là các kênh ngoài (PUSH, SMS, EMAIL) có worker gửi ở bản triển khai này (DV-12-03); rỗng thì
	// chỉ ghi kênh trong ứng dụng.
	External []string
}

// NewWriter tạo Writer.
func NewWriter() *Writer { return &Writer{days: map[string]bool{}} }

func (w *Writer) ensureDay(ctx context.Context, tx vdb.Tx, day time.Time) error {
	key := day.Format(time.DateOnly)
	w.mu.Lock()
	ok := w.days[key]
	w.mu.Unlock()
	if ok {
		return nil
	}
	var created int
	if err := tx.QueryRow(ctx, `SELECT notify.fn_ensure_day_partitions($1::date, 1)`, key).Scan(&created); err != nil {
		return fmt.Errorf("store: tạo phân vùng ngày %s: %w", key, err)
	}
	w.mu.Lock()
	w.days[key] = true
	w.mu.Unlock()
	return nil
}

// Insert ghi notification và delivery kênh INAPP; trả false khi thông báo đã có (giao lại). Mã thông báo
// tính được trước bằng NotificationID.
func (w *Writer) Insert(ctx context.Context, tx vdb.Tx, n NewNotification) (bool, error) {
	if err := render.CheckParams(n.Params); err != nil {
		return false, err
	}
	if n.Params == nil {
		n.Params = map[string]any{}
	}
	params, err := json.Marshal(n.Params)
	if err != nil {
		return false, err
	}
	created := n.CreatedAt.UTC()
	day := time.Date(created.Year(), created.Month(), created.Day(), 0, 0, 0, 0, time.UTC)
	if err := w.ensureDay(ctx, tx, day); err != nil {
		return false, err
	}
	id := NotificationID(n.SourceEventID, n.RecipientID)
	tag, err := tx.Exec(ctx, `
		INSERT INTO notify.notification
		       (created_day, id, tenant_id, recipient_user_id, template_code, params, deep_link, priority,
		        source_event_id, created_at)
		VALUES ($1::date, $2, $3, $4, $5, $6::jsonb, $7, $8, $9, $10)
		ON CONFLICT ON CONSTRAINT uq_notification__source DO NOTHING`,
		day.Format(time.DateOnly), id.String(), n.TenantID, n.RecipientID, n.TemplateCode, string(params),
		n.DeepLink, n.Priority, n.SourceEventID, created)
	if err != nil {
		return false, fmt.Errorf("store: ghi notification: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return false, nil
	}
	// Thông báo trong ứng dụng coi như đã gửi khi đã ghi vào hộp thông báo; SSE (DV-12-02) chỉ đẩy nhanh.
	deliveryID := uuid.NewSHA1(id, []byte(ChannelInApp))
	if _, err := tx.Exec(ctx, `
		INSERT INTO notify.delivery (created_day, id, tenant_id, notification_id, channel, status, attempts, sent_at)
		VALUES ($1::date, $2, $3, $4, 'INAPP', 'DA_GUI', 1, $5)`,
		day.Format(time.DateOnly), deliveryID.String(), n.TenantID, id.String(), created); err != nil {
		return false, fmt.Errorf("store: ghi delivery: %w", err)
	}
	if len(w.External) == 0 {
		return true, nil
	}
	// Kênh ngoài theo tuỳ chọn của nhóm thông báo (mặc định khi chưa chọn), xếp hàng trạng thái CHO cho worker
	// từng kênh; giờ yên lặng và việc kênh còn bật được xét lại lúc gửi. Mã delivery tất định theo kênh.
	defaults := make([]string, 0, len(prefs.DefaultChannels))
	for _, c := range prefs.DefaultChannels {
		defaults = append(defaults, prefs.APIToDB(c))
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO notify.delivery (created_day, id, tenant_id, notification_id, channel, status, attempts,
		                             next_attempt_at)
		SELECT $1::date, md5($4::text || ':' || ch)::uuid, $2::uuid, $4::uuid, ch, 'CHO', 0, $5
		  FROM unnest(coalesce((SELECT channels FROM notify.channel_pref
		                         WHERE tenant_id = $2 AND user_id = $3 AND category = $6), $7::varchar[])) AS ch
		 WHERE ch = ANY($8::varchar[]) AND ch <> 'INAPP'`,
		day.Format(time.DateOnly), n.TenantID, n.RecipientID, id.String(), created, prefs.CategoryOf(n.TemplateCode),
		defaults, w.External); err != nil {
		return false, fmt.Errorf("store: xếp hàng kênh ngoài: %w", err)
	}
	return true, nil
}

// TemplateExists kiểm có mẫu kênh INAPP cho mã mẫu (mẫu riêng của tenant hoặc mẫu chung).
func TemplateExists(ctx context.Context, tx vdb.Tx, code string) (bool, error) {
	var ok bool
	err := tx.QueryRow(ctx, `
		SELECT EXISTS (SELECT 1 FROM notify.template
		                WHERE template_code = $1 AND channel = 'INAPP' AND locale = $2)`,
		code, DefaultLocale).Scan(&ok)
	return ok, err
}

// SubscribersOf trả người dùng của tenant hiện tại đã bật kênh INAPP cho nhóm thông báo category.
func SubscribersOf(ctx context.Context, tx vdb.Tx, category string) ([]string, error) {
	var ids []string
	err := tx.QueryRow(ctx, `
		SELECT coalesce(array_agg(user_id::text ORDER BY user_id), '{}')
		  FROM notify.channel_pref
		 WHERE category = $1 AND 'INAPP' = ANY(channels)`, category).Scan(&ids)
	return ids, err
}

// Notification là một dòng hộp thông báo đã dựng nội dung.
type Notification struct {
	ID           string         `json:"id"`
	TemplateCode string         `json:"templateCode"`
	Title        string         `json:"title"`
	Body         string         `json:"body"`
	Params       map[string]any `json:"params"`
	DeepLink     *string        `json:"deepLink"`
	Priority     string         `json:"priority"`
	CreatedAt    time.Time      `json:"createdAt"`
	ReadAt       *time.Time     `json:"readAt"`
}

// Page là một trang hộp thông báo (lược đồ NotificationPage).
type Page struct {
	Items       []Notification `json:"items"`
	NextCursor  *string        `json:"nextCursor"`
	UnreadCount int            `json:"unreadCount"`
}

type cursor struct {
	T time.Time `json:"t"`
	I string    `json:"i"`
}

func encodeCursor(c cursor) string {
	b, _ := json.Marshal(c)
	return base64.RawURLEncoding.EncodeToString(b)
}

func decodeCursor(s string) (*cursor, error) {
	if s == "" {
		return nil, nil
	}
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return nil, ErrInvalidCursor
	}
	var c cursor
	if err := json.Unmarshal(b, &c); err != nil || c.T.IsZero() {
		return nil, ErrInvalidCursor
	}
	if _, err := uuid.Parse(c.I); err != nil {
		return nil, ErrInvalidCursor
	}
	return &c, nil
}

// Reader đọc và cập nhật hộp thông báo cho API.
type Reader struct{ Pool *pgxpool.Pool }

func (r Reader) inTenant(ctx context.Context, tenantID string, fn func(pgx.Tx) error) error {
	tx, err := r.Pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, vdb.SetLocalTenantSQL, tenantID); err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

const retentionFilter = `created_day >= current_date - 90`

func unreadCount(ctx context.Context, tx pgx.Tx, tenantID, userID string) (int, error) {
	var n int
	err := tx.QueryRow(ctx, `SELECT count(*) FROM notify.notification
		WHERE tenant_id = $1 AND recipient_user_id = $2 AND read_at IS NULL AND `+retentionFilter,
		tenantID, userID).Scan(&n)
	return n, err
}

// List trả trang thông báo của người dùng, mới nhất trước.
func (r Reader) List(ctx context.Context, tenantID, userID string, unreadOnly bool, limit int, cur string) (*Page, error) {
	c, err := decodeCursor(cur)
	if err != nil {
		return nil, err
	}
	page := &Page{Items: []Notification{}}
	err = r.inTenant(ctx, tenantID, func(tx pgx.Tx) error {
		q := strings.Builder{}
		q.WriteString(`SELECT id::text, template_code, params, deep_link, priority, created_at, read_at
			FROM notify.notification
			WHERE tenant_id = $1 AND recipient_user_id = $2 AND ` + retentionFilter)
		args := []any{tenantID, userID}
		if unreadOnly {
			q.WriteString(` AND read_at IS NULL`)
		}
		if c != nil {
			args = append(args, c.T, c.I)
			q.WriteString(fmt.Sprintf(` AND (created_at, id) < ($%d, $%d::uuid)`, len(args)-1, len(args)))
		}
		args = append(args, limit+1)
		q.WriteString(fmt.Sprintf(` ORDER BY created_at DESC, id DESC LIMIT $%d`, len(args)))
		rows, err := tx.Query(ctx, q.String(), args...)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var n Notification
			var params []byte
			if err := rows.Scan(&n.ID, &n.TemplateCode, &params, &n.DeepLink, &n.Priority, &n.CreatedAt, &n.ReadAt); err != nil {
				return err
			}
			if err := json.Unmarshal(params, &n.Params); err != nil {
				return err
			}
			page.Items = append(page.Items, n)
		}
		if err := rows.Err(); err != nil {
			return err
		}
		if len(page.Items) > limit {
			page.Items = page.Items[:limit]
			last := page.Items[limit-1]
			nc := encodeCursor(cursor{T: last.CreatedAt, I: last.ID})
			page.NextCursor = &nc
		}
		if err := renderItems(ctx, tx, page.Items); err != nil {
			return err
		}
		page.UnreadCount, err = unreadCount(ctx, tx, tenantID, userID)
		return err
	})
	if err != nil {
		return nil, err
	}
	return page, nil
}

// renderItems dựng tiêu đề, nội dung theo mẫu INAPP tiếng Việt; mẫu riêng của tenant thắng mẫu chung.
func renderItems(ctx context.Context, tx pgx.Tx, items []Notification) error {
	cache := map[string]*render.Template{}
	for i := range items {
		code := items[i].TemplateCode
		t, ok := cache[code]
		if !ok {
			var tpl render.Template
			err := tx.QueryRow(ctx, `
				SELECT subject, body FROM notify.template
				 WHERE template_code = $1 AND channel = 'INAPP' AND locale = $2
				 ORDER BY tenant_id NULLS LAST LIMIT 1`, code, DefaultLocale).Scan(&tpl.Subject, &tpl.Body)
			switch {
			case errors.Is(err, pgx.ErrNoRows):
				t = nil
			case err != nil:
				return err
			default:
				tpl.Code = code
				t = &tpl
			}
			cache[code] = t
		}
		if t == nil {
			items[i].Title = "Đồng chí có thông báo mới"
			continue
		}
		title, body, err := t.Render(items[i].Params)
		if err != nil {
			items[i].Title = "Đồng chí có thông báo mới"
			continue
		}
		items[i].Title, items[i].Body = title, body
	}
	return nil
}

// MarkRead đặt read_at cho ids, hoặc mọi thông báo chưa đọc khi all; trả số dòng đổi và số chưa đọc còn lại.
func (r Reader) MarkRead(ctx context.Context, tenantID, userID string, ids []string, all bool) (updated, unread int, err error) {
	err = r.inTenant(ctx, tenantID, func(tx pgx.Tx) error {
		var tag interface{ RowsAffected() int64 }
		var e error
		if all {
			tag, e = tx.Exec(ctx, `UPDATE notify.notification SET read_at = now()
				WHERE tenant_id = $1 AND recipient_user_id = $2 AND read_at IS NULL AND `+retentionFilter,
				tenantID, userID)
		} else {
			tag, e = tx.Exec(ctx, `UPDATE notify.notification SET read_at = now()
				WHERE tenant_id = $1 AND recipient_user_id = $2 AND read_at IS NULL AND id = ANY($3::uuid[]) AND `+retentionFilter,
				tenantID, userID, ids)
		}
		if e != nil {
			return e
		}
		updated = int(tag.RowsAffected())
		unread, e = unreadCount(ctx, tx, tenantID, userID)
		return e
	})
	return updated, unread, err
}

// SeedTemplate là một mẫu trong data/seed/notify.
type SeedTemplate struct {
	TemplateCode string `json:"templateCode"`
	Channel      string `json:"channel"`
	Locale       string `json:"locale"`
	Subject      string `json:"subject"`
	Body         string `json:"body"`
}

// SeedFile là tệp mẫu chung.
type SeedFile struct {
	Templates []SeedTemplate `json:"templates"`
}

// ParseSeed đọc và kiểm tệp mẫu chung (cú pháp, không có tham số C2, không trùng khoá).
func ParseSeed(b []byte) (*SeedFile, error) {
	var f SeedFile
	if err := json.Unmarshal(b, &f); err != nil {
		return nil, err
	}
	seen := map[string]bool{}
	for _, t := range f.Templates {
		key := t.TemplateCode + "/" + t.Channel + "/" + t.Locale
		if seen[key] {
			return nil, fmt.Errorf("store: mẫu trùng khoá %s", key)
		}
		seen[key] = true
		if len(t.TemplateCode) == 0 || len(t.TemplateCode) > 48 {
			return nil, fmt.Errorf("store: mã mẫu %q không hợp lệ", t.TemplateCode)
		}
		if err := (render.Template{Code: t.TemplateCode, Subject: t.Subject, Body: t.Body}).Validate(); err != nil {
			return nil, fmt.Errorf("mẫu %s: %w", t.TemplateCode, err)
		}
	}
	return &f, nil
}

// UpsertGlobalTemplates nạp mẫu chung (tenant_id NULL); chạy bằng vai trò mig_notify.
func UpsertGlobalTemplates(ctx context.Context, conn interface {
	Exec(context.Context, string, ...any) (pgconn.CommandTag, error)
}, f *SeedFile) error {
	for _, t := range f.Templates {
		id := uuid.NewSHA1(uuid.NameSpaceOID, []byte("notify-template:"+t.TemplateCode+":"+t.Channel+":"+t.Locale))
		if _, err := conn.Exec(ctx, `
			INSERT INTO notify.template (id, tenant_id, template_code, channel, locale, subject, body)
			VALUES ($1, NULL, $2, $3, $4, $5, $6)
			ON CONFLICT ON CONSTRAINT uq_template__key
			DO UPDATE SET subject = EXCLUDED.subject, body = EXCLUDED.body`,
			id.String(), t.TemplateCode, t.Channel, t.Locale, t.Subject, t.Body); err != nil {
			return fmt.Errorf("store: nạp mẫu %s: %w", t.TemplateCode, err)
		}
	}
	return nil
}

// Maintain tạo trước phân vùng 7 ngày tới và xoá phân vùng quá RetentionDays; trả số phân vùng tạo, xoá.
func Maintain(ctx context.Context, pool *pgxpool.Pool, today time.Time) (created, dropped int, err error) {
	day := today.UTC().Format(time.DateOnly)
	if err = pool.QueryRow(ctx, `SELECT notify.fn_ensure_day_partitions($1::date, 8)`, day).Scan(&created); err != nil {
		return 0, 0, err
	}
	if err = pool.QueryRow(ctx, `SELECT notify.fn_drop_expired_partitions($1, $2::date)`, RetentionDays, day).Scan(&dropped); err != nil {
		return created, 0, err
	}
	return created, dropped, nil
}

// ByIDs trả các thông báo đã dựng nội dung theo mã cùng số chưa đọc của người dùng (dùng cho luồng SSE).
func (r Reader) ByIDs(ctx context.Context, tenantID, userID string, ids []string) ([]Notification, int, error) {
	var items []Notification
	var unread int
	err := r.inTenant(ctx, tenantID, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, `SELECT id::text, template_code, params, deep_link, priority, created_at, read_at
			FROM notify.notification
			WHERE tenant_id = $1 AND recipient_user_id = $2 AND id = ANY($3::uuid[]) AND `+retentionFilter+`
			ORDER BY created_at, id`, tenantID, userID, ids)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var n Notification
			var params []byte
			if err := rows.Scan(&n.ID, &n.TemplateCode, &params, &n.DeepLink, &n.Priority, &n.CreatedAt, &n.ReadAt); err != nil {
				return err
			}
			if err := json.Unmarshal(params, &n.Params); err != nil {
				return err
			}
			items = append(items, n)
		}
		if err := rows.Err(); err != nil {
			return err
		}
		if err := renderItems(ctx, tx, items); err != nil {
			return err
		}
		unread, err = unreadCount(ctx, tx, tenantID, userID)
		return err
	})
	return items, unread, err
}
