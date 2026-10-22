package rt

import (
	"context"
	"encoding/json"
	"log/slog"

	"mission/services-go/notification/internal/store"
)

// Notifier đẩy thông báo vừa ghi và số chưa đọc vào stream của người nhận sau khi giao dịch CSDL đã cam kết.
// Lỗi Valkey chỉ ghi nhật ký: CSDL là nguồn sự thật, client tải lại hộp thông báo khi nối lại.
type Notifier struct {
	Pub    Publisher
	Reader store.Reader
	Log    *slog.Logger
}

// Notify đọc lại các thông báo theo mã (đã dựng nội dung) và phát `notification` rồi `unread` cho từng người nhận.
func (n Notifier) Notify(ctx context.Context, tenantID string, byUser map[string][]string) {
	for user, ids := range byUser {
		items, unread, err := n.Reader.ByIDs(ctx, tenantID, user, ids)
		if err != nil {
			n.Log.Error("không đọc được thông báo để đẩy", "err", err.Error())
			continue
		}
		for _, it := range items {
			b, _ := json.Marshal(it)
			if _, err := n.Pub.Publish(ctx, tenantID, user, EventNotification, b); err != nil {
				n.Log.Error("không đẩy được thông báo", "err", err.Error())
			}
		}
		n.Unread(ctx, tenantID, user, unread)
	}
}

// Unread phát số chưa đọc mới.
func (n Notifier) Unread(ctx context.Context, tenantID, userID string, unread int) {
	b, _ := json.Marshal(map[string]int{"unreadCount": unread})
	if _, err := n.Pub.Publish(ctx, tenantID, userID, EventUnread, b); err != nil {
		n.Log.Error("không đẩy được số chưa đọc", "err", err.Error())
	}
}
