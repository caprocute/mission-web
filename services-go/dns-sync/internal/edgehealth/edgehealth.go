// Package edgehealth là đích của kiểm tra sức khoẻ /healthz/edge của cell (INF-20; TL-04 "GeoDNS và định tuyến
// tenant tới cell"). APISIX của cell chuyển /healthz/edge tới đây, nên một câu trả lời OK đã hàm ý điều kiện thứ nhất
// (APISIX nhận request). Hai điều kiện còn lại đọc từ Kubernetes API: mission-core có ít nhất một nửa số replica sẵn
// sàng, và primary của pg-core đang ở TTDL này (cụm CNPG ở đây không phải replica cluster). Điều kiện thứ ba ngăn
// GeoDNS gửi lưu lượng tới TTDL mà CSDL vẫn là bản sao chỉ đọc.
package edgehealth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Kube đọc một tài nguyên Kubernetes theo đường dẫn API.
type Kube interface {
	Get(ctx context.Context, path string) (map[string]any, error)
}

// Checker tổng hợp hai điều kiện đọc từ Kubernetes API, đệm kết quả CacheFor để kiểm tra dồn dập không đè API server.
type Checker struct {
	Kube         Kube
	WorkloadPath string // ví dụ /apis/argoproj.io/v1alpha1/namespaces/core/rollouts/mission-core
	PGPath       string // ví dụ /apis/postgresql.cnpg.io/v1/namespaces/data-pg/clusters/pg-core
	CacheFor     time.Duration
	Now          func() time.Time

	mu     sync.Mutex
	at     time.Time
	ok     bool
	reason string
}

// Check trả (đạt, lý do).
func (c *Checker) Check(ctx context.Context) (bool, string) {
	now := time.Now
	if c.Now != nil {
		now = c.Now
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.at.IsZero() && now().Sub(c.at) < c.CacheFor {
		return c.ok, c.reason
	}
	c.ok, c.reason = c.check(ctx)
	c.at = now()
	return c.ok, c.reason
}

func (c *Checker) check(ctx context.Context) (bool, string) {
	w, err := c.Kube.Get(ctx, c.WorkloadPath)
	if err != nil {
		return false, "mission-core: " + err.Error()
	}
	want := num(w, "spec", "replicas")
	ready := num(w, "status", "readyReplicas")
	if want <= 0 {
		want = 1
	}
	if ready*2 < want {
		return false, fmt.Sprintf("mission-core sẵn sàng %d/%d", ready, want)
	}
	pg, err := c.Kube.Get(ctx, c.PGPath)
	if err != nil {
		return false, "pg-core: " + err.Error()
	}
	if ok, why := primaryHere(pg); !ok {
		return false, why
	}
	return true, "OK"
}

// primaryHere: cụm CNPG ở TTDL này giữ primary ghi được. Với topology phân tán của CNPG (spec.replica.primary/self),
// primary ở đây khi primary == self; với replica cluster kiểu cũ (spec.replica.enabled) thì không phải primary.
func primaryHere(pg map[string]any) (bool, string) {
	spec, _ := pg["spec"].(map[string]any)
	if rep, ok := spec["replica"].(map[string]any); ok {
		p, _ := rep["primary"].(string)
		self, _ := rep["self"].(string)
		if p != "" && self != "" {
			if p != self {
				return false, fmt.Sprintf("pg-core: primary ở %s, không ở %s", p, self)
			}
		} else if en, _ := rep["enabled"].(bool); en {
			return false, "pg-core: cụm ở đây là replica cluster"
		}
	}
	st, _ := pg["status"].(map[string]any)
	if cp, _ := st["currentPrimary"].(string); cp == "" {
		return false, "pg-core: chưa có primary"
	}
	if num(pg, "status", "readyInstances") < 1 {
		return false, "pg-core: không instance nào sẵn sàng"
	}
	return true, ""
}

func num(m map[string]any, path ...string) int {
	var cur any = m
	for _, p := range path {
		mm, ok := cur.(map[string]any)
		if !ok {
			return 0
		}
		cur = mm[p]
	}
	f, _ := cur.(float64)
	return int(f)
}

// Handler trả 200 "OK" khi đạt, 503 "FAIL" khi không; lý do chỉ ghi nhật ký, không trả ra ngoài. PowerDNS so khớp
// chuỗi OK (stringmatch) nên thân trả về phải đúng "OK".
func Handler(c *Checker, logf func(reason string)) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ok, why := c.Check(r.Context())
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		if ok {
			_, _ = io.WriteString(w, "OK")
			return
		}
		if logf != nil {
			logf(why)
		}
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = io.WriteString(w, "FAIL")
	})
}

// InCluster là máy khách Kubernetes API tối giản dùng token của ServiceAccount.
type InCluster struct {
	Base  string
	Token string
	HTTP  *http.Client
}

func (k *InCluster) Get(ctx context.Context, path string) (map[string]any, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(k.Base, "/")+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+k.Token)
	resp, err := k.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var out map[string]any
	return out, json.NewDecoder(io.LimitReader(resp.Body, 4<<20)).Decode(&out)
}
