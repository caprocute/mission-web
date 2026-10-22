// Package prefs quản lý thiết bị nhận thông báo đẩy (notify.push_device, bổ sung DV-12-05) và tuỳ chọn kênh theo
// nhóm thông báo (notify.channel_pref của TL-03, phiên bản ở notify.channel_pref_meta), cùng bộ định tuyến kênh mà
// worker kênh ngoài của DV-12-03 dùng: kênh được bật của nhóm, giờ yên lặng, thiết bị chưa thu hồi.
package prefs

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/notification/internal/crypt"
	vdb "vn.viettel.okrkpi/services-go/vtplatform/db"
)

// Cột mã thiết bị dùng trong AAD và khoá con chỉ mục mù.
const tokenColumn = "notify.push_device.token_enc"

// StaleDeviceDays là số ngày không thấy thiết bị thì thu hồi (suy luận).
const StaleDeviceDays = 90

// Nhóm thông báo theo platform.yaml NotificationCategory.
var Categories = []string{"NHIEM_VU", "PHE_DUYET", "DANH_GIA", "KY_SO", "CANH_BAO_BAO_MAT", "HE_THONG"}

// MandatoryInApp là nhóm không được tắt kênh trong ứng dụng: kết quả xếp loại, hạn khiếu nại (DANH_GIA) và
// cảnh báo bảo mật (suy luận, danh sách chờ người phụ trách duyệt).
var MandatoryInApp = map[string]bool{"DANH_GIA": true, "CANH_BAO_BAO_MAT": true}

// DefaultChannels là kênh mặc định khi người dùng chưa chọn (suy luận).
var DefaultChannels = []string{"INAPP", "PUSH"}

// Lỗi nghiệp vụ.
var (
	ErrNotFound        = errors.New("prefs: không tìm thấy")
	ErrVersionConflict = errors.New("prefs: phiên bản đã đổi")
	ErrInvalid         = errors.New("prefs: dữ liệu không hợp lệ")
)

// Kênh theo hợp đồng (IN_APP) và theo CSDL (INAPP).
func apiToDB(c string) string {
	if c == "IN_APP" {
		return "INAPP"
	}
	return c
}

func dbToAPI(c string) string {
	if c == "INAPP" {
		return "IN_APP"
	}
	return c
}

// CategoryOf ánh xạ mã mẫu thông báo sang nhóm thông báo (suy luận theo tiền tố mã mẫu).
func CategoryOf(templateCode string) string {
	switch {
	case strings.HasPrefix(templateCode, "NV_"), templateCode == "OKR_NHAC_CHECKIN", strings.HasPrefix(templateCode, "MC_"):
		return "NHIEM_VU"
	case strings.HasSuffix(templateCode, "_CHO_DUYET"):
		return "PHE_DUYET"
	case strings.HasPrefix(templateCode, "HS_"), strings.HasPrefix(templateCode, "KQ_"), strings.HasPrefix(templateCode, "KN_"):
		return "DANH_GIA"
	case strings.HasPrefix(templateCode, "KY_"):
		return "KY_SO"
	case strings.HasPrefix(templateCode, "BM_"):
		return "CANH_BAO_BAO_MAT"
	default:
		return "HE_THONG"
	}
}

// Store đọc ghi thiết bị và tuỳ chọn.
type Store struct {
	Pool  *pgxpool.Pool
	Field crypt.Field
}

func (s Store) inTenant(ctx context.Context, tenantID string, fn func(pgx.Tx) error) error {
	tx, err := s.Pool.Begin(ctx)
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

// Device là thiết bị trả cho người dùng; không bao giờ mang mã thiết bị.
type Device struct {
	ID           string    `json:"id"`
	Platform     string    `json:"platform"`
	DeviceName   string    `json:"deviceName"`
	AppVersion   *string   `json:"appVersion,omitempty"`
	RegisteredAt time.Time `json:"registeredAt"`
	LastSeenAt   time.Time `json:"lastSeenAt"`
}

// Registration là yêu cầu đăng ký thiết bị.
type Registration struct {
	Platform   string
	PushToken  string
	WebPushRaw string // JSON khoá Web Push, mã hoá cùng mã thiết bị
	DeviceName string
	AppVersion *string
	Locale     *string
}

const deviceCols = `id::text, platform, device_name, app_version, registered_at, last_seen_at`

func scanDevice(r pgx.Row) (Device, error) {
	var d Device
	err := r.Scan(&d.ID, &d.Platform, &d.DeviceName, &d.AppVersion, &d.RegisteredAt, &d.LastSeenAt)
	return d, err
}

// Register đăng ký hoặc làm mới thiết bị. Cùng mã của chính người dùng thì làm mới dòng cũ; mã đang thuộc người
// khác thì thu hồi dòng cũ và tạo dòng mới cho người gọi.
func (s Store) Register(ctx context.Context, tenantID, userID string, r Registration) (Device, error) {
	bidx, err := s.Field.BlindIndex(tenantID, tokenColumn, r.PushToken)
	if err != nil {
		return Device{}, err
	}
	var out Device
	err = s.inTenant(ctx, tenantID, func(tx pgx.Tx) error {
		var curID, curUser string
		err := tx.QueryRow(ctx, `SELECT id::text, user_id::text FROM notify.push_device
			WHERE tenant_id = $1 AND token_bidx = $2 AND revoked_at IS NULL FOR UPDATE`, tenantID, bidx).Scan(&curID, &curUser)
		switch {
		case err == nil && curUser == userID:
			out, err = scanDevice(tx.QueryRow(ctx, `UPDATE notify.push_device
				SET device_name = $2, app_version = $3, locale = coalesce($4, locale), last_seen_at = now()
				WHERE id = $1 RETURNING `+deviceCols, curID, r.DeviceName, r.AppVersion, r.Locale))
			return err
		case err == nil:
			if _, err := tx.Exec(ctx, `UPDATE notify.push_device SET revoked_at = now() WHERE id = $1`, curID); err != nil {
				return err
			}
		case !errors.Is(err, pgx.ErrNoRows):
			return err
		}
		id := uuid.Must(uuid.NewV7()).String()
		secret := r.PushToken
		if r.WebPushRaw != "" {
			secret = r.PushToken + "\n" + r.WebPushRaw
		}
		ct, dek, err := s.Field.Encrypt(tenantID, crypt.PurposeIdentity, tokenColumn, id, []byte(secret))
		if err != nil {
			return err
		}
		out, err = scanDevice(tx.QueryRow(ctx, `INSERT INTO notify.push_device
			(id, tenant_id, user_id, platform, device_name, app_version, locale, token_enc, dek_id, token_bidx)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING `+deviceCols,
			id, tenantID, userID, r.Platform, r.DeviceName, r.AppVersion, r.Locale, ct, dek.String(), bidx))
		return err
	})
	return out, err
}

// ListMine trả thiết bị chưa thu hồi của người dùng.
func (s Store) ListMine(ctx context.Context, tenantID, userID string) ([]Device, error) {
	out := []Device{}
	err := s.inTenant(ctx, tenantID, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, `SELECT `+deviceCols+` FROM notify.push_device
			WHERE tenant_id = $1 AND user_id = $2 AND revoked_at IS NULL ORDER BY registered_at DESC`, tenantID, userID)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			d, err := scanDevice(rows)
			if err != nil {
				return err
			}
			out = append(out, d)
		}
		return rows.Err()
	})
	return out, err
}

// Unregister thu hồi thiết bị của chính người dùng; thiết bị của người khác hoặc đã thu hồi trả ErrNotFound.
func (s Store) Unregister(ctx context.Context, tenantID, userID, deviceID string) error {
	return s.inTenant(ctx, tenantID, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `UPDATE notify.push_device SET revoked_at = now()
			WHERE id = $1 AND tenant_id = $2 AND user_id = $3 AND revoked_at IS NULL`, deviceID, tenantID, userID)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return ErrNotFound
		}
		return nil
	})
}

// RevokeDevice thu hồi thiết bị khi FCM, APNs báo mã không còn hợp lệ (DV-12-03 gọi).
func (s Store) RevokeDevice(ctx context.Context, tenantID, deviceID string) error {
	return s.inTenant(ctx, tenantID, func(tx pgx.Tx) error {
		_, err := tx.Exec(ctx, `UPDATE notify.push_device SET revoked_at = now() WHERE id = $1 AND revoked_at IS NULL`, deviceID)
		return err
	})
}

// RevokeStale thu hồi thiết bị không thấy quá StaleDeviceDays ngày ở mọi tenant.
func RevokeStale(ctx context.Context, pool *pgxpool.Pool) (int, error) {
	var n int
	err := pool.QueryRow(ctx, `SELECT notify.fn_revoke_stale_devices($1)`, StaleDeviceDays).Scan(&n)
	return n, err
}

// PushTarget là một thiết bị nhận đẩy đã giải mã, chỉ dùng trong bộ nhớ của worker gửi.
type PushTarget struct {
	DeviceID string
	Platform string
	Token    string
	WebPush  string
}

// String che mã thiết bị khi bị in nhầm vào nhật ký.
func (t PushTarget) String() string {
	return "PushTarget{" + t.DeviceID + " " + t.Platform + " [REDACTED:C2]}"
}

// GoString che mã thiết bị với định dạng %#v.
func (t PushTarget) GoString() string { return t.String() }

// CategoryPref là kênh của một nhóm.
type CategoryPref struct {
	Category string   `json:"category"`
	Channels []string `json:"channels"`
}

// Preferences là tuỳ chọn kênh theo lược đồ NotificationPreferences.
type Preferences struct {
	Categories        []CategoryPref `json:"categories"`
	QuietHoursEnabled bool           `json:"quietHoursEnabled"`
	QuietHoursStart   *string        `json:"quietHoursStart"`
	QuietHoursEnd     *string        `json:"quietHoursEnd"`
	RowVersion        int64          `json:"rowVersion"`
	UpdatedAt         time.Time      `json:"updatedAt"`
}

func load(ctx context.Context, tx pgx.Tx, tenantID, userID string) (*Preferences, error) {
	p := &Preferences{RowVersion: 0, UpdatedAt: time.Unix(0, 0).UTC()}
	err := tx.QueryRow(ctx, `SELECT quiet_hours_enabled, row_version, updated_at FROM notify.channel_pref_meta
		WHERE tenant_id = $1 AND user_id = $2`, tenantID, userID).Scan(&p.QuietHoursEnabled, &p.RowVersion, &p.UpdatedAt)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}
	rows, err := tx.Query(ctx, `SELECT category, channels, to_char(quiet_from, 'HH24:MI'), to_char(quiet_to, 'HH24:MI')
		FROM notify.channel_pref WHERE tenant_id = $1 AND user_id = $2`, tenantID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	have := map[string][]string{}
	for rows.Next() {
		var cat string
		var chs []string
		var qf, qt *string
		if err := rows.Scan(&cat, &chs, &qf, &qt); err != nil {
			return nil, err
		}
		have[cat] = chs
		if qf != nil && p.QuietHoursStart == nil {
			p.QuietHoursStart, p.QuietHoursEnd = qf, qt
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, c := range Categories {
		chs, ok := have[c]
		if !ok {
			chs = DefaultChannels
		}
		api := make([]string, 0, len(chs))
		for _, ch := range chs {
			api = append(api, dbToAPI(ch))
		}
		p.Categories = append(p.Categories, CategoryPref{Category: c, Channels: api})
	}
	return p, nil
}

// Get trả tuỳ chọn của người dùng; nhóm chưa chọn lấy kênh mặc định, phiên bản 0 khi chưa lưu lần nào.
func (s Store) Get(ctx context.Context, tenantID, userID string) (*Preferences, error) {
	var p *Preferences
	err := s.inTenant(ctx, tenantID, func(tx pgx.Tx) error {
		var err error
		p, err = load(ctx, tx, tenantID, userID)
		return err
	})
	return p, err
}

// Input là yêu cầu cập nhật theo NotificationPreferencesInput.
type Input struct {
	Categories        []CategoryPref `json:"categories"`
	QuietHoursEnabled *bool          `json:"quietHoursEnabled"`
	QuietHoursStart   *string        `json:"quietHoursStart"`
	QuietHoursEnd     *string        `json:"quietHoursEnd"`
}

func parseHHMM(s *string) (*time.Time, error) {
	if s == nil {
		return nil, nil
	}
	t, err := time.Parse("15:04", *s)
	if err != nil {
		return nil, err
	}
	return &t, nil
}

// Validate kiểm yêu cầu; trả mô tả lỗi cho người dùng.
func (in Input) Validate() (field, msg string, ok bool) {
	if in.QuietHoursEnabled == nil {
		return "quietHoursEnabled", "Thiếu lựa chọn giờ yên lặng.", false
	}
	known := map[string]bool{}
	for _, c := range Categories {
		known[c] = true
	}
	seen := map[string]bool{}
	for _, c := range in.Categories {
		if !known[c.Category] || seen[c.Category] {
			return "categories", "Nhóm thông báo không hợp lệ hoặc bị lặp.", false
		}
		seen[c.Category] = true
		inApp := false
		for _, ch := range c.Channels {
			switch ch {
			case "IN_APP":
				inApp = true
			case "PUSH", "EMAIL", "SMS":
			default:
				return "categories", "Kênh nhận thông báo không hợp lệ.", false
			}
		}
		if MandatoryInApp[c.Category] && !inApp {
			return "categories", "Không thể tắt thông báo trong ứng dụng của nhóm thông báo bắt buộc.", false
		}
	}
	qs, err1 := parseHHMM(in.QuietHoursStart)
	qe, err2 := parseHHMM(in.QuietHoursEnd)
	if err1 != nil || err2 != nil || (qs == nil) != (qe == nil) {
		return "quietHoursStart", "Giờ yên lặng phải có cả giờ bắt đầu và giờ kết thúc dạng giờ:phút.", false
	}
	if *in.QuietHoursEnabled && qs == nil {
		return "quietHoursStart", "Bật giờ yên lặng cần chọn giờ bắt đầu và giờ kết thúc.", false
	}
	if qs != nil && qs.Equal(*qe) {
		return "quietHoursEnd", "Giờ kết thúc phải khác giờ bắt đầu.", false
	}
	return "", "", true
}

// Update lưu tuỳ chọn nếu phiên bản khớp ifMatch; trả ErrVersionConflict khi lệch.
func (s Store) Update(ctx context.Context, tenantID, userID string, ifMatch int64, in Input) (*Preferences, error) {
	if _, _, ok := in.Validate(); !ok {
		return nil, ErrInvalid
	}
	qs, qe := in.QuietHoursStart, in.QuietHoursEnd
	var p *Preferences
	err := s.inTenant(ctx, tenantID, func(tx pgx.Tx) error {
		var cur int64
		err := tx.QueryRow(ctx, `SELECT row_version FROM notify.channel_pref_meta
			WHERE tenant_id = $1 AND user_id = $2 FOR UPDATE`, tenantID, userID).Scan(&cur)
		if errors.Is(err, pgx.ErrNoRows) {
			cur = 0
		} else if err != nil {
			return err
		}
		if cur != ifMatch {
			return ErrVersionConflict
		}
		if _, err := tx.Exec(ctx, `INSERT INTO notify.channel_pref_meta (tenant_id, user_id, quiet_hours_enabled, row_version, updated_at)
			VALUES ($1, $2, $3, 1, now())
			ON CONFLICT (tenant_id, user_id) DO UPDATE SET quiet_hours_enabled = EXCLUDED.quiet_hours_enabled,
			  row_version = notify.channel_pref_meta.row_version + 1, updated_at = now()`,
			tenantID, userID, *in.QuietHoursEnabled); err != nil {
			return err
		}
		given := map[string][]string{}
		for _, c := range in.Categories {
			chs := []string{}
			for _, ch := range c.Channels {
				chs = append(chs, apiToDB(ch))
			}
			sort.Strings(chs)
			given[c.Category] = chs
		}
		for _, c := range Categories {
			chs, ok := given[c]
			if !ok {
				// Nhóm không gửi giữ lựa chọn cũ, chỉ cập nhật giờ yên lặng.
				if _, err := tx.Exec(ctx, `UPDATE notify.channel_pref SET quiet_from = $3::time, quiet_to = $4::time
					WHERE tenant_id = $1 AND user_id = $2`, tenantID, userID, qs, qe); err != nil {
					return err
				}
				continue
			}
			if _, err := tx.Exec(ctx, `INSERT INTO notify.channel_pref (tenant_id, user_id, category, channels, quiet_from, quiet_to)
				VALUES ($1, $2, $3, $4, $5::time, $6::time)
				ON CONFLICT (tenant_id, user_id, category) DO UPDATE SET channels = EXCLUDED.channels,
				  quiet_from = EXCLUDED.quiet_from, quiet_to = EXCLUDED.quiet_to`,
				tenantID, userID, c, chs, qs, qe); err != nil {
				return err
			}
		}
		p, err = load(ctx, tx, tenantID, userID)
		return err
	})
	return p, err
}

// Route trả các kênh sẽ gửi cho một thông báo của nhóm category tại thời điểm now (giờ địa phương của người
// nhận): kênh trong ứng dụng luôn ghi; kênh ngoài chỉ khi được bật và không nằm trong giờ yên lặng (giờ yên lặng
// hoãn kênh ngoài, DV-12-03 gửi lại sau). Thông báo ưu tiên CAO vẫn đi trong giờ yên lặng (suy luận).
func Route(p *Preferences, category, priority string, now time.Time) []string {
	out := []string{"INAPP"}
	var chs []string
	for _, c := range p.Categories {
		if c.Category == category {
			chs = c.Channels
		}
	}
	if chs == nil {
		chs = []string{"IN_APP", "PUSH"}
	}
	quiet := p.QuietHoursEnabled && p.QuietHoursStart != nil && inWindow(*p.QuietHoursStart, *p.QuietHoursEnd, now)
	for _, ch := range chs {
		db := apiToDB(ch)
		if db == "INAPP" || (quiet && priority != "CAO") {
			continue
		}
		out = append(out, db)
	}
	return out
}

func inWindow(from, to string, now time.Time) bool {
	cur := now.Format("15:04")
	if from < to {
		return cur >= from && cur < to
	}
	return cur >= from || cur < to // qua nửa đêm
}

// RoutePlan là việc gửi của một người nhận.
type RoutePlan struct {
	Channels []string
	Devices  []PushTarget
}

// Plan đọc tuỳ chọn và thiết bị của người nhận trong giao dịch tenant, giải mã mã thiết bị trong bộ nhớ.
func (s Store) Plan(ctx context.Context, tenantID, userID, templateCode, priority string, now time.Time) (*RoutePlan, error) {
	var rp RoutePlan
	err := s.inTenant(ctx, tenantID, func(tx pgx.Tx) error {
		p, err := load(ctx, tx, tenantID, userID)
		if err != nil {
			return err
		}
		rp.Channels = Route(p, CategoryOf(templateCode), priority, now)
		push := false
		for _, c := range rp.Channels {
			push = push || c == "PUSH"
		}
		if !push {
			return nil
		}
		rows, err := tx.Query(ctx, `SELECT id::text, platform, token_enc FROM notify.push_device
			WHERE tenant_id = $1 AND user_id = $2 AND revoked_at IS NULL`, tenantID, userID)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var t PushTarget
			var ct []byte
			if err := rows.Scan(&t.DeviceID, &t.Platform, &ct); err != nil {
				return err
			}
			pt, err := s.Field.Decrypt(tenantID, crypt.PurposeIdentity, tokenColumn, t.DeviceID, ct)
			if err != nil {
				return fmt.Errorf("prefs: thiết bị %s: %w", t.DeviceID, err)
			}
			tok, wp, _ := strings.Cut(string(pt), "\n")
			t.Token, t.WebPush = tok, wp
			rp.Devices = append(rp.Devices, t)
		}
		return rows.Err()
	})
	return &rp, err
}
