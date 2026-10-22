package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"mission/services-go/notification/internal/rt"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/problem"
)

// PingInterval là chu kỳ dòng chú thích `: ping` giữ kết nối qua proxy (platform.yaml).
var PingInterval = 25 * time.Second

// Stream là phần thời gian thực mà API cần (DV-12-02).
type Stream interface {
	Subscribe(ctx context.Context, tenantID, userID string) (*rt.Sub, error)
	Unsubscribe(s *rt.Sub)
	Replay(ctx context.Context, tenantID, userID, after string) ([]rt.Event, bool, error)
}

func writeEvent(w http.ResponseWriter, ev rt.Event) {
	// data là JSON một dòng; bỏ xuống dòng phòng khi bên phát gửi sai để không vỡ khung SSE.
	data := strings.NewReplacer("\r", "", "\n", "").Replace(ev.Data)
	if ev.ID != "" {
		fmt.Fprintf(w, "id: %s\n", ev.ID)
	}
	fmt.Fprintf(w, "event: %s\ndata: %s\n\n", ev.Name, data)
}

// streamNotifications mở luồng SSE: đăng ký vào hub trước, phát lại phần sau Last-Event-ID, rồi chuyển tiếp sự
// kiện mới và bỏ sự kiện đã gửi trong phần phát lại.
func (d Deps) stream(w http.ResponseWriter, r *http.Request) {
	tenantID, userID, ok := caller(w, r)
	if !ok {
		return
	}
	trace := otel.TraceIDFromContext(r.Context())
	if d.Stream == nil {
		p := problem.InternalServerError(r.URL.Path, trace)
		p.Status = http.StatusServiceUnavailable
		problem.Write(w, p)
		return
	}
	fl, canFlush := w.(http.Flusher)
	if !canFlush {
		d.internal(w, r, errors.New("api: ResponseWriter không hỗ trợ Flush"))
		return
	}
	sub, err := d.Stream.Subscribe(r.Context(), tenantID, userID)
	switch {
	case errors.Is(err, rt.ErrTooManyConnections):
		p := problem.BadRequest("Đồng chí đang mở quá nhiều phiên nhận thông báo, đề nghị đóng bớt cửa sổ.", nil, r.URL.Path, trace)
		p.Status, p.Code = http.StatusTooManyRequests, "NOTIFY-429-TOO-MANY-STREAMS"
		w.Header().Set("Retry-After", "30")
		problem.Write(w, p)
		return
	case errors.Is(err, rt.ErrDraining):
		p := problem.InternalServerError(r.URL.Path, trace)
		p.Status = http.StatusServiceUnavailable
		w.Header().Set("Retry-After", "1")
		problem.Write(w, p)
		return
	case err != nil:
		d.internal(w, r, err)
		return
	}
	defer d.Stream.Unsubscribe(sub)
	// Kết nối dài không chịu WriteTimeout chung của máy chủ.
	_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})

	h := w.Header()
	h.Set("Content-Type", "text/event-stream; charset=utf-8")
	h.Set("Cache-Control", "no-cache")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	fmt.Fprint(w, "retry: 5000\n\n")

	lastSent := ""
	if after := strings.TrimSpace(r.Header.Get("Last-Event-ID")); after != "" {
		if !rt.ValidID(after) {
			b, _ := json.Marshal(map[string]any{"unreadCount": 0, "reload": true})
			writeEvent(w, rt.Event{Name: rt.EventUnread, Data: string(b)})
		} else {
			events, gap, err := d.Stream.Replay(r.Context(), tenantID, userID, after)
			if err != nil {
				d.Log.Error("phát lại thông báo thất bại", "err", err.Error())
				return
			}
			if gap {
				b, _ := json.Marshal(map[string]any{"unreadCount": 0, "reload": true})
				writeEvent(w, rt.Event{Name: rt.EventUnread, Data: string(b)})
			}
			lastSent = after
			for _, ev := range events {
				writeEvent(w, ev)
				lastSent = ev.ID
			}
		}
	}
	fl.Flush()

	ping := time.NewTicker(PingInterval)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-sub.Done():
			return
		case <-ping.C:
			fmt.Fprint(w, ": ping\n\n")
			fl.Flush()
		case ev := <-sub.C:
			if lastSent != "" && !rt.Less(lastSent, ev.ID) {
				continue
			}
			writeEvent(w, ev)
			lastSent = ev.ID
			// Gom các sự kiện đang chờ rồi mới flush một lần.
			for more := true; more; {
				select {
				case ev := <-sub.C:
					if lastSent == "" || rt.Less(lastSent, ev.ID) {
						writeEvent(w, ev)
						lastSent = ev.ID
					}
				default:
					more = false
				}
			}
			fl.Flush()
		}
	}
}
