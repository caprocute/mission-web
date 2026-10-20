// Package api hiện thực các thao tác HTTP của SVC-03 tenant-registry theo hợp đồng
// contracts/openapi/modules/platform.yaml (tag Registry) và config.yaml (tag Tenant, phần /config/tenants*),
// cùng probe /healthz, /readyz. Mọi đường dẫn nghiệp vụ dưới /api/v1.
package api

import (
	"context"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"mission/services-go/tenant-registry/internal/platform/auth"
	"mission/services-go/tenant-registry/internal/platform/logx"
	"mission/services-go/tenant-registry/internal/platform/problem"
	"mission/services-go/tenant-registry/internal/resolve"
	"mission/services-go/tenant-registry/internal/tenant"
	"mission/services-go/tenant-registry/pkg/routecookie"
)

// Deps là phụ thuộc của Server.
type Deps struct {
	Pool         *pgxpool.Pool
	Log          *slog.Logger
	Verifier     auth.Verifier
	StepUpMaxAge time.Duration
	Now          func() time.Time
	// DV-03-02: phân giải có đệm và cookie định tuyến.
	Resolver     *resolve.Service
	Cookies      *routecookie.Signer
	CookieDomain string
	// DV-03-03: nghiệp vụ vòng đời tenant.
	Tenants *tenant.Service
}

// Server giữ phụ thuộc và bộ định tuyến.
type Server struct {
	Deps
	mux  *http.ServeMux
	jobs sync.WaitGroup
}

// New dựng Server và đăng ký tuyến.
func New(d Deps) *Server {
	if d.Now == nil {
		d.Now = time.Now
	}
	if d.StepUpMaxAge == 0 {
		d.StepUpMaxAge = 5 * time.Minute
	}
	s := &Server{Deps: d, mux: http.NewServeMux()}
	s.mux.HandleFunc("GET /healthz", s.healthz)
	s.mux.HandleFunc("GET /readyz", s.readyz)
	s.routes()
	s.mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		s.fail(w, r, problem.NotFound())
	})
	return s
}

// Handler trả http.Handler đã bọc nhật ký và truy vết.
func (s *Server) Handler() http.Handler { return logx.Middleware(s.Log, s.mux) }

func (s *Server) healthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	_, _ = w.Write([]byte("ok"))
}

// readyz sẵn sàng khi ping được CSDL trong 1 giây.
func (s *Server) readyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), time.Second)
	defer cancel()
	if err := s.Pool.Ping(ctx); err != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte("database unavailable"))
		return
	}
	_, _ = w.Write([]byte("ready"))
}

// fail ghi lỗi Problem; lỗi không phải Problem được ghi nhật ký và trả 500 chung.
func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	p := problem.From(err)
	if p.Status >= 500 {
		s.Log.ErrorContext(r.Context(), "lỗi xử lý", append(logx.Attrs(r.Context()), "err", err.Error())...)
	}
	problem.Write(w, logx.Trace(r.Context()).TraceID, p)
}

// authenticate kiểm token; trả Principal hoặc ghi 401.
func (s *Server) authenticate(w http.ResponseWriter, r *http.Request) (*auth.Principal, bool) {
	p, err := s.Verifier.Verify(r.Context(), auth.BearerToken(r))
	if err != nil {
		s.fail(w, r, problem.Unauthorized())
		return nil, false
	}
	logx.SetTenant(r.Context(), p.Tenant, p.AC())
	return p, true
}

// requireRole kiểm token, vai trò (x-mission.roles của hợp đồng) và AAL2 khi aal2 = true.
func (s *Server) requireRole(w http.ResponseWriter, r *http.Request, aal2 bool, roles ...string) (*auth.Principal, bool) {
	p, ok := s.authenticate(w, r)
	if !ok {
		return nil, false
	}
	if !p.HasAnyRole(roles...) {
		s.fail(w, r, problem.Forbidden())
		return nil, false
	}
	if aal2 && !p.HasRole(auth.RoleServiceClient) && !p.AAL2Fresh(s.Now(), s.StepUpMaxAge) {
		s.fail(w, r, problem.StepUpRequired(int(s.StepUpMaxAge.Seconds())))
		return nil, false
	}
	return p, true
}
