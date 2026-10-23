package api

import (
	"context"
	"net/http"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"mission/services-go/tenant-registry/internal/platform/auth"
	"mission/services-go/tenant-registry/internal/platform/httpx"
	"mission/services-go/tenant-registry/internal/platform/problem"
	"mission/services-go/tenant-registry/internal/tenant"
)

// DV-03-05: provisionConfigTenant, activateConfigTenant (config.yaml; QT-01 bước 4 và 9; SCR-0102);
// INF-26: suspendConfigTenant (QT-01 DANG_VAN_HANH → TAM_NGUNG).

// provisionConfigTenant: POST /api/v1/config/tenants/{id}/provision → 202 + Location /api/v1/jobs/{id}.
func (s *Server) provisionConfigTenant(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok {
		return
	}
	if s.Provision == nil {
		s.fail(w, r, problem.Unavailable(30))
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
	var jobID uuid.UUID
	s.idempotentWrite(w, r, a, nil, modConfig, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		j, err := s.Tenants.StartProvision(ctx, tx, a, id, expected)
		if err != nil {
			return nil, err
		}
		jobID = j.ID
		w.Header().Set("Retry-After", "5")
		return &writeOut{code: http.StatusAccepted, body: tenant.JobViewOf(j), location: "/api/v1/jobs/" + j.ID.String()}, nil
	})
	if jobID != uuid.Nil {
		s.Provision.Start(r.Context(), jobID)
	}
}

// activateRequest là ConfigTenantActivateRequest.
type activateRequest struct {
	Reason string `json:"reason"`
}

// activateConfigTenant: POST /api/v1/config/tenants/{id}/activate (AAL2, Idempotency-Key, If-Match).
func (s *Server) activateConfigTenant(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok {
		return
	}
	if s.Provision == nil {
		s.fail(w, r, problem.Unavailable(30))
		return
	}
	id, err := pathID(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var (
		raw []byte
		in  activateRequest
	)
	if r.ContentLength != 0 && r.Header.Get("Content-Type") != "" { // thân yêu cầu không bắt buộc
		if raw, err = httpx.ReadBody(r, "application/json"); err != nil {
			s.fail(w, r, err)
			return
		}
		if len(raw) > 0 {
			if err := httpx.DecodeStrict(raw, &in); err != nil {
				s.fail(w, r, err)
				return
			}
		}
		if len([]rune(in.Reason)) > 500 {
			s.fail(w, r, problem.Validation(problem.FieldError{Field: "reason", Message: "Lý do tối đa 500 ký tự"}))
			return
		}
	}
	expected, err := httpx.IfMatch(r, modConfig)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	a := s.actor(r, p)
	s.idempotentWrite(w, r, a, raw, modConfig, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		v, err := s.Provision.Activate(ctx, tx, a, id, expected, in.Reason)
		if err != nil {
			return nil, err
		}
		rv := v.RowVersion
		return &writeOut{code: http.StatusOK, body: v, stored: v.WithoutC2(), etag: &rv}, nil
	})
}

// suspendConfigTenant: POST /api/v1/config/tenants/{id}/suspend (AAL2, Idempotency-Key, If-Match, lý do bắt buộc).
func (s *Server) suspendConfigTenant(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok {
		return
	}
	id, err := pathID(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	raw, err := httpx.ReadBody(r, "application/json")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var in activateRequest
	if err := httpx.DecodeStrict(raw, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	reason := strings.TrimSpace(in.Reason)
	if reason == "" || len([]rune(reason)) > 500 {
		s.fail(w, r, problem.Validation(problem.FieldError{Field: "reason", Message: "Nhập lý do tạm ngừng (tối đa 500 ký tự)"}))
		return
	}
	expected, err := httpx.IfMatch(r, modConfig)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	a := s.actor(r, p)
	s.idempotentWrite(w, r, a, raw, modConfig, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		v, err := s.Tenants.Suspend(ctx, tx, a, id, expected, reason)
		if err != nil {
			return nil, err
		}
		rv := v.RowVersion
		return &writeOut{code: http.StatusOK, body: v, stored: v.WithoutC2(), etag: &rv}, nil
	})
}

// WaitSagas chờ các saga cấp phát đang chạy (tắt mềm, kiểm thử).
func (s *Server) WaitSagas() {
	if s.Provision != nil {
		s.Provision.Wait()
	}
}
