// Package server cung cấp máy chủ HTTP vận hành (healthz, readyz, metrics và xử lý lỗi chuẩn RFC 9457)
// cho SVC-09 Ledger Service.
package server

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/ledger/internal/auth"
	"mission/services-go/ledger/internal/export"
	"mission/services-go/ledger/internal/lookup"
	"mission/services-go/ledger/internal/verify"

	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/problem"
)

// Deps gom các phụ thuộc khởi tạo HTTP server của SVC-09.
type Deps struct {
	Pool         *pgxpool.Pool
	ReadOnlyPool *pgxpool.Pool
	Log          *slog.Logger
	OTel         *otel.Provider
	// Stats là số liệu luồng ghi (độ trễ p99, bản ghi/giây theo chuỗi); tuỳ chọn.
	Stats func() map[string]float64
	// Verify, Auth, Auditor phục vụ API kiểm tra toàn vẹn (DV-09-04); thiếu Verify hoặc Auth thì không mở tuyến.
	// Lag là histogram độ trễ ghi (số mẫu ≤ từng cận LagBounds, tổng, số mẫu); Gauges là số đo tức thời
	// khác (thời điểm niêm phong, kiểm tra gần nhất).
	Lag       func() ([]uint64, float64, uint64)
	LagBounds []float64
	Gauges    func() map[string]float64
	Verify    *verify.Service
	// Lookup phục vụ API tra cứu sổ cái (DV-09-05); thiếu thì không mở tuyến.
	Lookup *lookup.Service
	// Export phục vụ API xuất gói bằng chứng (DV-09-05).
	Export  *export.Service
	Auth    auth.Verifier
	Auditor Auditor
}

// Server quản lý các tuyến HTTP của SVC-09.
type Server struct {
	deps Deps
	mux  *http.ServeMux
}

// New khởi tạo HTTP Server của SVC-09.
func New(deps Deps) *Server {
	s := &Server{
		deps: deps,
		mux:  http.NewServeMux(),
	}
	s.registerRoutes()
	return s
}

func (s *Server) registerRoutes() {
	s.mux.HandleFunc("GET /healthz", s.handleHealthz)
	s.mux.HandleFunc("GET /readyz", s.handleReadyz)
	s.mux.HandleFunc("GET /metrics", s.handleMetrics)
	s.registerVerifyRoutes()
	s.registerLedgerRoutes()
	s.registerExportRoutes()
	s.mux.HandleFunc("/", s.handleNotFound)
}

// Handler trả về http.Handler có gắn middleware OpenTelemetry.
func (s *Server) Handler() http.Handler {
	if s.deps.OTel != nil {
		return s.deps.OTel.HTTPMiddleware(s.mux)
	}
	return s.mux
}

func (s *Server) handleHealthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"status":  "UP",
		"service": "ledger",
	})
}

func (s *Server) handleReadyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()

	if s.deps.Pool != nil {
		if err := s.deps.Pool.Ping(ctx); err != nil {
			traceID := otel.TraceIDFromContext(r.Context())
			pd := problem.InternalServerError(r.URL.Path, traceID)
			problem.Write(w, pd)
			return
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"status":  "READY",
		"service": "ledger",
	})
}

// counterNames là các bộ đếm OpenTelemetry của SVC-09 xuất ra /metrics.
var counterNames = []string{
	"http.server.requests",
	"ledger.ingest.batches_total",
	"ledger.ingest.entries_total",
	"ledger.ingest.duplicates_total",
	"ledger.ingest.rejected_c2_total",
	"ledger.ingest.incidents_total",
	"ledger.forward.records_total",
	"ledger.forward.dead_total",
	"ledger.seal.cell_seals_total",
	"ledger.seal.tsa_failures_total",
	"ledger.seal.tsa_overdue_total",
	"ledger.verify.runs_total",
	"ledger.verify.mismatches_total",
	"ledger.verify.failures_total",
}

// handleMetrics xuất định dạng văn bản Prometheus 0.0.4 cho bộ quét của cell (vt-service đặt
// prometheus.io/path=/metrics cho dịch vụ Go); `?format=json` giữ dạng JSON cũ.
func (s *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("format") == "json" {
		s.metricsJSON(w)
		return
	}
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	var b strings.Builder
	if s.deps.OTel != nil {
		for _, name := range counterNames {
			pn := promName(name)
			if !strings.HasSuffix(pn, "_total") {
				pn += "_total"
			}
			fmt.Fprintf(&b, "# TYPE %s counter\n%s %d\n", pn, pn, s.deps.OTel.CounterValue(name))
		}
	}
	if s.deps.Stats != nil {
		stats := s.deps.Stats()
		keys := make([]string, 0, len(stats))
		for k := range stats {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		typed := map[string]bool{}
		for _, k := range keys {
			name, labels := promName(k), ""
			if m := chainKey.FindStringSubmatch(k); m != nil {
				name, labels = "ledger_ingest_chain_"+promName(m[2]), fmt.Sprintf(`{chain="%s"}`, m[1])
			}
			if !typed[name] {
				fmt.Fprintf(&b, "# TYPE %s gauge\n", name)
				typed[name] = true
			}
			fmt.Fprintf(&b, "%s%s %s\n", name, labels, strconv.FormatFloat(stats[k], 'g', -1, 64))
		}
	}
	if s.deps.Lag != nil {
		counts, sum, n := s.deps.Lag()
		b.WriteString("# TYPE vt_ledger_lag_seconds histogram\n")
		for i, le := range s.deps.LagBounds {
			fmt.Fprintf(&b, "vt_ledger_lag_seconds_bucket{le=\"%s\"} %d\n", strconv.FormatFloat(le, 'g', -1, 64), counts[i])
		}
		fmt.Fprintf(&b, "vt_ledger_lag_seconds_bucket{le=\"+Inf\"} %d\nvt_ledger_lag_seconds_sum %s\nvt_ledger_lag_seconds_count %d\n",
			n, strconv.FormatFloat(sum, 'g', -1, 64), n)
	}
	if s.deps.Gauges != nil {
		g := s.deps.Gauges()
		keys := make([]string, 0, len(g))
		for k := range g {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			fmt.Fprintf(&b, "# TYPE %s gauge\n%s %s\n", promName(k), promName(k), strconv.FormatFloat(g[k], 'g', -1, 64))
		}
	}
	_, _ = w.Write([]byte(b.String()))
}

var chainKey = regexp.MustCompile(`^ledger\.ingest\.chain\.(\d{2})\.(.+)$`)

func promName(s string) string { return strings.NewReplacer(".", "_", "-", "_").Replace(s) }

func (s *Server) metricsJSON(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	metrics := map[string]any{}
	if s.deps.OTel != nil {
		for _, name := range counterNames {
			metrics[name] = s.deps.OTel.CounterValue(name)
		}
	}
	if s.deps.Stats != nil {
		for k, v := range s.deps.Stats() {
			metrics[k] = v
		}
	}
	_ = json.NewEncoder(w).Encode(metrics)
}

func (s *Server) handleNotFound(w http.ResponseWriter, r *http.Request) {
	traceID := otel.TraceIDFromContext(r.Context())
	pd := problem.NotFound("Không tìm thấy đường dẫn yêu cầu.", r.URL.Path, traceID)
	problem.Write(w, pd)
}
