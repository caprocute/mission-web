package channel

import (
	"sync"
	"time"
)

// Breaker là ngắt mạch của một kênh, tương đương cấu hình Resilience4j chuẩn của TL-01: mở khi lỗi tạm thời liên
// tiếp đạt ngưỡng, sau thời gian mở thì cho một lần thử (nửa mở); thử được thì đóng, lỗi thì mở lại.
type Breaker struct {
	mu        sync.Mutex
	Threshold int
	Open      time.Duration
	Now       func() time.Time
	fails     int
	openUntil time.Time
	probing   bool
}

// NewBreaker tạo ngắt mạch mở sau threshold lỗi liên tiếp, giữ mở trong open.
func NewBreaker(threshold int, open time.Duration) *Breaker {
	return &Breaker{Threshold: threshold, Open: open, Now: time.Now}
}

// Allow cho biết được gửi không; ở trạng thái nửa mở chỉ một lần gửi thử được qua.
func (b *Breaker) Allow() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.openUntil.IsZero() {
		return true
	}
	if b.Now().Before(b.openUntil) || b.probing {
		return false
	}
	b.probing = true
	return true
}

// IsOpen cho biết mạch đang mở (chưa tới lúc thử lại).
func (b *Breaker) IsOpen() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return !b.openUntil.IsZero() && (b.Now().Before(b.openUntil) || b.probing)
}

// Success đóng mạch.
func (b *Breaker) Success() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.fails, b.openUntil, b.probing = 0, time.Time{}, false
}

// Failure ghi một lỗi tạm thời.
func (b *Breaker) Failure() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.fails++
	if b.probing || b.fails >= b.Threshold {
		b.openUntil = b.Now().Add(b.Open)
		b.probing = false
	}
}

// Release trả lượt thử nửa mở khi việc không gửi được vì lý do khác lỗi kênh.
func (b *Breaker) Release() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.probing = false
}
