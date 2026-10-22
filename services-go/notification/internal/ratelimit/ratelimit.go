// Package ratelimit giới hạn tốc độ gửi theo kênh của đợt thông báo hàng loạt (DV-12-04, TL-03 "Chính sách theo
// loại dữ liệu": bộ đếm tần suất cửa sổ 60 giây trong Valkey). Dùng nhật ký trượt (sorted set theo thời điểm) nên
// số lần gửi trong mọi cửa sổ dài Window không vượt Limit, kể cả khi nhiều pod cùng gửi; đồng hồ lấy từ Valkey.
package ratelimit

import (
	"context"
	"strconv"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
)

// Limiter giữ chỗ gửi.
type Limiter interface {
	// Reserve giữ tối đa n lượt gửi của kênh, trả số lượt được cấp và hàm trả lại các lượt không dùng.
	Reserve(ctx context.Context, channel string, n int) (granted int, release func(unused int), err error)
	// Note ghi một lần gửi thường (không chờ giới hạn) để đợt hàng loạt nhường phần năng lực đó.
	Note(ctx context.Context, channel string)
}

// Limits là số lần gửi tối đa của mỗi kênh trong Window.
type Limits struct {
	Window time.Duration
	Per    map[string]int
}

// Rate trả tốc độ mỗi giây của kênh.
func (l Limits) Rate(channel string) float64 {
	if l.Window <= 0 || l.Per[channel] <= 0 {
		return 0
	}
	return float64(l.Per[channel]) / l.Window.Seconds()
}

// Ghi thời điểm bằng giờ của Valkey, xoá phần ngoài cửa sổ, cấp min(n, limit - đang có) lượt.
var reserve = redis.NewScript(`
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
local win = tonumber(ARGV[1])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - win)
local k = math.min(tonumber(ARGV[3]), tonumber(ARGV[2]) - redis.call('ZCARD', KEYS[1]))
if k < 0 then k = 0 end
for i = 1, k do redis.call('ZADD', KEYS[1], now, ARGV[4] .. ':' .. i) end
redis.call('PEXPIRE', KEYS[1], math.ceil(win / 1000) + 1000)
return k
`)

// Valkey là bộ giới hạn dùng chung giữa các pod.
type Valkey struct {
	R      redis.UniversalClient
	Limits Limits
}

func key(ch string) string { return "sys:notify:rate:{" + ch + "}" }

// Reserve giữ chỗ trong Valkey.
func (v Valkey) Reserve(ctx context.Context, ch string, n int) (int, func(int), error) {
	limit := v.Limits.Per[ch]
	if limit <= 0 {
		return n, func(int) {}, nil
	}
	id := uuid.NewString()
	k, err := reserve.Run(ctx, v.R, []string{key(ch)}, v.Limits.Window.Microseconds(), limit, n, id).Int()
	if err != nil {
		return 0, func(int) {}, err
	}
	release := func(unused int) {
		if unused <= 0 {
			return
		}
		members := make([]any, 0, unused)
		for i := k - unused + 1; i <= k; i++ {
			members = append(members, id+":"+strconv.Itoa(i))
		}
		_ = v.R.ZRem(context.WithoutCancel(ctx), key(ch), members...).Err()
	}
	return k, release, nil
}

// Note ghi một lần gửi thường.
func (v Valkey) Note(ctx context.Context, ch string) {
	if v.Limits.Per[ch] <= 0 {
		return
	}
	_ = reserve.Run(ctx, v.R, []string{key(ch)}, v.Limits.Window.Microseconds(), 1<<30, 1, uuid.NewString()).Err()
}

// Memory là bộ giới hạn trong một tiến trình, dùng khi chưa có Valkey (dev, một pod).
type Memory struct {
	Limits Limits
	Now    func() time.Time
	mu     sync.Mutex
	log    map[string][]time.Time
}

func (m *Memory) prune(ch string, now time.Time) []time.Time {
	if m.log == nil {
		m.log = map[string][]time.Time{}
	}
	l := m.log[ch]
	i := 0
	for i < len(l) && !l[i].After(now.Add(-m.Limits.Window)) {
		i++
	}
	return l[i:]
}

func (m *Memory) now() time.Time {
	if m.Now != nil {
		return m.Now()
	}
	return time.Now()
}

// Reserve giữ chỗ trong bộ nhớ.
func (m *Memory) Reserve(_ context.Context, ch string, n int) (int, func(int), error) {
	limit := m.Limits.Per[ch]
	if limit <= 0 {
		return n, func(int) {}, nil
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	l := m.prune(ch, now)
	k := max(0, min(n, limit-len(l)))
	for range k {
		l = append(l, now)
	}
	m.log[ch] = l
	return k, func(unused int) {
		m.mu.Lock()
		defer m.mu.Unlock()
		cur := m.log[ch]
		m.log[ch] = cur[:max(0, len(cur)-max(0, unused))]
	}, nil
}

// Note ghi một lần gửi thường.
func (m *Memory) Note(_ context.Context, ch string) {
	if m.Limits.Per[ch] <= 0 {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	m.log[ch] = append(m.prune(ch, now), now)
}
