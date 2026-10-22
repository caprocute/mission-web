// Package channel gửi thông báo qua các kênh ngoài ứng dụng của SVC-12 (DV-12-03, TL-01 "SVC-12
// notification-service"): email (SMTP có TLS), SMS (cổng SMS Viettel), đẩy di động FCM HTTP v1 và APNs HTTP/2,
// và kênh đẩy nội bộ cho bản cách ly. Địa chỉ nhận chỉ có trong Message lúc gửi, không bao giờ được in ra:
// Message che địa chỉ khi bị định dạng, lỗi trả về không chứa địa chỉ.
package channel

import (
	"context"
	"errors"
	"fmt"
)

// Kênh theo CHECK của notify.delivery.
const (
	Email = "EMAIL"
	SMS   = "SMS"
	Push  = "PUSH"
)

// Lỗi phân loại để worker quyết định thử lại hay dừng.
var (
	// ErrPermanent là lỗi không thử lại được (địa chỉ sai, nội dung bị từ chối): THAT_BAI ngay.
	ErrPermanent = errors.New("channel: lỗi vĩnh viễn")
	// ErrUnregistered là thiết bị đã gỡ ứng dụng hoặc mã thiết bị hết hiệu lực: thu hồi thiết bị.
	ErrUnregistered = errors.New("channel: thiết bị không còn đăng ký")
)

// Permanent bọc lỗi vĩnh viễn kèm mã ngắn (không chứa địa chỉ).
func Permanent(code string) error { return fmt.Errorf("%w: %s", ErrPermanent, code) }

// Transient là lỗi tạm thời kèm mã ngắn, worker thử lại có giới hạn.
type Transient struct{ Code string }

func (e Transient) Error() string { return "channel: lỗi tạm thời: " + e.Code }

// Message là một lần gửi tới một địa chỉ.
type Message struct {
	// To là email, số điện thoại hoặc mã thiết bị; C2, chỉ nằm trong bộ nhớ.
	To       string
	Platform string // FCM, APNS với kênh PUSH
	Subject  string
	Body     string
	DeepLink string
	// High là ưu tiên CAO: đẩy ưu tiên cao, đi cả trong giờ yên lặng.
	High bool
	// Key là mã delivery, dùng làm khoá chống gửi trùng phía nhà cung cấp khi có.
	Key string
}

// String che địa chỉ khi Message bị in nhầm vào nhật ký.
func (m Message) String() string { return "Message{" + m.Key + " [REDACTED:C2]}" }

// GoString che địa chỉ với định dạng %#v.
func (m Message) GoString() string { return m.String() }

// Sender gửi một Message, trả mã phía nhà cung cấp.
type Sender interface {
	Send(ctx context.Context, m Message) (providerID string, err error)
}

// Code trả mã lỗi ngắn để ghi last_error.
func Code(err error) string {
	var t Transient
	switch {
	case err == nil:
		return ""
	case errors.As(err, &t):
		return t.Code
	case errors.Is(err, ErrUnregistered):
		return "UNREGISTERED"
	case errors.Is(err, ErrPermanent):
		msg := err.Error()
		if i := len("channel: lỗi vĩnh viễn: "); len(msg) > i {
			return trim(msg[i:])
		}
		return "PERMANENT"
	case errors.Is(err, context.DeadlineExceeded):
		return "TIMEOUT"
	default:
		return "ERROR"
	}
}

func trim(s string) string {
	if len(s) > 64 {
		return s[:64]
	}
	return s
}
