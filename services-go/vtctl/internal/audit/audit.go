// Package audit ghi một bản ghi kiểm toán cho mỗi lệnh ghi đã chạy thật (có --confirm), dạng JSON một dòng vào
// tệp nhật ký cục bộ. Fluent Bit trên máy trạm vận hành hoặc pod Job đọc tệp này và chuyển SIEM (suy luận,
// chọn theo INF-19); thao tác phía máy chủ còn có kiểm toán riêng của dịch vụ đích (sys.audit.v1).
package audit

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"time"
)

// Outcome của lệnh.
const (
	OutcomeOK     = "THANH_CONG"
	OutcomeFailed = "THAT_BAI"
)

// Record là một bản ghi kiểm toán.
type Record struct {
	Time           time.Time         `json:"time"`
	Actor          string            `json:"actor"`
	Subject        string            `json:"subject,omitempty"`
	Env            string            `json:"env"`
	Cell           string            `json:"cell,omitempty"`
	Command        string            `json:"command"`
	Args           map[string]string `json:"args"`
	IdempotencyKey string            `json:"idempotencyKey,omitempty"`
	Outcome        string            `json:"outcome"`
	Result         map[string]any    `json:"result,omitempty"`
	Error          string            `json:"error,omitempty"`
}

// Logger ghi bản ghi vào tệp, mỗi bản ghi một dòng.
type Logger struct {
	Path string
	Now  func() time.Time
}

var secretFlag = regexp.MustCompile(`(?i)(token|secret|password|key-file|credential)`)

// Redact che giá trị của tham số mang tên bí mật.
func Redact(args map[string]string) map[string]string {
	out := make(map[string]string, len(args))
	for k, v := range args {
		if secretFlag.MatchString(k) && k != "idempotency-key" {
			v = "***"
		}
		out[k] = v
	}
	return out
}

// Write ghi một bản ghi; lỗi ghi được trả về để lệnh báo cho người chạy.
func (l *Logger) Write(r Record) error {
	if l.Now != nil {
		r.Time = l.Now().UTC()
	} else {
		r.Time = time.Now().UTC()
	}
	r.Args = Redact(r.Args)
	raw, err := json.Marshal(r)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(l.Path), 0o700); err != nil {
		return fmt.Errorf("thư mục nhật ký kiểm toán: %w", err)
	}
	f, err := os.OpenFile(l.Path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return fmt.Errorf("mở nhật ký kiểm toán: %w", err)
	}
	defer f.Close()
	if _, err := f.Write(append(raw, '\n')); err != nil {
		return fmt.Errorf("ghi nhật ký kiểm toán: %w", err)
	}
	return f.Sync()
}

// Read đọc mọi bản ghi của tệp (dùng cho kiểm thử và `vtctl audit tail`).
func Read(path string) ([]Record, error) {
	raw, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var out []Record
	start := 0
	for i, b := range raw {
		if b != '\n' {
			continue
		}
		var r Record
		if err := json.Unmarshal(raw[start:i], &r); err != nil {
			return nil, fmt.Errorf("dòng kiểm toán hỏng: %w", err)
		}
		out = append(out, r)
		start = i + 1
	}
	return out, nil
}

// SortedKeys hỗ trợ in tham số theo thứ tự.
func SortedKeys(m map[string]string) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
