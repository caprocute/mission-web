// Package engine là máy trạng thái mức suy giảm của cell (INF-25; TL-04 "Hành vi khi bão hoà", OPS-13, OPS-14).
//
// Mức mong muốn là mức ghim tay còn hạn nếu có, ngược lại là max(mức nền, mức của các cảnh báo đang cháy).
// Tăng mức áp dụng ngay; hạ mức chỉ khi mức mong muốn đã thấp hơn liên tục ít nhất HoldDown (chống dao động).
// Ghim tay bỏ qua chống dao động theo cả hai chiều và luôn có thời hạn. Mức từ 2 trở lên kéo dài quá
// EscalateAfter thì phát tín hiệu nâng SEV-2 đúng một lần cho mỗi đợt.
package engine

import (
	"errors"
	"sort"
	"time"
)

// MaxLevel là mức cao nhất (3 Bảo vệ).
const MaxLevel = 3

// Config là tham số của máy trạng thái.
type Config struct {
	Base          int           // mức nền khai trong Git
	HoldDown      time.Duration // thời gian điều kiện phải hết trước khi hạ mức (mặc định 10 phút, suy luận)
	DefaultPinTTL time.Duration // thời hạn ghim khi người trực không nêu
	MaxPinTTL     time.Duration // thời hạn ghim dài nhất
	StaleAfter    time.Duration // cảnh báo cháy không được gửi lại quá thời gian này thì coi như đã hết
	EscalateAfter time.Duration // mức ≥ 2 kéo dài quá thời gian này thì nâng SEV-2 (OPS-13: 15 phút)
}

// DefaultConfig trả tham số mặc định.
func DefaultConfig() Config {
	return Config{
		HoldDown:      10 * time.Minute,
		DefaultPinTTL: time.Hour,
		MaxPinTTL:     24 * time.Hour,
		StaleAfter:    time.Hour,
		EscalateAfter: 15 * time.Minute,
	}
}

// Alert là một cảnh báo Alertmanager đã ánh xạ ra mức.
type Alert struct {
	Key    string // fingerprint
	Name   string // alertname
	Level  int
	Firing bool
}

// Pin là mức ghim tay của người trực.
type Pin struct {
	Level  int       `json:"level"`
	Until  time.Time `json:"until"`
	Reason string    `json:"reason,omitempty"`
}

// Decision là kết quả một bước.
type Decision struct {
	Level    int
	Previous int
	Changed  bool
	Source   string // alertmanager, manual, pin_expired, hold_down, tick
	Reason   string
	Escalate bool // vừa vượt EscalateAfter ở mức ≥ 2
}

// State là ảnh chụp cho GET /level.
type State struct {
	Level         int        `json:"level"`
	Target        int        `json:"target"`
	Base          int        `json:"base"`
	Manual        *int       `json:"manual"`
	Pin           *Pin       `json:"pin,omitempty"`
	Firing        []int      `json:"firing"`
	FiringAlerts  []string   `json:"firingAlerts"`
	HoldDownUntil *time.Time `json:"holdDownUntil,omitempty"`
	Since         time.Time  `json:"since"`
	Escalated     bool       `json:"escalated"`
}

// ErrInvalid báo tham số ngoài miền.
var ErrInvalid = errors.New("engine: tham số không hợp lệ")

type firing struct {
	name  string
	level int
	seen  time.Time
}

// Engine không an toàn cho truy cập đồng thời; bên gọi giữ khoá.
type Engine struct {
	cfg        Config
	firing     map[string]firing
	pin        *Pin
	applied    int
	appliedAt  time.Time
	lowerSince time.Time
	highSince  time.Time
	escalated  bool
}

// New dựng máy trạng thái bắt đầu ở mức initial (mức đang ghi trên cụm, để khởi động lại pod không làm
// tụt mức tức thì: nếu không còn cảnh báo, mức chỉ hạ sau HoldDown).
func New(cfg Config, initial int, now time.Time) *Engine {
	if cfg.HoldDown <= 0 {
		cfg.HoldDown = DefaultConfig().HoldDown
	}
	if cfg.DefaultPinTTL <= 0 {
		cfg.DefaultPinTTL = DefaultConfig().DefaultPinTTL
	}
	if cfg.MaxPinTTL <= 0 {
		cfg.MaxPinTTL = DefaultConfig().MaxPinTTL
	}
	if cfg.StaleAfter <= 0 {
		cfg.StaleAfter = DefaultConfig().StaleAfter
	}
	if cfg.EscalateAfter <= 0 {
		cfg.EscalateAfter = DefaultConfig().EscalateAfter
	}
	cfg.Base = clamp(cfg.Base)
	e := &Engine{cfg: cfg, firing: map[string]firing{}, applied: clamp(initial), appliedAt: now}
	if e.applied >= 2 {
		e.highSince = now
	}
	return e
}

// Level trả mức đang áp dụng.
func (e *Engine) Level() int { return e.applied }

// Alerts ghi nhận một lô cảnh báo từ webhook rồi tính lại mức.
func (e *Engine) Alerts(now time.Time, alerts []Alert, reason string) Decision {
	for _, a := range alerts {
		if a.Key == "" {
			continue
		}
		if a.Firing && a.Level >= 0 && a.Level <= MaxLevel {
			e.firing[a.Key] = firing{name: a.Name, level: a.Level, seen: now}
		} else {
			delete(e.firing, a.Key)
		}
	}
	return e.step(now, "alertmanager", reason)
}

// SetPin ghim mức (level != nil) với thời hạn ttl (0 = mặc định), hoặc bỏ ghim (level == nil).
func (e *Engine) SetPin(now time.Time, level *int, ttl time.Duration, reason string) (Decision, error) {
	if level == nil {
		e.pin = nil
		return e.step(now, "manual", reason), nil
	}
	if *level < 0 || *level > MaxLevel || ttl < 0 || ttl > e.cfg.MaxPinTTL {
		return Decision{Level: e.applied, Previous: e.applied}, ErrInvalid
	}
	if ttl == 0 {
		ttl = e.cfg.DefaultPinTTL
	}
	e.pin = &Pin{Level: *level, Until: now.Add(ttl), Reason: reason}
	return e.step(now, "manual", reason), nil
}

// Tick tính lại theo thời gian: hết hạn ghim, cảnh báo cũ, hết thời gian chống dao động, nâng SEV-2.
func (e *Engine) Tick(now time.Time) Decision { return e.step(now, "tick", "") }

// MaxPinTTL trả thời hạn ghim dài nhất.
func (e *Engine) MaxPinTTL() time.Duration { return e.cfg.MaxPinTTL }

func (e *Engine) desired(now time.Time) int {
	if e.pin != nil {
		return e.pin.Level
	}
	lv := e.cfg.Base
	for _, f := range e.firing {
		if f.level > lv {
			lv = f.level
		}
	}
	return lv
}

func (e *Engine) step(now time.Time, source, reason string) Decision {
	if e.pin != nil && !now.Before(e.pin.Until) {
		e.pin = nil
		if source == "tick" {
			source, reason = "pin_expired", "hết hạn ghim tay"
		}
	}
	for k, f := range e.firing {
		if now.Sub(f.seen) > e.cfg.StaleAfter {
			delete(e.firing, k)
		}
	}
	prev := e.applied
	want := e.desired(now)
	target := prev
	switch {
	case e.pin != nil:
		target = want
		e.lowerSince = time.Time{}
	case want > prev:
		target = want
		e.lowerSince = time.Time{}
	case want < prev:
		if e.lowerSince.IsZero() {
			e.lowerSince = now
		}
		if now.Sub(e.lowerSince) >= e.cfg.HoldDown {
			target = want
			e.lowerSince = time.Time{}
			if source == "tick" {
				source, reason = "hold_down", "điều kiện đã hết đủ thời gian chống dao động"
			}
		}
	default:
		e.lowerSince = time.Time{}
	}
	d := Decision{Level: target, Previous: prev, Source: source, Reason: reason}
	if target != prev {
		d.Changed = true
		e.applied = target
		e.appliedAt = now
		if target < 2 {
			e.highSince, e.escalated = time.Time{}, false
		} else if prev < 2 {
			e.highSince = now
		}
	}
	if e.applied >= 2 && !e.escalated && now.Sub(e.highSince) >= e.cfg.EscalateAfter {
		e.escalated = true
		d.Escalate = true
	}
	return d
}

// Snapshot trả trạng thái cho API.
func (e *Engine) Snapshot(now time.Time) State {
	s := State{Level: e.applied, Target: e.desired(now), Base: e.cfg.Base, Since: e.appliedAt, Escalated: e.escalated,
		Firing: []int{}, FiringAlerts: []string{}}
	if e.pin != nil {
		p := *e.pin
		s.Pin = &p
		lv := p.Level
		s.Manual = &lv
	}
	levels := map[int]bool{}
	names := map[string]bool{}
	for _, f := range e.firing {
		levels[f.level] = true
		if f.name != "" {
			names[f.name] = true
		}
	}
	for l := range levels {
		s.Firing = append(s.Firing, l)
	}
	sort.Ints(s.Firing)
	for n := range names {
		s.FiringAlerts = append(s.FiringAlerts, n)
	}
	sort.Strings(s.FiringAlerts)
	if !e.lowerSince.IsZero() {
		t := e.lowerSince.Add(e.cfg.HoldDown)
		s.HoldDownUntil = &t
	}
	return s
}

func clamp(l int) int {
	if l < 0 {
		return 0
	}
	if l > MaxLevel {
		return MaxLevel
	}
	return l
}
