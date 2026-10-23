// Package controller nối máy trạng thái mức suy giảm với actuator, thông báo kênh trực, metric và API HTTP
// (INF-25). API giữ tương thích với bộ điều khiển Python của DV-01-02 mà vtctl đang gọi: GET/POST /level,
// POST /alertmanager, /metrics, /healthz; POST /level nhận thêm ttl để ghim có thời hạn.
package controller

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"vn.viettel.okrkpi/services-go/degrade-controller/internal/actuate"
	"vn.viettel.okrkpi/services-go/degrade-controller/internal/engine"
)

// Notifier gửi thông báo lên kênh trực.
type Notifier interface {
	Notify(ctx context.Context, n Notice) error
}

// Notice là một thông báo chuyển mức hoặc nâng SEV-2.
type Notice struct {
	Event    string    `json:"event"` // degrade_level_changed | degrade_escalate_sev2
	Cell     string    `json:"cell"`
	Level    int       `json:"level"`
	Previous int       `json:"previous"`
	Source   string    `json:"source"`
	Reason   string    `json:"reason,omitempty"`
	Severity string    `json:"severity,omitempty"`
	Text     string    `json:"text"`
	At       time.Time `json:"at"`
}

// Options cấu hình Controller.
type Options struct {
	Cell        string
	Token       string
	AlertLevels map[string]int // alertname → mức, dùng khi cảnh báo không mang nhãn degrade_level
	Reconcile   time.Duration  // ghi lại mức định kỳ (cấu hình bị GitOps hay người khác ghi đè)
	Now         func() time.Time
	Log         *slog.Logger
}

// Controller an toàn cho truy cập đồng thời.
type Controller struct {
	mu           sync.Mutex
	eng          *engine.Engine
	acts         []actuate.Actuator
	notify       Notifier
	opt          Options
	lastApply    time.Time
	clusterLevel int
	transitions  int64
	errors       int64
	escalations  int64
}

// New dựng Controller.
func New(eng *engine.Engine, acts []actuate.Actuator, n Notifier, opt Options) *Controller {
	if opt.Now == nil {
		opt.Now = time.Now
	}
	if opt.Log == nil {
		opt.Log = slog.New(slog.NewJSONHandler(io.Discard, nil))
	}
	if opt.Reconcile <= 0 {
		opt.Reconcile = time.Minute
	}
	return &Controller{eng: eng, acts: acts, notify: n, opt: opt, clusterLevel: eng.Level()}
}

// ErrApply báo không ghi được mức ra cụm.
var ErrApply = errors.New("không ghi được cấu hình cổng")

// Start áp mức hiện hành lần đầu.
func (c *Controller) Start(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.apply(ctx, engine.Decision{Level: c.eng.Level(), Previous: c.eng.Level(), Source: "start"}, true)
}

// Tick chạy định kỳ: tính lại theo thời gian và ghi lại mức sau mỗi chu kỳ Reconcile.
func (c *Controller) Tick(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.opt.Now()
	d := c.eng.Tick(now)
	return c.apply(ctx, d, now.Sub(c.lastApply) >= c.opt.Reconcile)
}

// apply chạy khi giữ khoá. clusterLevel là mức đã ghi thành công ra cụm: lần ghi lỗi được thử lại ở Tick
// sau, và nhật ký, thông báo chuyển mức chỉ phát khi cụm thật sự đổi mức.
func (c *Controller) apply(ctx context.Context, d engine.Decision, force bool) error {
	now := c.opt.Now()
	if d.Level != c.clusterLevel || force {
		if _, err := actuate.ApplyAll(ctx, c.acts, c.clusterLevel, d.Level); err != nil {
			c.errors++
			c.opt.Log.Error("degrade_apply_failed", "level", d.Level, "error", err.Error())
			return fmt.Errorf("%w: %v", ErrApply, err)
		}
		c.lastApply = now
		if prev := c.clusterLevel; d.Level != prev {
			c.clusterLevel = d.Level
			c.transitions++
			c.opt.Log.Info("degrade_level_changed", "cell", c.opt.Cell, "previous", prev, "level", d.Level,
				"source", d.Source, "reason", d.Reason)
			c.send(ctx, Notice{Event: "degrade_level_changed", Level: d.Level, Previous: prev, Source: d.Source,
				Reason: d.Reason, Text: fmt.Sprintf("Cell %s chuyển mức suy giảm %d → %d (%s)", c.opt.Cell, prev, d.Level, d.Source)})
		}
	}
	if d.Escalate {
		c.escalations++
		c.opt.Log.Warn("degrade_escalate_sev2", "cell", c.opt.Cell, "level", d.Level)
		c.send(ctx, Notice{Event: "degrade_escalate_sev2", Level: d.Level, Previous: d.Level, Source: "escalation", Severity: "SEV-2",
			Text: fmt.Sprintf("Cell %s ở mức suy giảm %d quá 15 phút: nâng sự cố SEV-2 (OPS-13)", c.opt.Cell, d.Level)})
	}
	return nil
}

func (c *Controller) send(ctx context.Context, n Notice) {
	if c.notify == nil {
		return
	}
	n.Cell, n.At = c.opt.Cell, c.opt.Now().UTC()
	nctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	if err := c.notify.Notify(nctx, n); err != nil {
		c.errors++
		c.opt.Log.Error("degrade_notify_failed", "event", n.Event, "error", err.Error())
	}
}

type amPayload struct {
	GroupKey string `json:"groupKey"`
	Alerts   []struct {
		Status      string            `json:"status"`
		Labels      map[string]string `json:"labels"`
		Fingerprint string            `json:"fingerprint"`
	} `json:"alerts"`
}

func (c *Controller) mapAlerts(p amPayload) []engine.Alert {
	var out []engine.Alert
	for _, a := range p.Alerts {
		name := a.Labels["alertname"]
		level := -1
		if raw, ok := a.Labels["degrade_level"]; ok {
			if n, err := strconv.Atoi(raw); err == nil {
				level = n
			}
		} else if n, ok := c.opt.AlertLevels[name]; ok {
			level = n
		}
		if level < 0 || level > engine.MaxLevel {
			continue
		}
		key := a.Fingerprint
		if key == "" {
			raw, _ := json.Marshal(a.Labels)
			key = string(raw)
		}
		out = append(out, engine.Alert{Key: key, Name: name, Level: level, Firing: a.Status == "firing"})
	}
	return out
}

// Handler trả API HTTP.
func (c *Controller) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		reply(w, http.StatusOK, map[string]string{"status": "UP"})
	})
	mux.HandleFunc("GET /level", func(w http.ResponseWriter, _ *http.Request) {
		c.mu.Lock()
		s := c.eng.Snapshot(c.opt.Now())
		c.mu.Unlock()
		reply(w, http.StatusOK, s)
	})
	mux.HandleFunc("GET /metrics", c.metrics)
	mux.HandleFunc("POST /alertmanager", c.auth(func(w http.ResponseWriter, r *http.Request, body []byte) {
		var p amPayload
		if err := json.Unmarshal(body, &p); err != nil {
			problem(w, http.StatusBadRequest, "Thân không phải JSON")
			return
		}
		c.mu.Lock()
		defer c.mu.Unlock()
		d := c.eng.Alerts(c.opt.Now(), c.mapAlerts(p), p.GroupKey)
		c.finish(w, r.Context(), d)
	}))
	mux.HandleFunc("POST /level", c.auth(func(w http.ResponseWriter, r *http.Request, body []byte) {
		var p struct {
			Level  json.RawMessage `json:"level"`
			Reason string          `json:"reason"`
			TTL    string          `json:"ttl"`
		}
		if err := json.Unmarshal(body, &p); err != nil {
			problem(w, http.StatusBadRequest, "Thân không phải JSON")
			return
		}
		var level *int
		if s := strings.TrimSpace(string(p.Level)); s != "" && s != "null" {
			n, err := strconv.Atoi(s)
			if err != nil || n < 0 || n > engine.MaxLevel {
				problem(w, http.StatusUnprocessableEntity, "Mức suy giảm chỉ nhận 0, 1, 2, 3 hoặc null")
				return
			}
			level = &n
		}
		var ttl time.Duration
		if p.TTL != "" {
			d, err := time.ParseDuration(p.TTL)
			if err != nil || d <= 0 {
				problem(w, http.StatusUnprocessableEntity, "Thời hạn ghim không hợp lệ (ví dụ 30m, 2h)")
				return
			}
			ttl = d
		}
		reason := p.Reason
		if len(reason) > 200 {
			reason = reason[:200]
		}
		c.mu.Lock()
		defer c.mu.Unlock()
		d, err := c.eng.SetPin(c.opt.Now(), level, ttl, reason)
		if err != nil {
			problem(w, http.StatusUnprocessableEntity, "Thời hạn ghim vượt "+c.eng.MaxPinTTL().String())
			return
		}
		c.finish(w, r.Context(), d)
	}))
	return mux
}

// finish chạy khi giữ khoá.
func (c *Controller) finish(w http.ResponseWriter, ctx context.Context, d engine.Decision) {
	if err := c.apply(ctx, d, false); err != nil {
		problem(w, http.StatusBadGateway, "Không ghi được cấu hình cổng")
		return
	}
	reply(w, http.StatusOK, c.eng.Snapshot(c.opt.Now()))
}

func (c *Controller) auth(h func(http.ResponseWriter, *http.Request, []byte)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		want := "Bearer " + c.opt.Token
		got := r.Header.Get("Authorization")
		if c.opt.Token == "" || subtle.ConstantTimeCompare([]byte(got), []byte(want)) != 1 {
			problem(w, http.StatusUnauthorized, "Thiếu hoặc sai token")
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if err != nil {
			problem(w, http.StatusBadRequest, "Không đọc được thân")
			return
		}
		h(w, r, body)
	}
}

func (c *Controller) metrics(w http.ResponseWriter, _ *http.Request) {
	c.mu.Lock()
	s := c.eng.Snapshot(c.opt.Now())
	tr, er, es := c.transitions, c.errors, c.escalations
	c.mu.Unlock()
	pinned := 0
	if s.Pin != nil {
		pinned = 1
	}
	var b bytes.Buffer
	fmt.Fprintf(&b, "# HELP vt_degrade_level Mức suy giảm đang áp dụng (0..3).\n# TYPE vt_degrade_level gauge\nvt_degrade_level %d\n", s.Level)
	fmt.Fprintf(&b, "# HELP vt_degrade_target_level Mức mong muốn trước chống dao động.\n# TYPE vt_degrade_target_level gauge\nvt_degrade_target_level %d\n", s.Target)
	fmt.Fprintf(&b, "# HELP vt_degrade_pinned Có ghim tay hay không.\n# TYPE vt_degrade_pinned gauge\nvt_degrade_pinned %d\n", pinned)
	fmt.Fprintf(&b, "# HELP vt_degrade_level_since_seconds Thời điểm chuyển sang mức hiện hành.\n# TYPE vt_degrade_level_since_seconds gauge\nvt_degrade_level_since_seconds %d\n", s.Since.Unix())
	fmt.Fprintf(&b, "# TYPE vt_degrade_transitions_total counter\nvt_degrade_transitions_total %d\n", tr)
	fmt.Fprintf(&b, "# TYPE vt_degrade_escalations_total counter\nvt_degrade_escalations_total %d\n", es)
	fmt.Fprintf(&b, "# TYPE vt_degrade_errors_total counter\nvt_degrade_errors_total %d\n", er)
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	_, _ = w.Write(b.Bytes())
}

func reply(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func problem(w http.ResponseWriter, code int, title string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]any{"type": "about:blank", "title": title, "status": code})
}

// WebhookNotifier gửi Notice dạng JSON tới URL kênh trực (notification-service, cầu nối chat).
type WebhookNotifier struct {
	URL   string
	Token string
	HTTP  *http.Client
}

// Notify thực hiện Notifier.
func (n *WebhookNotifier) Notify(ctx context.Context, msg Notice) error {
	body, _ := json.Marshal(msg)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, n.URL, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if n.Token != "" {
		req.Header.Set("Authorization", "Bearer "+n.Token)
	}
	hc := n.HTTP
	if hc == nil {
		hc = &http.Client{Timeout: 5 * time.Second}
	}
	res, err := hc.Do(req)
	if err != nil {
		return err
	}
	res.Body.Close()
	if res.StatusCode/100 != 2 {
		return fmt.Errorf("kênh trực trả HTTP %d", res.StatusCode)
	}
	return nil
}
