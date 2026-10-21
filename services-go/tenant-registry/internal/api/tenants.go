package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"mission/services-go/tenant-registry/internal/platform/auth"
	"mission/services-go/tenant-registry/internal/platform/dbx"
	"mission/services-go/tenant-registry/internal/platform/httpx"
	"mission/services-go/tenant-registry/internal/platform/idem"
	"mission/services-go/tenant-registry/internal/platform/logx"
	"mission/services-go/tenant-registry/internal/platform/problem"
	"mission/services-go/tenant-registry/internal/store"
	"mission/services-go/tenant-registry/internal/tenant"
)

// DV-03-03: vòng đời tenant (QT-01; SCR-0101, SCR-0102). Thao tác /config/tenants* hiện thực tại SVC-03 theo
// đúng lược đồ config.yaml (hợp đồng ghi x-mission.owner MOD-08; đề nghị sửa ở wiki trang 14).

const (
	modConfig   = "CONFIG"
	modRegistry = "REGISTRY"
)

// actorNS sinh mã tác nhân ổn định khi claim sub không phải UUID (suy luận; Keycloak 26 dùng UUID).
var actorNS = uuid.MustParse("0192f1a2-0000-7000-8000-00000000a000")

func (s *Server) actor(r *http.Request, p *auth.Principal) tenant.Actor {
	id, err := uuid.Parse(p.Subject)
	if err != nil {
		id = uuid.NewSHA1(actorNS, []byte(p.Subject))
	}
	return tenant.Actor{ID: id, PlatformAdmin: p.HasRole(auth.RolePlatformAdmin), TenantAdmin: p.HasRole(auth.RoleTenantAdmin),
		Service: p.HasRole(auth.RoleServiceClient), Tenant: p.Tenant, TraceParent: r.Header.Get("traceparent")}
}

// bizErr ánh xạ lỗi nghiệp vụ sang Problem Details; type trỏ BR hoặc MSG tương ứng.
func bizErr(module string, err error) error {
	var (
		ve  *tenant.ValidationError
		de  *tenant.DuplicateError
		ce  *tenant.CapacityError
		se  *tenant.StateError
		te  *tenant.TransitionError
		ver *tenant.VersionError
		cu  *tenant.CellUnavailableError
		pm  *tenant.PackMissingError
		p   *problem.Problem
	)
	switch {
	case errors.As(err, &p):
		return p
	case errors.Is(err, tenant.ErrNotFound):
		return problem.NotFound()
	case errors.Is(err, tenant.ErrForbidden):
		return problem.Forbidden()
	case errors.As(err, &ve):
		fe := make([]problem.FieldError, 0, len(ve.Fields))
		for _, f := range ve.Fields {
			fe = append(fe, problem.FieldError{Field: f.Field, Message: f.Message, Rule: f.Rule})
		}
		pr := problem.Validation(fe...)
		pr.Code = module + "-422-VALIDATION"
		return pr
	case errors.Is(err, tenant.ErrSiloRequired):
		return problem.New(http.StatusUnprocessableEntity, "br-01-01", "Tenant thuộc diện bắt buộc dùng mô hình silo", module+"-422-SILO-REQUIRED").
			WithDetail("Tenant trên 1.000.000 tài khoản hoặc thuộc khối Đảng Trung ương, lực lượng vũ trang phải dùng mô hình silo.")
	case errors.Is(err, tenant.ErrSiloKek):
		pr := problem.Validation(problem.FieldError{Field: "kekMode", Message: "Mô hình silo bắt buộc dùng khoá mã hoá riêng"})
		pr.Code = module + "-422-VALIDATION"
		return pr
	case errors.Is(err, tenant.ErrAirgap):
		pr := problem.Validation(problem.FieldError{Field: "tenancyModel", Message: "Chưa hỗ trợ gắn cell cho mô hình airgap"})
		pr.Code = module + "-422-VALIDATION"
		return pr
	case errors.As(err, &ce):
		pr := problem.New(http.StatusUnprocessableEntity, "br-01-02", "Cell vượt ngưỡng dung lượng", module+"-422-CELL-OVER-CAPACITY")
		if ce.Cell == "" {
			return pr.WithMsg("MSG-01-03").WithDetail("Không còn cell %s nào dưới ngưỡng 85%% sau khi thêm tenant. Vui lòng mở yêu cầu dựng cell mới.", ce.Model)
		}
		return pr.WithDetail("Cell %s sẽ vượt ngưỡng 85%% dung lượng thiết kế nếu thêm tenant này.", ce.Cell)
	case errors.As(err, &pm):
		return problem.New(http.StatusUnprocessableEntity, "br-01-04", "Chưa gắn đủ gói chính sách cho mỗi luồng", module+"-422-FLOW-PACK-MISSING").
			WithDetail("Tenant thuộc cả hai luồng phải gắn đủ một gói chính sách cho mỗi luồng.")
	case errors.As(err, &de):
		if module == modRegistry {
			return problem.New(http.StatusConflict, "duplicate", "Dữ liệu đã tồn tại", "REGISTRY-409-DUPLICATE").
				WithDetail("Mã tenant, mã hồ sơ khởi tạo hoặc tên miền đã tồn tại.")
		}
		if de.Field == "domain" {
			return problem.New(http.StatusConflict, "msg-01-04", "Tên miền đã được sử dụng", "CONFIG-409-DOMAIN-TAKEN").
				WithMsg("MSG-01-04").WithDetail("Tên miền %s đã được sử dụng.", de.Value)
		}
		label := map[string]string{"code": "Mã tenant", "name": "Tên tenant"}[de.Field]
		if label == "" {
			label = "Dữ liệu"
		}
		return problem.New(http.StatusConflict, "duplicate", "Dữ liệu đã tồn tại", "CONFIG-409-DUPLICATE").WithDetail("%s đã được sử dụng.", label)
	case errors.As(err, &cu):
		if module == modRegistry {
			return problem.New(http.StatusConflict, "cell-unavailable", "Cell đích không hoạt động", "REGISTRY-409-CELL-UNAVAILABLE").
				WithDetail("Cell %s không ở trạng thái hoạt động.", cu.Cell)
		}
		pr := problem.Validation(problem.FieldError{Field: "cellCode", Message: "Cell đang bảo trì hoặc đã ngừng"})
		pr.Code = module + "-422-VALIDATION"
		return pr
	case errors.As(err, &se), errors.As(err, &te):
		return problem.New(http.StatusConflict, "tenant-state", "Trạng thái tenant không cho phép thao tác này", module+"-409-TENANT-STATE")
	case errors.As(err, &ver):
		return problem.VersionConflict(module, ver.Sent, ver.Current)
	}
	return err
}

// writeOut là kết quả của một lệnh ghi.
type writeOut struct {
	code     int
	body     any // phản hồi
	stored   any // bản lưu vào idempotency_key (đã lược C2)
	etag     *int
	location string
}

// idempotentWrite chạy fn trong một giao dịch cùng khoá chống ghi trùng (TL-03; 48 giờ). Cùng khoá cùng nội
// dung: trả lại phản hồi đã lưu; cùng khoá khác nội dung: 422; thiếu khoá: 422.
func (s *Server) idempotentWrite(w http.ResponseWriter, r *http.Request, a tenant.Actor, raw []byte, module string,
	fn func(ctx context.Context, tx pgx.Tx) (*writeOut, error)) {
	key := strings.TrimSpace(r.Header.Get("Idempotency-Key"))
	if key == "" {
		s.fail(w, r, problem.IdempotencyKeyRequired())
		return
	}
	if len(key) > 64 {
		s.fail(w, r, problem.Validation(problem.FieldError{Field: "Idempotency-Key", Message: "Khoá chống ghi trùng tối đa 64 ký tự"}))
		return
	}
	scope := idem.Scope{TenantID: uuid.Nil, ActorID: a.ID, Key: key}
	if tid, err := uuid.Parse(a.Tenant); err == nil {
		scope.TenantID = tid
	}
	hash := idem.Hash(r.Method, r.URL.Path+"|"+r.Header.Get("If-Match"), raw)
	var (
		out    *writeOut
		replay *idem.Stored
	)
	err := dbx.InTx(r.Context(), s.Pool, func(tx pgx.Tx) error {
		st, err := idem.Begin(r.Context(), tx, scope, hash)
		if errors.Is(err, idem.ErrMismatch) {
			return problem.IdempotencyMismatch()
		}
		if err != nil {
			return err
		}
		if st != nil {
			replay = st
			return nil
		}
		o, err := fn(r.Context(), tx)
		if err != nil {
			return bizErr(module, err)
		}
		stored := o.stored
		if stored == nil {
			stored = o.body
		}
		if err := idem.Complete(r.Context(), tx, scope, o.code, stored, o.location); err != nil {
			return err
		}
		out = o
		return nil
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if replay != nil {
		if replay.ResourceRef != "" {
			w.Header().Set("Location", replay.ResourceRef)
		}
		var rv struct {
			RowVersion *int `json:"rowVersion"`
		}
		if json.Unmarshal(replay.Body, &rv) == nil && rv.RowVersion != nil {
			w.Header().Set("ETag", httpx.ETag(*rv.RowVersion))
		}
		w.Header().Set("Idempotency-Replayed", "true")
		if len(replay.Body) == 0 || replay.Code == http.StatusNoContent {
			w.WriteHeader(replay.Code)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(replay.Code)
		_, _ = w.Write(replay.Body)
		return
	}
	if out.location != "" {
		w.Header().Set("Location", out.location)
	}
	if out.etag != nil {
		w.Header().Set("ETag", httpx.ETag(*out.etag))
	}
	if out.body == nil {
		w.WriteHeader(out.code)
		return
	}
	httpx.WriteJSON(w, out.code, out.body)
}

func pathID(r *http.Request) (uuid.UUID, error) {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		return uuid.Nil, problem.NotFound()
	}
	return id, nil
}

// ---------------------------------------------------------------- /config/tenants

// listConfigTenants: GET /api/v1/config/tenants.
func (s *Server) listConfigTenants(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok {
		return
	}
	limit, err := parseLimit(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	q := r.URL.Query()
	page, err := s.Tenants.List(r.Context(), s.Pool, s.actor(r, p), tenant.ListInput{Cell: q.Get("cell"), Model: q.Get("model"),
		Status: q.Get("status"), Q: q.Get("q"), Cursor: q.Get("cursor"), Limit: limit})
	if err != nil {
		s.fail(w, r, bizErr(modConfig, err))
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	httpx.WriteJSON(w, http.StatusOK, page)
}

// createConfigTenant: POST /api/v1/config/tenants.
func (s *Server) createConfigTenant(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok {
		return
	}
	raw, err := httpx.ReadBody(r, "application/json")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var in tenant.CreateInput
	if err := httpx.DecodeStrict(raw, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	a := s.actor(r, p)
	s.idempotentWrite(w, r, a, raw, modConfig, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		v, err := s.Tenants.Create(ctx, tx, a, in)
		if err != nil {
			return nil, err
		}
		rv := v.RowVersion
		return &writeOut{code: http.StatusCreated, body: v, stored: v.WithoutC2(), etag: &rv, location: "/api/v1/config/tenants/" + v.ID.String()}, nil
	})
}

// getConfigTenant: GET /api/v1/config/tenants/{id}.
func (s *Server) getConfigTenant(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin, auth.RoleTenantAdmin)
	if !ok {
		return
	}
	id, err := pathID(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	v, err := s.Tenants.Get(r.Context(), s.Pool, s.actor(r, p), id)
	if err != nil {
		s.fail(w, r, bizErr(modConfig, err))
		return
	}
	w.Header().Set("ETag", httpx.ETag(v.RowVersion))
	w.Header().Set("Cache-Control", "no-store")
	httpx.WriteJSON(w, http.StatusOK, v)
}

// updateConfigTenant: PATCH /api/v1/config/tenants/{id} (application/merge-patch+json, If-Match).
func (s *Server) updateConfigTenant(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin, auth.RoleTenantAdmin)
	if !ok {
		return
	}
	id, err := pathID(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	raw, err := httpx.ReadBody(r, "application/merge-patch+json")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var patch map[string]json.RawMessage
	if err := json.Unmarshal(raw, &patch); err != nil || patch == nil {
		s.fail(w, r, problem.BadRequest("Thân yêu cầu phải là một đối tượng JSON."))
		return
	}
	expected, err := httpx.IfMatch(r, modConfig)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	a := s.actor(r, p)
	s.idempotentWrite(w, r, a, raw, modConfig, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		v, err := s.Tenants.Patch(ctx, tx, a, id, expected, patch)
		if err != nil {
			return nil, err
		}
		rv := v.RowVersion
		return &writeOut{code: http.StatusOK, body: v, stored: v.WithoutC2(), etag: &rv}, nil
	})
}

// cancelConfigTenant: DELETE /api/v1/config/tenants/{id}.
func (s *Server) cancelConfigTenant(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok {
		return
	}
	id, err := pathID(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	expected, err := httpx.IfMatch(r, modConfig)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	a := s.actor(r, p)
	s.idempotentWrite(w, r, a, nil, modConfig, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		if err := s.Tenants.Cancel(ctx, tx, a, id, expected); err != nil {
			return nil, err
		}
		return &writeOut{code: http.StatusNoContent}, nil
	})
}

// exportConfigTenants: POST /api/v1/config/tenants/export → 202 + Location /api/v1/jobs/{id}.
func (s *Server) exportConfigTenants(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok {
		return
	}
	raw, err := httpx.ReadBody(r, "application/json")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var in tenant.ExportRequest
	if err := httpx.DecodeStrict(raw, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	if err := in.Check(); err != nil {
		s.fail(w, r, bizErr(modConfig, err))
		return
	}
	a := s.actor(r, p)
	var jobID uuid.UUID
	s.idempotentWrite(w, r, a, raw, modConfig, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		w.Header().Set("Retry-After", "2")
		j, err := tenant.NewExportJob(ctx, tx, a, in)
		if err != nil {
			return nil, err
		}
		jobID = j.ID
		return &writeOut{code: http.StatusAccepted, body: tenant.JobViewOf(j), location: "/api/v1/jobs/" + j.ID.String()}, nil
	})
	if jobID != uuid.Nil {
		s.startJob(r.Context(), jobID)
	}
}

// startJob chạy tác vụ xuất ngoài vòng đời request.
func (s *Server) startJob(reqCtx context.Context, id uuid.UUID) {
	ctx := context.WithoutCancel(reqCtx)
	s.jobs.Add(1)
	go func() {
		defer s.jobs.Done()
		if err := s.Tenants.RunExport(ctx, s.Pool, id); err != nil {
			s.Log.ErrorContext(ctx, "tác vụ xuất lỗi", append(logx.Attrs(reqCtx), "job", id.String(), "err", err.Error())...)
		}
	}()
}

// WaitJobs chờ các tác vụ nền đang chạy (tắt mềm, kiểm thử).
func (s *Server) WaitJobs() { s.jobs.Wait() }

// getJob: GET /api/v1/jobs/{id}; chỉ người khởi tạo xem được, người khác nhận 404.
func (s *Server) getJob(w http.ResponseWriter, r *http.Request) {
	p, ok := s.authenticate(w, r)
	if !ok {
		return
	}
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		s.fail(w, r, problem.NotFound())
		return
	}
	j, err := store.GetJob(r.Context(), s.Pool, id)
	if err != nil || j.CreatedBy != s.actor(r, p).ID {
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			s.fail(w, r, err)
			return
		}
		s.fail(w, r, problem.NotFound())
		return
	}
	if j.Status == "CHO" || j.Status == "DANG_CHAY" {
		w.Header().Set("Retry-After", "2")
	}
	w.Header().Set("Cache-Control", "no-store")
	httpx.WriteJSON(w, http.StatusOK, tenant.JobViewOf(j))
}

// ---------------------------------------------------------------- /registry/tenants

// createRegistryTenant: POST /api/v1/registry/tenants.
func (s *Server) createRegistryTenant(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin, auth.RoleServiceClient)
	if !ok {
		return
	}
	raw, err := httpx.ReadBody(r, "application/json")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var in tenant.RegistryCreateInput
	if err := httpx.DecodeStrict(raw, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	a := s.actor(r, p)
	s.idempotentWrite(w, r, a, raw, modRegistry, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		v, err := s.Tenants.CreateRegistry(ctx, tx, a, in)
		if err != nil {
			return nil, err
		}
		rv := v.RowVersion
		return &writeOut{code: http.StatusCreated, body: v, etag: &rv, location: fmt.Sprintf("/api/v1/registry/tenants/%s", v.ID)}, nil
	})
}

// getRegistryTenant: GET /api/v1/registry/tenants/{id}.
func (s *Server) getRegistryTenant(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok {
		return
	}
	id, err := pathID(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	v, err := s.Tenants.GetRegistry(r.Context(), s.Pool, s.actor(r, p), id)
	if err != nil {
		s.fail(w, r, bizErr(modRegistry, err))
		return
	}
	w.Header().Set("ETag", httpx.ETag(v.RowVersion))
	w.Header().Set("Cache-Control", "no-store")
	httpx.WriteJSON(w, http.StatusOK, v)
}
