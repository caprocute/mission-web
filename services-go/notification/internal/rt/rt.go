// Package rt phân phát thông báo thời gian thực của SVC-12 qua Valkey Streams (TL-01 "SVC-12", TL-03 "Bộ nhớ đệm
// Valkey"). Mỗi người nhận có một stream bền `t:<tenant>:notify:stream:<user>` giữ phần để phát lại theo
// Last-Event-ID; mã sự kiện SSE chính là mã phần tử của stream này. Để một pod không phải giữ một lệnh chặn
// XREAD cho từng người dùng (Valkey cluster không cho XREAD nhiều khoá khác slot), bên ghi thêm một tín hiệu đánh
// thức vào một trong BusShards stream bus `sys:notify:bus:<n>`; mỗi pod đọc chặn toàn bộ bus theo lô và chỉ với
// người dùng đang kết nối tại pod đó mới đọc tiếp stream của người dùng từ mã cuối đã gửi. Tín hiệu bus chỉ để
// đánh thức nên mất hoặc đến sai thứ tự không làm mất sự kiện (suy luận, ghi ở wiki/14).
package rt

import (
	"context"
	"fmt"
	"hash/fnv"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

// Tham số giữ stream: TL-03 giữ 200 phần tử, hợp đồng giữ tối thiểu 10 phút; giữ phần nhiều hơn.
const (
	KeepEntries  = 200
	KeepDuration = 10 * time.Minute
	// StreamTTL xoá stream của người dùng lâu không có thông báo (suy luận).
	StreamTTL = 7 * 24 * time.Hour
	// BusShards là số stream bus; mỗi pod giữ BusShards lệnh đọc chặn (suy luận).
	BusShards = 16
	busMaxLen = 10000
)

// Tên sự kiện SSE theo platform.yaml streamNotifications.
const (
	EventNotification = "notification"
	EventJob          = "job"
	EventUnread       = "unread"
)

// UserStreamKey trả khoá stream thông báo của người dùng theo quy ước khoá tenant của TL-03.
func UserStreamKey(tenantID, userID string) string {
	return "t:" + tenantID + ":notify:stream:" + userID
}

// BusKey trả khoá stream bus thứ n; bus không thuộc tenant nào nên dùng tiền tố hệ thống `sys:` (suy luận).
func BusKey(n int) string { return "sys:notify:bus:" + strconv.Itoa(n) }

func busShard(tenantID, userID string) int {
	h := fnv.New32a()
	h.Write([]byte(tenantID + ":" + userID))
	return int(h.Sum32() % BusShards)
}

// Publisher ghi sự kiện vào stream của người nhận và đánh thức pod đang giữ kết nối.
type Publisher struct {
	R redis.UniversalClient
}

// Publish ghi một sự kiện (notification, job, unread) cho người dùng, trả mã sự kiện. Các service khác phát cập
// nhật tác vụ dài (`job`) theo cùng định dạng: trường `event`, `data` (JSON một dòng) trên stream người dùng,
// rồi trường `t`, `u` trên bus (suy luận, chờ chốt luồng nội bộ thống nhất).
func (p Publisher) Publish(ctx context.Context, tenantID, userID, event string, data []byte) (string, error) {
	switch event {
	case EventNotification, EventJob, EventUnread:
	default:
		return "", fmt.Errorf("rt: sự kiện %q không hỗ trợ", event)
	}
	key := UserStreamKey(tenantID, userID)
	id, err := p.R.XAdd(ctx, &redis.XAddArgs{Stream: key, Values: []any{"event", event, "data", string(data)}}).Result()
	if err != nil {
		return "", err
	}
	if err := p.trim(ctx, key); err != nil {
		return id, err
	}
	err = p.R.XAdd(ctx, &redis.XAddArgs{Stream: BusKey(busShard(tenantID, userID)), MaxLen: busMaxLen, Approx: true,
		Values: []any{"t", tenantID, "u", userID}}).Err()
	return id, err
}

// trim cắt các phần tử vừa ngoài KeepEntries phần tử mới nhất vừa cũ hơn KeepDuration, và gia hạn TTL.
func (p Publisher) trim(ctx context.Context, key string) error {
	pipe := p.R.Pipeline()
	n := pipe.XLen(ctx, key)
	pipe.Expire(ctx, key, StreamTTL)
	if _, err := pipe.Exec(ctx); err != nil {
		return err
	}
	if n.Val() <= KeepEntries+20 {
		return nil
	}
	newest, err := p.R.XRevRangeN(ctx, key, "+", "-", KeepEntries).Result()
	if err != nil || len(newest) < KeepEntries {
		return err
	}
	byCount := newest[len(newest)-1].ID
	byTime := strconv.FormatInt(time.Now().Add(-KeepDuration).UnixMilli(), 10) + "-0"
	minID := byCount
	if Less(byTime, byCount) {
		minID = byTime
	}
	return p.R.XTrimMinID(ctx, key, minID).Err()
}

// Less so sánh hai mã phần tử stream dạng `<ms>-<seq>`.
func Less(a, b string) bool {
	am, as := split(a)
	bm, bs := split(b)
	if am != bm {
		return am < bm
	}
	return as < bs
}

func split(id string) (uint64, uint64) {
	ms, seq, _ := strings.Cut(id, "-")
	m, _ := strconv.ParseUint(ms, 10, 64)
	s, _ := strconv.ParseUint(seq, 10, 64)
	return m, s
}

// ValidID kiểm mã sự kiện client gửi lại.
func ValidID(id string) bool {
	ms, seq, ok := strings.Cut(id, "-")
	if !ok || len(id) > 64 {
		return false
	}
	_, e1 := strconv.ParseUint(ms, 10, 64)
	_, e2 := strconv.ParseUint(seq, 10, 64)
	return e1 == nil && e2 == nil
}
