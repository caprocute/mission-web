package channel

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/runes"
	"golang.org/x/text/transform"
	"golang.org/x/text/unicode/norm"
)

// MaxSMSRunes giới hạn nội dung SMS ở ba phân đoạn không dấu (3 × 153 ký tự).
const MaxSMSRunes = 459

// ViettelSMS gọi API cổng SMS Viettel (brandname). TL chưa có đặc tả API: định dạng dưới đây là suy luận
// (`POST {BaseURL}/v1/sms/send`, JSON `brandname`, `to`, `content`, `requestId`, khoá ở tiêu đề Authorization;
// phản hồi `code` "0" khi nhận, kèm `messageId`), đổi ở đây khi có tài liệu (wiki/14).
type ViettelSMS struct {
	HTTP      *http.Client
	BaseURL   string
	APIKey    string
	Brandname string
	// ASCII bỏ dấu tiếng Việt khi cổng yêu cầu nội dung không dấu.
	ASCII bool
}

type smsReq struct {
	Brandname string `json:"brandname"`
	To        string `json:"to"`
	Content   string `json:"content"`
	RequestID string `json:"requestId"`
}

type smsResp struct {
	Code      string `json:"code"`
	MessageID string `json:"messageId"`
}

// Send gửi một tin nhắn.
func (s ViettelSMS) Send(ctx context.Context, m Message) (string, error) {
	to, ok := NormalizePhone(m.To)
	if !ok {
		return "", Permanent("BAD_ADDRESS")
	}
	text := m.Subject
	if m.Body != "" {
		text += ": " + m.Body
	}
	if s.ASCII {
		text = Unaccent(text)
	}
	text = truncate(text, MaxSMSRunes)
	body, _ := json.Marshal(smsReq{s.Brandname, to, text, m.Key})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(s.BaseURL, "/")+"/v1/sms/send",
		bytes.NewReader(body))
	if err != nil {
		return "", Permanent("REQUEST")
	}
	req.Header.Set("Content-Type", "application/json")
	if s.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+s.APIKey)
	}
	resp, err := client(s.HTTP).Do(req)
	if err != nil {
		return "", Transient{"CONNECT"}
	}
	defer resp.Body.Close()
	if err := status(resp.StatusCode, "SMS"); err != nil {
		return "", err
	}
	var out smsResp
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", Transient{"SMS_DECODE"}
	}
	if out.Code != "" && out.Code != "0" && out.Code != "00" {
		return "", Permanent("SMS_" + trim(out.Code))
	}
	return out.MessageID, nil
}

// NormalizePhone đưa số điện thoại Việt Nam về dạng 84xxxxxxxxx.
func NormalizePhone(p string) (string, bool) {
	var b strings.Builder
	for _, r := range p {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		} else if !strings.ContainsRune(" .-+()", r) {
			return "", false
		}
	}
	d := b.String()
	switch {
	case strings.HasPrefix(d, "84"):
	case strings.HasPrefix(d, "0"):
		d = "84" + d[1:]
	default:
		return "", false
	}
	return d, len(d) >= 11 && len(d) <= 12
}

// Unaccent bỏ dấu tiếng Việt (đ → d).
func Unaccent(s string) string {
	s = strings.NewReplacer("đ", "d", "Đ", "D").Replace(s)
	out, _, err := transform.String(transform.Chain(norm.NFD, runes.Remove(runes.In(unicode.Mn)), norm.NFC), s)
	if err != nil {
		return s
	}
	return out
}

func truncate(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	r := []rune(s)
	return string(r[:n-1]) + "…"
}

func client(c *http.Client) *http.Client {
	if c != nil {
		return c
	}
	return &http.Client{Timeout: 10 * time.Second}
}

// status phân loại mã HTTP: 2xx thành công, 408/429/5xx tạm thời, còn lại vĩnh viễn.
func status(code int, prefix string) error {
	switch {
	case code >= 200 && code < 300:
		return nil
	case code == http.StatusTooManyRequests || code == http.StatusRequestTimeout || code >= 500:
		return Transient{prefix + "_" + http.StatusText(code)}
	default:
		return Permanent(prefix + "_" + strings.ReplaceAll(http.StatusText(code), " ", "_"))
	}
}
