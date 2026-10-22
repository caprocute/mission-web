package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/google/uuid"

	"mission/services-go/notification/internal/prefs"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/problem"
)

// Prefs là phần thiết bị và tuỳ chọn kênh mà API cần (DV-12-05).
type Prefs interface {
	Register(ctx context.Context, tenantID, userID string, r prefs.Registration) (prefs.Device, error)
	ListMine(ctx context.Context, tenantID, userID string) ([]prefs.Device, error)
	Unregister(ctx context.Context, tenantID, userID, deviceID string) error
	Get(ctx context.Context, tenantID, userID string) (*prefs.Preferences, error)
	Update(ctx context.Context, tenantID, userID string, ifMatch int64, in prefs.Input) (*prefs.Preferences, error)
}

func (d Deps) routePrefs(m *http.ServeMux) {
	m.HandleFunc("GET "+Base+"/notifications/devices", d.listDevices)
	m.HandleFunc("POST "+Base+"/notifications/devices", d.registerDevice)
	m.HandleFunc("DELETE "+Base+"/notifications/devices/{deviceId}", d.unregisterDevice)
	m.HandleFunc("GET "+Base+"/notifications/preferences", d.getPrefs)
	m.HandleFunc("PUT "+Base+"/notifications/preferences", d.putPrefs)
}

func (d Deps) prefsReady(w http.ResponseWriter, r *http.Request) bool {
	if d.Prefs == nil {
		// Chưa cấu hình khoá mã hoá trường C2 thì không nhận thiết bị.
		p := problem.InternalServerError(r.URL.Path, otel.TraceIDFromContext(r.Context()))
		p.Status = http.StatusServiceUnavailable
		problem.Write(w, p)
		return false
	}
	return true
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	trace := otel.TraceIDFromContext(r.Context())
	if ct := strings.TrimSpace(strings.Split(r.Header.Get("Content-Type"), ";")[0]); ct != "application/json" {
		problem.Write(w, problem.UnsupportedMediaType("Yêu cầu phải gửi dạng JSON.", r.URL.Path, trace))
		return false
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 64<<10))
	if err != nil {
		problem.Write(w, problem.BadRequest("Không đọc được yêu cầu.", nil, r.URL.Path, trace))
		return false
	}
	dec := json.NewDecoder(strings.NewReader(string(body)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		problem.Write(w, problem.BadRequest("Yêu cầu không đúng định dạng.", nil, r.URL.Path, trace))
		return false
	}
	return true
}

func invalid(w http.ResponseWriter, r *http.Request, field, msg string) {
	problem.Write(w, problem.ValidationError([]problem.FieldErrorItem{{Field: field, Message: msg}},
		r.URL.Path, otel.TraceIDFromContext(r.Context())))
}

func (d Deps) listDevices(w http.ResponseWriter, r *http.Request) {
	tenantID, userID, ok := caller(w, r)
	if !ok || !d.prefsReady(w, r) {
		return
	}
	items, err := d.Prefs.ListMine(r.Context(), tenantID, userID)
	if err != nil {
		d.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

type registerRequest struct {
	Platform    string          `json:"platform"`
	PushToken   string          `json:"pushToken"`
	DeviceName  string          `json:"deviceName"`
	AppVersion  *string         `json:"appVersion"`
	WebPushKeys json.RawMessage `json:"webPushKeys"`
}

// registerDevice: đăng ký lại cùng mã là làm mới nên Idempotency-Key không cần lưu.
func (d Deps) registerDevice(w http.ResponseWriter, r *http.Request) {
	tenantID, userID, ok := caller(w, r)
	if !ok || !d.prefsReady(w, r) {
		return
	}
	var req registerRequest
	if !readJSON(w, r, &req) {
		return
	}
	switch {
	case req.Platform != "FCM" && req.Platform != "APNS" && req.Platform != "WEB_PUSH":
		invalid(w, r, "platform", "Nền tảng nhận thông báo đẩy không hợp lệ.")
		return
	case len(req.PushToken) < 16 || len(req.PushToken) > 4096:
		invalid(w, r, "pushToken", "Mã thiết bị không hợp lệ.")
		return
	case strings.TrimSpace(req.DeviceName) == "" || len([]rune(req.DeviceName)) > 120:
		invalid(w, r, "deviceName", "Tên thiết bị phải có từ 1 đến 120 ký tự.")
		return
	case req.AppVersion != nil && len(*req.AppVersion) > 32:
		invalid(w, r, "appVersion", "Phiên bản ứng dụng tối đa 32 ký tự.")
		return
	}
	wp := ""
	if len(req.WebPushKeys) > 0 && string(req.WebPushKeys) != "null" {
		wp = string(req.WebPushKeys)
	}
	var locale *string
	if l := strings.TrimSpace(strings.Split(r.Header.Get("Accept-Language"), ",")[0]); l != "" && len(l) <= 8 {
		locale = &l
	}
	dev, err := d.Prefs.Register(r.Context(), tenantID, userID, prefs.Registration{Platform: req.Platform,
		PushToken: req.PushToken, WebPushRaw: wp, DeviceName: strings.TrimSpace(req.DeviceName),
		AppVersion: req.AppVersion, Locale: locale})
	if err != nil {
		d.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, dev)
}

func (d Deps) unregisterDevice(w http.ResponseWriter, r *http.Request) {
	tenantID, userID, ok := caller(w, r)
	if !ok || !d.prefsReady(w, r) {
		return
	}
	trace := otel.TraceIDFromContext(r.Context())
	id, err := uuid.Parse(r.PathValue("deviceId"))
	if err != nil {
		problem.Write(w, problem.NotFound("Không tìm thấy thiết bị.", r.URL.Path, trace))
		return
	}
	err = d.Prefs.Unregister(r.Context(), tenantID, userID, id.String())
	if errors.Is(err, prefs.ErrNotFound) {
		problem.Write(w, problem.NotFound("Không tìm thấy thiết bị.", r.URL.Path, trace))
		return
	}
	if err != nil {
		d.internal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func etag(v int64) string { return `"` + strconv.FormatInt(v, 10) + `"` }

func (d Deps) getPrefs(w http.ResponseWriter, r *http.Request) {
	tenantID, userID, ok := caller(w, r)
	if !ok || !d.prefsReady(w, r) {
		return
	}
	p, err := d.Prefs.Get(r.Context(), tenantID, userID)
	if err != nil {
		d.internal(w, r, err)
		return
	}
	w.Header().Set("ETag", etag(p.RowVersion))
	writeJSON(w, http.StatusOK, p)
}

func (d Deps) putPrefs(w http.ResponseWriter, r *http.Request) {
	tenantID, userID, ok := caller(w, r)
	if !ok || !d.prefsReady(w, r) {
		return
	}
	trace := otel.TraceIDFromContext(r.Context())
	im := strings.TrimSpace(r.Header.Get("If-Match"))
	if im == "" {
		problem.Write(w, problem.PreconditionRequired("Thiếu phiên bản của tuỳ chọn, đề nghị tải lại trang.", r.URL.Path, trace))
		return
	}
	ver, err := strconv.ParseInt(strings.Trim(strings.TrimPrefix(im, "W/"), `"`), 10, 64)
	if err != nil {
		problem.Write(w, problem.PreconditionRequired("Phiên bản của tuỳ chọn không hợp lệ, đề nghị tải lại trang.", r.URL.Path, trace))
		return
	}
	var in prefs.Input
	if !readJSON(w, r, &in) {
		return
	}
	if in.Categories == nil {
		invalid(w, r, "categories", "Thiếu danh sách nhóm thông báo.")
		return
	}
	if field, msg, ok := in.Validate(); !ok {
		invalid(w, r, field, msg)
		return
	}
	p, err := d.Prefs.Update(r.Context(), tenantID, userID, ver, in)
	if errors.Is(err, prefs.ErrVersionConflict) {
		pd := problem.ConcurrencyConflict("Tuỳ chọn đã được thay đổi ở nơi khác, đề nghị tải lại trước khi lưu.", r.URL.Path, trace)
		pd.Code = "NOTIFY-409-VERSION-CONFLICT"
		problem.Write(w, pd)
		return
	}
	if err != nil {
		d.internal(w, r, err)
		return
	}
	w.Header().Set("ETag", etag(p.RowVersion))
	writeJSON(w, http.StatusOK, p)
}
