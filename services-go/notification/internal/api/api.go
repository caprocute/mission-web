// Package api hiện thực listNotifications và markNotificationsRead của contracts/openapi/modules/platform.yaml
// (QT-00 → SCR-0002, SVC-12) cùng probe và metric vận hành. Token đã được cổng API kiểm chữ ký; dịch vụ lấy
// tenant_id và sub từ claim (vtplatform/tenant, NEN-010). Người gọi chỉ thấy thông báo của chính mình.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"mission/services-go/notification/internal/store"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/problem"
	"vn.viettel.okrkpi/services-go/vtplatform/tenant"
)

// Base là tiền tố đường dẫn theo quy ước API mục 13 baseline.
const Base = "/api/v1"

// MaxLimit là cỡ trang tối đa (baseline mục 13).
const MaxLimit = 200

// DefaultLimit là cỡ trang mặc định (suy luận theo common.yaml Limit).
const DefaultLimit = 20

// Inbox là phần đọc ghi hộp thông báo mà API cần.
type Inbox interface {
	List(ctx context.Context, tenantID, userID string, unreadOnly bool, limit int, cursor string) (*store.Page, error)
	MarkRead(ctx context.Context, tenantID, userID string, ids []string, all bool) (int, int, error)
}

// Deps là phụ thuộc của máy chủ.
type Deps struct {
	Inbox     Inbox
	Prefs     Prefs // nil khi chưa cấu hình khoá mã hoá trường C2
	Ready     func(ctx context.Context) error
	Log       *slog.Logger
	OTel      *otel.Provider
	Verifier  func(ctx context.Context, rawToken string) (map[string]any, error)
	MetricSet []string
}

// Handler dựng bộ định tuyến HTTP.
func Handler(d Deps) http.Handler {
	if d.Log == nil {
		d.Log = slog.Default()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		if d.Ready != nil {
			ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
			defer cancel()
			if err := d.Ready(ctx); err != nil {
				w.WriteHeader(http.StatusServiceUnavailable)
				return
			}
		}
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("GET /metrics", func(w http.ResponseWriter, _ *http.Request) {
		out := map[string]int64{}
		if d.OTel != nil {
			names := append([]string{}, d.MetricSet...)
			sort.Strings(names)
			for _, n := range names {
				out[n] = d.OTel.CounterValue(n)
			}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
	})

	authed := http.NewServeMux()
	authed.HandleFunc("GET "+Base+"/notifications", d.list)
	authed.HandleFunc("POST "+Base+"/notifications/read", d.markRead)
	d.routePrefs(authed)
	mw := tenant.Middleware(tenant.MiddlewareConfig{TokenVerifier: d.Verifier})
	mux.Handle(Base+"/", mw(authed))
	if d.OTel != nil {
		return d.OTel.HTTPMiddleware(mux)
	}
	return mux
}

func caller(w http.ResponseWriter, r *http.Request) (tenantID, userID string, ok bool) {
	tenantID, _ = tenant.FromContext(r.Context())
	sub, _ := tenant.ActorFromContext(r.Context())
	u, err := uuid.Parse(sub)
	if err != nil {
		problem.Write(w, problem.Unauthorized("Phiên đăng nhập không hợp lệ, đề nghị đồng chí đăng nhập lại.",
			r.URL.Path, otel.TraceIDFromContext(r.Context())))
		return "", "", false
	}
	return tenantID, u.String(), true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func (d Deps) internal(w http.ResponseWriter, r *http.Request, err error) {
	d.Log.Error("lỗi xử lý yêu cầu", "path", r.URL.Path, "err", err.Error())
	problem.Write(w, problem.InternalServerError(r.URL.Path, otel.TraceIDFromContext(r.Context())))
}

func (d Deps) list(w http.ResponseWriter, r *http.Request) {
	tenantID, userID, ok := caller(w, r)
	if !ok {
		return
	}
	q := r.URL.Query()
	trace := otel.TraceIDFromContext(r.Context())
	limit := DefaultLimit
	if s := q.Get("limit"); s != "" {
		n, err := strconv.Atoi(s)
		if err != nil || n < 1 || n > MaxLimit {
			problem.Write(w, problem.BadRequest("Số dòng mỗi trang phải từ 1 đến 200.",
				[]problem.FieldErrorItem{{Field: "limit", Message: "Giá trị phải từ 1 đến 200."}}, r.URL.Path, trace))
			return
		}
		limit = n
	}
	unread := false
	if s := q.Get("unread"); s != "" {
		b, err := strconv.ParseBool(s)
		if err != nil {
			problem.Write(w, problem.BadRequest("Tham số lọc chưa đọc không hợp lệ.",
				[]problem.FieldErrorItem{{Field: "unread", Message: "Giá trị phải là true hoặc false."}}, r.URL.Path, trace))
			return
		}
		unread = b
	}
	page, err := d.Inbox.List(r.Context(), tenantID, userID, unread, limit, q.Get("cursor"))
	if errors.Is(err, store.ErrInvalidCursor) {
		problem.Write(w, problem.BadRequest("Con trỏ phân trang không hợp lệ.",
			[]problem.FieldErrorItem{{Field: "cursor", Message: "Con trỏ không hợp lệ hoặc đã hết hạn."}}, r.URL.Path, trace))
		return
	}
	if err != nil {
		d.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, page)
}

type readRequest struct {
	IDs []string `json:"ids"`
	All *bool    `json:"all"`
}

// markRead lặp lại không đổi kết quả nên Idempotency-Key được nhận nhưng không cần lưu (platform.yaml).
func (d Deps) markRead(w http.ResponseWriter, r *http.Request) {
	tenantID, userID, ok := caller(w, r)
	if !ok {
		return
	}
	trace := otel.TraceIDFromContext(r.Context())
	if ct := strings.TrimSpace(strings.Split(r.Header.Get("Content-Type"), ";")[0]); ct != "application/json" {
		problem.Write(w, problem.UnsupportedMediaType("Yêu cầu phải gửi dạng JSON.", r.URL.Path, trace))
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 64<<10))
	if err != nil {
		problem.Write(w, problem.BadRequest("Không đọc được yêu cầu.", nil, r.URL.Path, trace))
		return
	}
	var req readRequest
	dec := json.NewDecoder(strings.NewReader(string(body)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil {
		problem.Write(w, problem.BadRequest("Yêu cầu không đúng định dạng.", nil, r.URL.Path, trace))
		return
	}
	all := req.All != nil && *req.All
	hasIDs := len(req.IDs) > 0
	if all == hasIDs {
		problem.Write(w, problem.ValidationError([]problem.FieldErrorItem{{Field: "ids",
			Message: "Chọn thông báo cần đánh dấu hoặc chọn đánh dấu tất cả."}}, r.URL.Path, trace))
		return
	}
	if len(req.IDs) > MaxLimit {
		problem.Write(w, problem.ValidationError([]problem.FieldErrorItem{{Field: "ids",
			Message: "Mỗi lần đánh dấu tối đa 200 thông báo."}}, r.URL.Path, trace))
		return
	}
	ids := make([]string, 0, len(req.IDs))
	for _, s := range req.IDs {
		u, err := uuid.Parse(s)
		if err != nil {
			problem.Write(w, problem.ValidationError([]problem.FieldErrorItem{{Field: "ids",
				Message: "Mã thông báo không hợp lệ."}}, r.URL.Path, trace))
			return
		}
		ids = append(ids, u.String())
	}
	updated, unread, err := d.Inbox.MarkRead(r.Context(), tenantID, userID, ids, all)
	if err != nil {
		d.internal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int{"updated": updated, "unreadCount": unread})
}
