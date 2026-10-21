package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"mission/services-go/tenant-registry/internal/move"
	"mission/services-go/tenant-registry/internal/platform/auth"
	"mission/services-go/tenant-registry/internal/platform/httpx"
	"mission/services-go/tenant-registry/internal/platform/problem"
	"mission/services-go/tenant-registry/internal/tenant"
)

// DV-03-07: chuyển tenant giữa các cell (platform.yaml requestTenantMove, approveTenantMove, getTenantMove,
// cancelTenantMove; ADR-29; bốn mắt).

// moveErr ánh xạ lỗi của lệnh chuyển sang Problem (mã theo mô tả 403, 409 của hợp đồng).
func moveErr(err error) error {
	var ms *move.MoveStateError
	switch {
	case errors.Is(err, move.ErrSameApprover):
		return problem.New(http.StatusForbidden, "four-eyes", "Người duyệt phải khác người đề nghị", "REGISTRY-403-SAME-APPROVER").
			WithDetail("Thao tác này cần một quản trị nền tảng khác người đề nghị thực hiện.")
	case errors.Is(err, move.ErrSameCell):
		return problem.New(http.StatusConflict, "same-cell", "Cell đích trùng cell hiện tại", "REGISTRY-409-SAME-CELL")
	case errors.Is(err, move.ErrInProgress):
		return problem.New(http.StatusConflict, "move-in-progress", "Đơn vị đang có lần chuyển khác", "REGISTRY-409-MOVE-IN-PROGRESS")
	case errors.Is(err, move.ErrCutover):
		return problem.New(http.StatusConflict, "move-cutover", "Đã chuyển xong danh bạ, không huỷ được", "REGISTRY-409-MOVE-ALREADY-CUTOVER")
	case errors.Is(err, move.ErrNotFound):
		return problem.NotFound()
	case errors.As(err, &ms):
		return problem.New(http.StatusConflict, "move-state", "Trạng thái lệnh chuyển không cho phép thao tác này", "REGISTRY-409-MOVE-STATE")
	}
	return bizErr(modRegistry, err)
}

func moveIDs(r *http.Request) (uuid.UUID, uuid.UUID, error) {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		return uuid.Nil, uuid.Nil, problem.NotFound()
	}
	mid, err := uuid.Parse(r.PathValue("moveId"))
	if err != nil {
		return uuid.Nil, uuid.Nil, problem.NotFound()
	}
	return id, mid, nil
}

// optionalComment đọc ApprovalComment (thân không bắt buộc).
func optionalComment(r *http.Request) ([]byte, string, error) {
	if r.ContentLength == 0 || r.Header.Get("Content-Type") == "" {
		return nil, "", nil
	}
	raw, err := httpx.ReadBody(r, "application/json")
	if err != nil || len(raw) == 0 {
		return raw, "", err
	}
	var in struct {
		Comment string `json:"comment"`
	}
	if err := httpx.DecodeStrict(raw, &in); err != nil {
		return nil, "", err
	}
	if len([]rune(in.Comment)) > 2000 {
		return nil, "", problem.Validation(problem.FieldError{Field: "comment", Message: "Ý kiến tối đa 2000 ký tự"})
	}
	return raw, in.Comment, nil
}

func (s *Server) movesReady(w http.ResponseWriter, r *http.Request) bool {
	if s.Moves == nil {
		s.fail(w, r, problem.Unavailable(30))
		return false
	}
	return true
}

// requestTenantMove: POST /api/v1/registry/tenants/{id}/move (If-Match là phiên bản tenant).
func (s *Server) requestTenantMove(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok || !s.movesReady(w, r) {
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
	var in move.RequestInput
	if err := httpx.DecodeStrict(raw, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	expected, err := httpx.IfMatch(r, modRegistry)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	a := s.actor(r, p)
	s.idempotentWrite(w, r, a, raw, modRegistry, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		v, err := s.Moves.Request(ctx, tx, a, id, expected, in)
		if err != nil {
			return nil, moveErr(err)
		}
		rv := v.RowVersion
		return &writeOut{code: http.StatusCreated, body: v, etag: &rv, location: "/api/v1/registry/tenants/" + id.String() + "/move/" + v.ID.String()}, nil
	})
}

// approveTenantMove: POST /api/v1/registry/tenants/{id}/move/{moveId}/approve → 202 (If-Match là phiên bản lệnh).
func (s *Server) approveTenantMove(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok || !s.movesReady(w, r) {
		return
	}
	id, mid, err := moveIDs(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	raw, comment, err := optionalComment(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	expected, err := httpx.IfMatch(r, modRegistry)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	a := s.actor(r, p)
	started := false
	s.idempotentWrite(w, r, a, raw, modRegistry, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		j, err := s.Moves.Approve(ctx, tx, a, id, mid, expected, comment)
		if err != nil {
			return nil, moveErr(err)
		}
		started = true
		w.Header().Set("Retry-After", "30")
		return &writeOut{code: http.StatusAccepted, body: tenant.JobViewOf(j), location: "/api/v1/jobs/" + j.ID.String()}, nil
	})
	if started && s.MoveRunner != nil {
		s.MoveRunner.Start(r.Context(), mid)
	}
}

// getTenantMove: GET /api/v1/registry/tenants/{id}/move/{moveId}.
func (s *Server) getTenantMove(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok || !s.movesReady(w, r) {
		return
	}
	id, mid, err := moveIDs(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	v, _, err := s.Moves.Get(r.Context(), s.Pool, s.actor(r, p), id, mid)
	if err != nil {
		s.fail(w, r, moveErr(err))
		return
	}
	w.Header().Set("ETag", httpx.ETag(v.RowVersion))
	w.Header().Set("Cache-Control", "no-store")
	httpx.WriteJSON(w, http.StatusOK, v)
}

// cancelTenantMove: POST /api/v1/registry/tenants/{id}/move/{moveId}/cancel.
func (s *Server) cancelTenantMove(w http.ResponseWriter, r *http.Request) {
	p, ok := s.requireRole(w, r, true, auth.RolePlatformAdmin)
	if !ok || !s.movesReady(w, r) {
		return
	}
	id, mid, err := moveIDs(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	raw, comment, err := optionalComment(r)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	expected, err := httpx.IfMatch(r, modRegistry)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	a := s.actor(r, p)
	rollback := false
	s.idempotentWrite(w, r, a, raw, modRegistry, func(ctx context.Context, tx pgx.Tx) (*writeOut, error) {
		res, err := s.Moves.Cancel(ctx, tx, a, id, mid, expected, comment)
		if err != nil {
			return nil, moveErr(err)
		}
		rollback = res.Rollback
		rv := res.View.RowVersion
		return &writeOut{code: http.StatusOK, body: res.View, etag: &rv}, nil
	})
	// Quay lui chạy nền sau khi cam kết; saga đang chạy (nếu có) thấy cờ huỷ ở nhịp kiểm kế tiếp.
	if rollback && s.MoveRunner != nil {
		s.MoveRunner.Start(r.Context(), mid)
	}
}
