// Package server cung cấp máy chủ HTTP vận hành (healthz, readyz, metrics và xử lý lỗi chuẩn RFC 9457)
// cho SVC-09 Ledger Service.
package server

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/ledger/internal/auth"
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
	Verify  *verify.Service
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

func (s *Server) handleMetrics(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	metrics := map[string]any{}
	if s.deps.OTel != nil {
		for _, name := range []string{
			"http.server.requests",
			"ledger.ingest.batches_total",
			"ledger.ingest.entries_total",
			"ledger.ingest.duplicates_total",
			"ledger.ingest.rejected_c2_total",
			"ledger.ingest.incidents_total",
			"ledger.seal.cell_seals_total",
			"ledger.seal.tsa_failures_total",
			"ledger.seal.tsa_overdue_total",
			"ledger.verify.runs_total",
			"ledger.verify.mismatches_total",
			"ledger.verify.chain_broken_total",
		} {
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
