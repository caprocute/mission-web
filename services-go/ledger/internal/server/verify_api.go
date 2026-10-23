package server

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"slices"
	"strconv"
	"time"

	"github.com/google/uuid"

	"mission/services-go/ledger/internal/auth"
	"mission/services-go/ledger/internal/export"
	"mission/services-go/ledger/internal/verify"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/problem"
)

// Auditor ghi một sự kiện kiểm toán sys.audit.v1 vào sổ cái ("mọi lần tra cứu được ghi sổ cái", SCR-1302).
type Auditor interface {
	Audit(ctx context.Context, tenant uuid.UUID, actor *uuid.UUID, operation string, subject uuid.UUID, detail map[string]any) error
}

// API kiểm tra toàn vẹn (oversight.yaml: listLedgerVerifyRuns, createLedgerVerifyRun, getLedgerVerifyRun) và
// GET /api/v1/jobs/{id} cho tác vụ dài của ledger-service.
func (s *Server) registerVerifyRoutes() {
	if s.deps.Auth != nil && (s.deps.Verify != nil || s.deps.Export != nil) {
		s.mux.HandleFunc("GET /api/v1/jobs/{id}", s.authed(nil, s.getJob))
	}
	if s.deps.Verify == nil || s.deps.Auth == nil {
		return
	}
	s.mux.HandleFunc("POST /api/v1/ledger/verify-runs", s.authed([]string{auth.RoleInspector}, s.createVerifyRun))
	s.mux.HandleFunc("GET /api/v1/ledger/verify-runs", s.authed([]string{auth.RoleInspector, auth.RoleAppraiser}, s.listVerifyRuns))
	s.mux.HandleFunc("GET /api/v1/ledger/verify-runs/{id}", s.authed([]string{auth.RoleInspector, auth.RoleAppraiser}, s.getVerifyRun))
}

type caller struct {
	p      *auth.Principal
	tenant uuid.UUID
	actor  *uuid.UUID
}

// authed kiểm token, vai trò theo x-mission.roles của hợp đồng và tenant UUID trong token. Phạm vi đơn vị
// (Đv của appraiser) thuộc OPA, chưa nối ở ledger (điểm chờ NEN-014): appraiser xem các lần kiểm tra của
// tenant vì lần kiểm tra không gắn đơn vị (suy luận).
func (s *Server) authed(roles []string, h func(http.ResponseWriter, *http.Request, caller)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		trace := otel.TraceIDFromContext(r.Context())
		p, err := s.deps.Auth.Verify(r.Context(), auth.BearerToken(r))
		if err != nil {
			problem.Write(w, problem.Unauthorized("", r.URL.Path, trace))
			return
		}
		if len(roles) > 0 && !p.HasAnyRole(roles...) {
			problem.Write(w, problem.Forbidden("", r.URL.Path, trace))
			return
		}
		t, err := uuid.Parse(p.Tenant)
		if err != nil {
			problem.Write(w, problem.OutOfScope("", r.URL.Path, trace))
			return
		}
		c := caller{p: p, tenant: t}
		if a, err := uuid.Parse(p.Subject); err == nil {
			c.actor = &a
		}
		h(w, r.WithContext(auth.WithPrincipal(r.Context(), p)), c)
	}
}

// TenantHeader là header tenant đích của lời gọi dịch vụ tới dịch vụ (wiki trang 14 mục 42).
const TenantHeader = "X-Tenant-Id"

// authedOrService như authed, thêm tài khoản dịch vụ của client thuộc DelegatingClients: token không có
// claim tenant nên tenant đích đọc từ X-Tenant-Id; claim tenant trong token (nếu có) luôn thắng header.
func (s *Server) authedOrService(roles []string, h func(http.ResponseWriter, *http.Request, caller)) http.HandlerFunc {
	user := s.authed(roles, h)
	return func(w http.ResponseWriter, r *http.Request) {
		trace := otel.TraceIDFromContext(r.Context())
		p, err := s.deps.Auth.Verify(r.Context(), auth.BearerToken(r))
		if err != nil || !p.ServiceAccount() || !slices.Contains(s.deps.DelegatingClients, p.ClientID) {
			user(w, r)
			return
		}
		raw := p.Tenant
		if raw == "" {
			raw = r.Header.Get(TenantHeader)
		}
		t, err := uuid.Parse(raw)
		if err != nil {
			problem.Write(w, problem.OutOfScope("", r.URL.Path, trace))
			return
		}
		// Tài khoản dịch vụ không phải người: actor để trống, kiểm toán ghi client ở detail.
		h(w, r.WithContext(auth.WithPrincipal(r.Context(), p)), caller{p: p, tenant: t})
	}
}

func (s *Server) audit(r *http.Request, c caller, op string, subject uuid.UUID, detail map[string]any) {
	if s.deps.Auditor == nil {
		return
	}
	if err := s.deps.Auditor.Audit(r.Context(), c.tenant, c.actor, op, subject, detail); err != nil {
		// Không chặn thao tác của cán bộ kiểm tra khi chuỗi kiểm toán bị khoá (suy luận); nhật ký vận hành vẫn ghi.
		s.deps.Log.Warn("không ghi được sự kiện kiểm toán vào sổ cái", "operation", op, "err", err.Error())
	}
}

type createBody struct {
	VerifyKind     string     `json:"verifyKind"`
	ChainPartition *int       `json:"chainPartition"`
	RangeFrom      *time.Time `json:"rangeFrom"`
	RangeTo        *time.Time `json:"rangeTo"`
}

func validationError(r *http.Request, field, msg string) problem.ProblemDetails {
	return problem.ValidationError([]problem.FieldErrorItem{{Field: field, Message: msg}}, r.URL.Path, otel.TraceIDFromContext(r.Context()))
}

func (s *Server) createVerifyRun(w http.ResponseWriter, r *http.Request, c caller) {
	key := r.Header.Get("Idempotency-Key")
	if key == "" || len(key) > 128 {
		problem.Write(w, validationError(r, "Idempotency-Key", "Thiếu khoá chống ghi trùng."))
		return
	}
	var b createBody
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&b); err != nil {
		problem.Write(w, validationError(r, "body", "Nội dung yêu cầu không hợp lệ."))
		return
	}
	req := verify.Request{TenantID: c.tenant, Kind: b.VerifyKind}
	switch b.VerifyKind {
	case verify.KindHead, verify.KindSeal, verify.KindFull, verify.KindArchive:
	default:
		problem.Write(w, validationError(r, "verifyKind", "Loại kiểm tra không hợp lệ."))
		return
	}
	if b.ChainPartition != nil {
		if *b.ChainPartition < 0 || *b.ChainPartition > 63 {
			problem.Write(w, validationError(r, "chainPartition", "Phân vùng phải từ 0 đến 63."))
			return
		}
		p := int16(*b.ChainPartition)
		req.Chain = &p
	}
	if b.RangeFrom != nil {
		req.From = b.RangeFrom.UTC()
	}
	if b.RangeTo != nil {
		req.To = b.RangeTo.UTC()
	}
	if !req.From.IsZero() && !req.To.IsZero() {
		if req.From.After(req.To) {
			problem.Write(w, validationError(r, "rangeFrom", "Thời điểm bắt đầu phải trước thời điểm kết thúc."))
			return
		}
		if req.To.Sub(req.From) > 2*366*24*time.Hour {
			problem.Write(w, problem.FromCode("LEDGER-422-RANGE-TOO-LONG", "Khoảng thời gian kiểm tra quá 2 năm.", nil,
				r.URL.Path, otel.TraceIDFromContext(r.Context())))
			return
		}
	}
	run, existing, err := s.deps.Verify.Create(r.Context(), req, c.actor, key)
	if errors.Is(err, verify.ErrIdempotencyMismatch) {
		problem.Write(w, problem.FromCode("COMMON-422-IDEMPOTENCY-MISMATCH", "", nil, r.URL.Path, otel.TraceIDFromContext(r.Context())))
		return
	}
	if err != nil {
		s.deps.Log.Error("không tạo được lần kiểm tra toàn vẹn", "err", err.Error())
		problem.Write(w, problem.InternalServerError(r.URL.Path, otel.TraceIDFromContext(r.Context())))
		return
	}
	if !existing {
		s.audit(r, c, "createLedgerVerifyRun", run.ID, map[string]any{"verifyKind": run.Kind})
	}
	w.Header().Set("Location", "/api/v1/jobs/"+jobID(run.ID))
	w.Header().Set("Retry-After", "2")
	writeJSON(w, http.StatusAccepted, jobView(run))
}

func (s *Server) getVerifyRun(w http.ResponseWriter, r *http.Request, c caller) {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		problem.Write(w, problem.NotFound("", r.URL.Path, otel.TraceIDFromContext(r.Context())))
		return
	}
	run, err := s.deps.Verify.Get(r.Context(), c.tenant, id)
	if errors.Is(err, verify.ErrNotFound) {
		problem.Write(w, problem.NotFound("", r.URL.Path, otel.TraceIDFromContext(r.Context())))
		return
	}
	if err != nil {
		problem.Write(w, problem.InternalServerError(r.URL.Path, otel.TraceIDFromContext(r.Context())))
		return
	}
	s.audit(r, c, "getLedgerVerifyRun", run.ID, nil)
	writeJSON(w, http.StatusOK, runView(run))
}

func (s *Server) listVerifyRuns(w http.ResponseWriter, r *http.Request, c caller) {
	q := r.URL.Query()
	kind := q.Get("verifyKind")
	switch kind {
	case "", verify.KindHead, verify.KindSeal, verify.KindFull, verify.KindArchive:
	default:
		problem.Write(w, validationError(r, "verifyKind", "Loại kiểm tra không hợp lệ."))
		return
	}
	limit := 20
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 200 {
			problem.Write(w, validationError(r, "limit", "Số dòng mỗi trang từ 1 đến 200."))
			return
		}
		limit = n
	}
	runs, next, err := s.deps.Verify.List(r.Context(), c.tenant, kind, limit, q.Get("cursor"))
	if errors.Is(err, verify.ErrBadCursor) {
		problem.Write(w, validationError(r, "cursor", "Con trỏ trang không hợp lệ."))
		return
	}
	if err != nil {
		problem.Write(w, problem.InternalServerError(r.URL.Path, otel.TraceIDFromContext(r.Context())))
		return
	}
	s.audit(r, c, "listLedgerVerifyRuns", uuid.NewSHA1(c.tenant, []byte("listLedgerVerifyRuns")), map[string]any{"verifyKind": kind})
	items := make([]map[string]any, len(runs))
	for i, run := range runs {
		items[i] = runView(run)
	}
	var nc any
	if next != "" {
		nc = next
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "nextCursor": nc})
}

// getJob: chỉ người khởi tạo xem được; người khác (và lần chạy theo lịch) nhận 404 (jobs.yaml). Mã Job là mã lần
// kiểm tra toàn vẹn hoặc mã tác vụ xuất.
func (s *Server) getJob(w http.ResponseWriter, r *http.Request, c caller) {
	id, err := parseJobID(r.PathValue("id"))
	notFound := func() { problem.Write(w, problem.NotFound("", r.URL.Path, otel.TraceIDFromContext(r.Context()))) }
	if err != nil || c.actor == nil {
		notFound()
		return
	}
	if s.deps.Verify != nil {
		if run, err := s.deps.Verify.Get(r.Context(), c.tenant, id); err == nil {
			if run.RequestedBy == nil || *run.RequestedBy != *c.actor {
				notFound()
				return
			}
			if run.Status == verify.StatusPending || run.Status == verify.StatusRunning {
				w.Header().Set("Retry-After", "2")
			}
			writeJSON(w, http.StatusOK, jobView(run))
			return
		}
	}
	if s.deps.Export != nil {
		if j, err := s.deps.Export.Get(r.Context(), c.tenant, id); err == nil && j.RequestedBy == *c.actor {
			if j.Status == export.StatusPending || j.Status == export.StatusRunning {
				w.Header().Set("Retry-After", "2")
			}
			writeJSON(w, http.StatusOK, s.exportJobView(r, j))
			return
		}
	}
	notFound()
}

func jobView(run *verify.Run) map[string]any {
	progress := map[string]int{verify.StatusPending: 0, verify.StatusRunning: 50}[run.Status]
	if run.Status == verify.StatusDone || run.Status == verify.StatusFailed {
		progress = 100
	}
	v := map[string]any{"id": jobID(run.ID), "type": verify.JobType, "status": run.Status, "progress": progress,
		"updatedAt": run.StartedAt.Format(time.RFC3339Nano)}
	if run.FinishedAt != nil {
		v["updatedAt"] = run.FinishedAt.Format(time.RFC3339Nano)
	}
	if run.Detail != "" {
		v["message"] = run.Detail
	}
	if run.Status == verify.StatusDone {
		v["resultLocation"] = "/api/v1/ledger/verify-runs/" + run.ID.String()
	}
	if run.Status == verify.StatusFailed {
		v["problem"] = problem.InternalServerError("/api/v1/jobs/"+jobID(run.ID), "")
	}
	return v
}

func runView(run *verify.Run) map[string]any {
	v := map[string]any{
		"id": run.ID.String(), "verifyKind": run.Kind, "chainPartition": nil,
		"rangeFrom": run.From.Format(time.RFC3339Nano), "rangeTo": run.To.Format(time.RFC3339Nano),
		"entriesChecked": run.EntriesChecked, "sealsChecked": run.SealsChecked, "partitionsTotal": run.PartitionsTotal,
		"partitionsMatched": run.PartitionsMatched, "mismatches": run.Mismatches, "firstMismatch": nil,
		"alertId": nil, "startedAt": run.StartedAt.Format(time.RFC3339Nano), "finishedAt": nil,
	}
	if run.Chain != nil {
		v["chainPartition"] = *run.Chain
	}
	if run.AlertID != nil {
		v["alertId"] = run.AlertID.String()
	}
	if run.FinishedAt != nil {
		v["finishedAt"] = run.FinishedAt.Format(time.RFC3339Nano)
	}
	if f := run.First; f != nil {
		m := map[string]any{}
		if f.ChainPartition != nil {
			m["chainPartition"] = *f.ChainPartition
		}
		if f.SeqNo != nil {
			m["seqNo"] = *f.SeqNo
		}
		if f.EntryID != nil {
			m["entryId"] = f.EntryID.String()
		}
		if f.StoredHash != "" {
			m["storedHash"] = f.StoredHash
		}
		if f.RecomputedHash != "" {
			m["recomputedHash"] = f.RecomputedHash
		}
		if f.SealID != nil {
			m["sealId"] = f.SealID.String()
		}
		if f.AnchorMatches != nil {
			m["anchorMatches"] = *f.AnchorMatches
		}
		v["firstMismatch"] = m
	}
	if len(run.Links) > 0 {
		v["chain"] = run.Links
	}
	return v
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
