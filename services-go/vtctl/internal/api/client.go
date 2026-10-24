// Package api gọi API công bố của Mission theo quy ước baseline mục 13: `/api/v1/<module>`, ghi kèm
// Idempotency-Key, cập nhật kèm If-Match (409 khi xung đột), lỗi RFC 9457, tác vụ dài 202 + Location; thao tác
// `aal: 2` gửi token bậc 2 còn mới và xin bước lên lại khi máy chủ trả `insufficient_user_authentication`.
package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
)

// TokenSource cấp access token; aal2=true thì token phải đạt bậc 2 còn mới, force=true thì xin bước lên lại.
type TokenSource interface {
	Token(ctx context.Context, aal2, force bool) (string, error)
}

// Client gọi một gốc API (registry của môi trường hoặc cổng API của cell).
type Client struct {
	Base   string
	HTTP   *http.Client
	Tokens TokenSource
	// UserAgent ghi phiên bản vtctl để dịch vụ đích nhận ra nguồn gọi trong nhật ký.
	UserAgent string
}

// Request là một lời gọi.
type Request struct {
	Method string
	Path   string
	Body   any
	// IfMatch là ETag/phiên bản đã đọc; rỗng thì không gửi.
	IfMatch string
	// IdempotencyKey gửi kèm mọi lệnh ghi; gọi lại cùng khoá không tạo tác dụng mới.
	IdempotencyKey string
	AAL2           bool
}

// Response là kết quả thành công.
type Response struct {
	Status   int
	ETag     string
	Location string
	Body     []byte
}

// Decode đọc thân JSON.
func (r *Response) Decode(v any) error { return json.Unmarshal(r.Body, v) }

// Problem là lỗi RFC 9457 của Mission (mở rộng `code`, `traceId`).
type Problem struct {
	Status  int    `json:"status"`
	Type    string `json:"type"`
	Title   string `json:"title"`
	Detail  string `json:"detail"`
	Code    string `json:"code"`
	TraceID string `json:"traceId"`
	// Errors là danh sách FieldError (common.yaml), ví dụ từng mục kiểm tra tổng thể chưa đạt.
	Errors []struct {
		Field   string `json:"field"`
		Message string `json:"message"`
	} `json:"errors,omitempty"`
}

func (p *Problem) Error() string {
	msg := p.Title
	if p.Detail != "" {
		msg += ": " + p.Detail
	}
	if p.Code != "" {
		msg += " (" + p.Code + ")"
	}
	if p.TraceID != "" {
		msg += " [traceId " + p.TraceID + "]"
	}
	for _, e := range p.Errors {
		msg += "\n  - " + e.Field + ": " + e.Message
	}
	return fmt.Sprintf("HTTP %d %s", p.Status, msg)
}

// NewKey sinh Idempotency-Key UUIDv7.
func NewKey() string { return uuid.Must(uuid.NewV7()).String() }

// Do gửi lời gọi; 401 kèm `insufficient_user_authentication` thì xin bước lên rồi gửi lại đúng một lần với cùng
// Idempotency-Key.
func (c *Client) Do(ctx context.Context, r Request) (*Response, error) {
	res, err := c.do(ctx, r, false)
	if _, ok := err.(*stepUpNeeded); ok {
		return c.do(ctx, Request{Method: r.Method, Path: r.Path, Body: r.Body, IfMatch: r.IfMatch,
			IdempotencyKey: r.IdempotencyKey, AAL2: true}, true)
	}
	return res, err
}

type stepUpNeeded struct{ p *Problem }

func (s *stepUpNeeded) Error() string { return s.p.Error() }

func (c *Client) do(ctx context.Context, r Request, force bool) (*Response, error) {
	var body io.Reader
	if r.Body != nil {
		raw, err := json.Marshal(r.Body)
		if err != nil {
			return nil, err
		}
		body = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, r.Method, strings.TrimRight(c.Base, "/")+"/api/v1"+r.Path, body)
	if err != nil {
		return nil, err
	}
	tok, err := c.Tokens.Token(ctx, r.AAL2, force)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Accept", "application/json, application/problem+json")
	if c.UserAgent != "" {
		req.Header.Set("User-Agent", c.UserAgent)
	}
	if r.Body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if r.IfMatch != "" {
		req.Header.Set("If-Match", r.IfMatch)
	}
	if r.IdempotencyKey != "" {
		req.Header.Set("Idempotency-Key", r.IdempotencyKey)
	}
	hc := c.HTTP
	if hc == nil {
		hc = &http.Client{Timeout: 60 * time.Second}
	}
	res, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 8<<20))
	if err != nil {
		return nil, err
	}
	if res.StatusCode >= 400 {
		p := &Problem{Status: res.StatusCode}
		if strings.Contains(res.Header.Get("Content-Type"), "json") {
			_ = json.Unmarshal(raw, p)
		}
		p.Status = res.StatusCode
		if p.Title == "" {
			p.Title = http.StatusText(res.StatusCode)
		}
		if res.StatusCode == http.StatusUnauthorized && !force &&
			strings.Contains(res.Header.Get("WWW-Authenticate"), "insufficient_user_authentication") {
			return nil, &stepUpNeeded{p}
		}
		return nil, p
	}
	return &Response{Status: res.StatusCode, ETag: res.Header.Get("ETag"), Location: res.Header.Get("Location"), Body: raw}, nil
}

// Get đọc một tài nguyên.
func (c *Client) Get(ctx context.Context, path string, aal2 bool, out any) (*Response, error) {
	res, err := c.Do(ctx, Request{Method: http.MethodGet, Path: path, AAL2: aal2})
	if err != nil {
		return nil, err
	}
	if out != nil {
		if err := res.Decode(out); err != nil {
			return nil, fmt.Errorf("đọc kết quả %s: %w", path, err)
		}
	}
	return res, nil
}
