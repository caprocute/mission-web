package server

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"time"

	"github.com/google/uuid"

	"mission/services-go/ledger/internal/auth"
	"mission/services-go/ledger/internal/canon"
	"mission/services-go/ledger/internal/export"
	"vn.viettel.okrkpi/services-go/vtplatform/problem"
)

// ExportJobType là loại Job của tác vụ xuất (common.yaml Job.type: EXPORT).
const ExportJobType = "EXPORT"

// createLedgerExport (oversight.yaml): 202 + Location /api/v1/jobs/{id}, Idempotency-Key bắt buộc (DV-09-05).
func (s *Server) registerExportRoutes() {
	if s.deps.Export == nil || s.deps.Auth == nil {
		return
	}
	s.mux.HandleFunc("POST /api/v1/ledger/exports", s.authed([]string{auth.RoleInspector}, s.createLedgerExport))
}

type exportBody struct {
	AggregateType      string     `json:"aggregateType"`
	AggregateID        *uuid.UUID `json:"aggregateId"`
	EventType          string     `json:"eventType"`
	From               *time.Time `json:"from"`
	To                 *time.Time `json:"to"`
	IncludeVerifyRunID *uuid.UUID `json:"includeVerifyRunId"`
}

func (s *Server) createLedgerExport(w http.ResponseWriter, r *http.Request, c caller) {
	key := r.Header.Get("Idempotency-Key")
	if key == "" || len(key) > 128 {
		problem.Write(w, validationError(r, "Idempotency-Key", "Thiếu khoá chống ghi trùng."))
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 16<<10))
	var b exportBody
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	if err != nil || dec.Decode(&b) != nil {
		problem.Write(w, validationError(r, "body", "Nội dung yêu cầu không hợp lệ."))
		return
	}
	switch {
	case b.From == nil:
		problem.Write(w, validationError(r, "from", "Cần thời điểm bắt đầu."))
		return
	case b.To == nil:
		problem.Write(w, validationError(r, "to", "Cần thời điểm kết thúc."))
		return
	case b.From.After(*b.To):
		problem.Write(w, validationError(r, "from", "Thời điểm bắt đầu phải trước thời điểm kết thúc."))
		return
	case len(b.AggregateType) > 32:
		problem.Write(w, validationError(r, "aggregateType", "Loại đối tượng dài quá 32 ký tự."))
		return
	case len(b.EventType) > 64:
		problem.Write(w, validationError(r, "eventType", "Loại sự kiện dài quá 64 ký tự."))
		return
	case b.To.Sub(*b.From) > 2*366*24*time.Hour:
		problem.Write(w, problem.FromCode("LEDGER-422-RANGE-TOO-LONG", "Khoảng thời gian xuất quá 2 năm.", nil, r.URL.Path, trace(r)))
		return
	}
	if c.actor == nil {
		problem.Write(w, problem.Forbidden("", r.URL.Path, trace(r)))
		return
	}
	canonical, err := canon.CanonicalizeJSON(raw)
	if err != nil {
		problem.Write(w, validationError(r, "body", "Nội dung yêu cầu không hợp lệ."))
		return
	}
	req := export.Request{AggregateType: b.AggregateType, AggregateID: b.AggregateID, EventType: b.EventType,
		From: b.From.UTC(), To: b.To.UTC(), IncludeVerifyRunID: b.IncludeVerifyRunID}
	job, existing, err := s.deps.Export.Create(r.Context(), c.tenant, *c.actor, key, req, sha256.Sum256(canonical))
	if errors.Is(err, export.ErrIdempotencyMismatch) {
		problem.Write(w, problem.FromCode("COMMON-422-IDEMPOTENCY-MISMATCH", "", nil, r.URL.Path, trace(r)))
		return
	}
	if err != nil {
		s.deps.Log.Error("không tạo được tác vụ xuất bằng chứng", "err", err.Error())
		problem.Write(w, problem.InternalServerError(r.URL.Path, trace(r)))
		return
	}
	if !existing {
		detail := map[string]any{"from": canon.FormatTimestamp(req.From), "to": canon.FormatTimestamp(req.To)}
		subject := job.ID
		if req.AggregateID != nil {
			subject = *req.AggregateID
			detail["aggregateId"] = req.AggregateID.String()
		}
		s.audit(r, c, "createLedgerExport", subject, detail)
	}
	w.Header().Set("Location", "/api/v1/jobs/"+job.ID.String())
	w.Header().Set("Retry-After", "2")
	writeJSON(w, http.StatusAccepted, s.exportJobView(r, job))
}

func (s *Server) exportJobView(r *http.Request, j *export.Job) map[string]any {
	progress := map[string]int{export.StatusPending: 0, export.StatusRunning: 50}[j.Status]
	if j.Status == export.StatusDone || j.Status == export.StatusFailed {
		progress = 100
	}
	v := map[string]any{"id": j.ID.String(), "type": ExportJobType, "status": j.Status, "progress": progress,
		"updatedAt": j.CreatedAt.UTC().Format(time.RFC3339Nano)}
	if j.FinishedAt != nil {
		v["updatedAt"] = j.FinishedAt.UTC().Format(time.RFC3339Nano)
	}
	if j.Detail != "" {
		v["message"] = j.Detail
	}
	if j.Status == export.StatusDone {
		if loc, err := s.deps.Export.Location(r.Context(), j); err == nil && loc != "" {
			v["resultLocation"] = loc
		}
	}
	if j.Status == export.StatusFailed {
		v["problem"] = problem.InternalServerError("/api/v1/jobs/"+j.ID.String(), "")
	}
	return v
}
