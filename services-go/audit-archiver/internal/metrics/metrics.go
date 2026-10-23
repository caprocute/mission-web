// Package metrics xuất chỉ số dạng văn bản Prometheus (không thêm thư viện client, giống ledger-service). Tên chỉ
// số khớp luật cảnh báo trong chart audit-archiver.
package metrics

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"math"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// Registry giữ các chỉ số có nhãn.
type Registry struct {
	mu     sync.Mutex
	help   map[string]string
	kind   map[string]string
	values map[string]map[string]float64 // tên → chuỗi nhãn → giá trị
	funcs  []func(*Registry)
}

// New tạo registry rỗng.
func New() *Registry {
	return &Registry{help: map[string]string{}, kind: map[string]string{}, values: map[string]map[string]float64{}}
}

// Describe khai báo chỉ số; kind là counter hoặc gauge.
func (r *Registry) Describe(name, kind, help string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.help[name], r.kind[name] = help, kind
	if r.values[name] == nil {
		r.values[name] = map[string]float64{}
	}
}

// OnCollect đăng ký hàm cập nhật chỉ số ngay trước khi xuất (độ trễ tính theo thời điểm đọc).
func (r *Registry) OnCollect(fn func(*Registry)) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.funcs = append(r.funcs, fn)
}

func labelString(labels []string) string {
	if len(labels) == 0 {
		return ""
	}
	var b strings.Builder
	b.WriteByte('{')
	for i := 0; i+1 < len(labels); i += 2 {
		if i > 0 {
			b.WriteByte(',')
		}
		v := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`).Replace(labels[i+1])
		fmt.Fprintf(&b, `%s="%s"`, labels[i], v)
	}
	b.WriteByte('}')
	return b.String()
}

// Add cộng vào counter; labels là cặp tên, giá trị.
func (r *Registry) Add(name string, v float64, labels ...string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.values[name] == nil {
		r.values[name] = map[string]float64{}
	}
	r.values[name][labelString(labels)] += v
}

// Set đặt giá trị gauge.
func (r *Registry) Set(name string, v float64, labels ...string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.values[name] == nil {
		r.values[name] = map[string]float64{}
	}
	r.values[name][labelString(labels)] = v
}

// Get đọc giá trị (kiểm thử).
func (r *Registry) Get(name string, labels ...string) float64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.values[name][labelString(labels)]
}

// WriteTo ghi toàn bộ chỉ số theo định dạng văn bản Prometheus 0.0.4.
func (r *Registry) WriteTo(w io.Writer) (int64, error) {
	r.mu.Lock()
	fns := append([]func(*Registry){}, r.funcs...)
	r.mu.Unlock()
	for _, fn := range fns {
		fn(r)
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	names := make([]string, 0, len(r.values))
	for n := range r.values {
		names = append(names, n)
	}
	sort.Strings(names)
	var buf bytes.Buffer
	for _, n := range names {
		if h := r.help[n]; h != "" {
			fmt.Fprintf(&buf, "# HELP %s %s\n", n, h)
		}
		if k := r.kind[n]; k != "" {
			fmt.Fprintf(&buf, "# TYPE %s %s\n", n, k)
		}
		ls := make([]string, 0, len(r.values[n]))
		for l := range r.values[n] {
			ls = append(ls, l)
		}
		sort.Strings(ls)
		for _, l := range ls {
			v := r.values[n][l]
			if math.IsNaN(v) {
				continue
			}
			fmt.Fprintf(&buf, "%s%s %g\n", n, l, v)
		}
	}
	n, err := w.Write(buf.Bytes())
	return int64(n), err
}

// Handler phục vụ /metrics.
func (r *Registry) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		_, _ = r.WriteTo(w)
	})
}

// Push gửi toàn bộ chỉ số lên Prometheus Pushgateway (job ngắn hạn như kiểm tra hằng ngày); url rỗng thì bỏ qua.
func (r *Registry) Push(ctx context.Context, url, job string) error {
	if url == "" {
		return nil
	}
	var buf bytes.Buffer
	if _, err := r.WriteTo(&buf); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, strings.TrimRight(url, "/")+"/metrics/job/"+job, &buf)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "text/plain; version=0.0.4")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("metrics: đẩy lên Pushgateway: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("metrics: Pushgateway trả %d", resp.StatusCode)
	}
	return nil
}
