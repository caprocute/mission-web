package server

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"

	"mission/services-go/ledger/internal/auth"
	"mission/services-go/ledger/internal/lookup"
	"mission/services-go/ledger/internal/seal"
	"vn.viettel.okrkpi/services-go/vtplatform/otel"
	"vn.viettel.okrkpi/services-go/vtplatform/problem"
)

// API tra cứu sổ cái (oversight.yaml: listLedgerEntries, getLedgerEntry, getLedgerInclusionProof), DV-09-05.
// Mỗi lần tra cứu, xem ghi một sự kiện kiểm toán vào sổ cái (SCR-1302 "Mọi lần tra cứu được ghi sổ cái").
func (s *Server) registerLedgerRoutes() {
	if s.deps.Lookup == nil || s.deps.Auth == nil {
		return
	}
	read := []string{auth.RoleInspector, auth.RoleAppraiser}
	s.mux.HandleFunc("GET /api/v1/ledger/entries", s.authedOrService(read, s.listLedgerEntries))
	s.mux.HandleFunc("GET /api/v1/ledger/entries/{id}", s.authed(read, s.getLedgerEntry))
	s.mux.HandleFunc("GET /api/v1/oversight/ledger/proofs", s.authed([]string{auth.RoleInspector}, s.getLedgerProof))
}

func trace(r *http.Request) string { return otel.TraceIDFromContext(r.Context()) }

func (s *Server) listLedgerEntries(w http.ResponseWriter, r *http.Request, c caller) {
	q := r.URL.Query()
	f := lookup.Filter{AggregateType: q.Get("aggregateType"), EventType: q.Get("eventType"), Cursor: q.Get("cursor")}
	if len(f.AggregateType) > 32 {
		problem.Write(w, validationError(r, "aggregateType", "Loại đối tượng dài quá 32 ký tự."))
		return
	}
	if len(f.EventType) > 64 {
		problem.Write(w, validationError(r, "eventType", "Loại sự kiện dài quá 64 ký tự."))
		return
	}
	if v := q.Get("aggregateId"); v != "" {
		id, err := uuid.Parse(v)
		if err != nil {
			problem.Write(w, validationError(r, "aggregateId", "Mã đối tượng không hợp lệ."))
			return
		}
		f.AggregateID = &id
	}
	for name, dst := range map[string]*time.Time{"from": &f.From, "to": &f.To} {
		if v := q.Get(name); v != "" {
			t, err := time.Parse(time.RFC3339Nano, v)
			if err != nil {
				problem.Write(w, validationError(r, name, "Thời điểm không hợp lệ."))
				return
			}
			*dst = t
		}
	}
	if !f.From.IsZero() && !f.To.IsZero() && f.From.After(f.To) {
		problem.Write(w, validationError(r, "from", "Thời điểm bắt đầu phải trước thời điểm kết thúc."))
		return
	}
	if v := q.Get("chainPartition"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 0 || n > 63 {
			problem.Write(w, validationError(r, "chainPartition", "Phân vùng phải từ 0 đến 63."))
			return
		}
		p := int16(n)
		f.Chain = &p
	}
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > lookup.MaxLimit {
			problem.Write(w, validationError(r, "limit", "Số dòng mỗi trang từ 1 đến 200."))
			return
		}
		f.Limit = n
	}
	items, next, err := s.deps.Lookup.List(r.Context(), c.tenant, f)
	switch {
	case errors.Is(err, lookup.ErrRangeTooLong):
		problem.Write(w, problem.FromCode("LEDGER-422-RANGE-TOO-LONG", "Khoảng thời gian tra cứu quá 2 năm.", nil, r.URL.Path, trace(r)))
		return
	case errors.Is(err, lookup.ErrBadCursor):
		problem.Write(w, validationError(r, "cursor", "Con trỏ trang không hợp lệ."))
		return
	case err != nil:
		s.deps.Log.Error("không tra cứu được sổ cái", "err", err.Error())
		problem.Write(w, problem.InternalServerError(r.URL.Path, trace(r)))
		return
	}
	subject := uuid.NewSHA1(c.tenant, []byte("listLedgerEntries"))
	detail := map[string]any{}
	if f.AggregateID != nil {
		subject = *f.AggregateID
		detail["aggregateId"] = f.AggregateID.String()
	}
	if f.EventType != "" {
		detail["eventType"] = f.EventType
	}
	if c.actor == nil && c.p != nil && c.p.ServiceAccount() {
		detail["serviceClient"] = c.p.ClientID
	}
	s.audit(r, c, "listLedgerEntries", subject, detail)
	out := make([]map[string]any, len(items))
	for i, e := range items {
		out[i] = entryView(e)
	}
	var nc any
	if next != "" {
		nc = next
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out, "nextCursor": nc})
}

func (s *Server) getLedgerEntry(w http.ResponseWriter, r *http.Request, c caller) {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		problem.Write(w, problem.NotFound("", r.URL.Path, trace(r)))
		return
	}
	e, sl, anchors, err := s.deps.Lookup.Get(r.Context(), c.tenant, id)
	if errors.Is(err, lookup.ErrNotFound) {
		problem.Write(w, problem.NotFound("", r.URL.Path, trace(r)))
		return
	}
	if err != nil {
		s.deps.Log.Error("không đọc được bản ghi sổ cái", "err", err.Error())
		problem.Write(w, problem.InternalServerError(r.URL.Path, trace(r)))
		return
	}
	s.audit(r, c, "getLedgerEntry", e.AggregateID, map[string]any{"entryId": e.ID.String()})
	v := entryView(e)
	v["prevHash"] = hex.EncodeToString(e.PrevHash)
	v["payloadHash"] = hex.EncodeToString(e.PayloadHash)
	v["metadataHash"] = hex.EncodeToString(e.MetadataHash)
	v["seal"] = nil
	v["anchors"] = anchorViews(anchors)
	if sl != nil {
		v["seal"] = sealView(sl)
	}
	writeJSON(w, http.StatusOK, v)
}

func (s *Server) getLedgerProof(w http.ResponseWriter, r *http.Request, c caller) {
	ev, err := uuid.Parse(r.URL.Query().Get("event"))
	if err != nil {
		problem.Write(w, validationError(r, "event", "Mã sự kiện không hợp lệ."))
		return
	}
	p, err := s.deps.Lookup.Proof(r.Context(), c.tenant, ev)
	switch {
	case errors.Is(err, lookup.ErrNotFound):
		problem.Write(w, problem.NotFound("", r.URL.Path, trace(r)))
		return
	case errors.Is(err, lookup.ErrNotSealed):
		problem.Write(w, problem.FromCode("LEDGER-409-NOT-SEALED", "Sự kiện chưa được niêm phong, vui lòng thử lại sau lần niêm phong kế tiếp.",
			nil, r.URL.Path, trace(r)))
		return
	case err != nil:
		s.deps.Log.Error("không dựng được bằng chứng bao hàm", "err", err.Error())
		problem.Write(w, problem.InternalServerError(r.URL.Path, trace(r)))
		return
	}
	s.audit(r, c, "getLedgerInclusionProof", p.Entry.AggregateID, map[string]any{"entryId": p.Entry.ID.String()})
	links := make([]map[string]any, len(p.Links))
	for i, l := range p.Links {
		links[i] = map[string]any{"seqNo": l.SeqNo, "payloadHash": hex.EncodeToString(l.PayloadHash),
			"metadataHash": hex.EncodeToString(l.MetadataHash), "entryHash": hex.EncodeToString(l.EntryHash)}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"entry":            entryView(p.Entry),
		"leafHash":         hex.EncodeToString(p.LeafHash[:]),
		"tenantPath":       stepViews(p.TenantPath),
		"tenantRoot":       hex.EncodeToString(p.Seal.TenantRoot),
		"cellLeafIndex":    p.Seal.CellLeafIndex,
		"cellPath":         stepViews(p.Seal.CellPath),
		"seal":             sealView(&p.Seal),
		"signature":        base64.StdEncoding.EncodeToString(p.Seal.Signature),
		"signerCertSha256": hex.EncodeToString(p.Seal.SignerCertSHA),
		"tsaToken":         base64.StdEncoding.EncodeToString(p.Seal.TSAToken),
		"anchors":          anchorViews(p.Anchors),
		// Mắt chuỗi từ bản ghi tới đầu chuỗi đã niêm phong: lá Merkle là entry_hash cuối của chuỗi (TL-03),
		// nên cần các mắt này để nối bản ghi lên lá (trường bổ sung, wiki/14 mục DV-09-05).
		"chainLinks": links,
	})
}

func entryView(e lookup.Entry) map[string]any {
	v := map[string]any{
		"id": e.ID.String(), "chainPartition": e.Chain, "seqNo": e.SeqNo, "eventId": e.EventID.String(),
		"eventType": e.EventType, "aggregateType": e.AggregateType, "aggregateId": e.AggregateID.String(),
		"actorKind": e.ActorKind, "occurredAt": e.OccurredAt.UTC().Format(time.RFC3339Nano),
		"recordedAt": e.RecordedAt.UTC().Format(time.RFC3339Nano), "entryHash": hex.EncodeToString(e.EntryHash),
		"payloadHash": hex.EncodeToString(e.PayloadHash),
		"metadata":    metadataView(e.Metadata),
	}
	if e.ActorID != nil {
		// Sổ cái không giữ tên người; giao diện tra tên theo mã ở danh bạ (suy luận).
		v["actor"] = map[string]any{"id": e.ActorID.String(), "name": ""}
	}
	return v
}

// metadataView trả siêu dữ liệu rút gọn đã lưu. Không cần che thêm: luồng ghi đã từ chối mọi khoá ngoài danh
// sách C1 cho phép khi sự kiện phân loại C2, C3 (ADR-06), nên sổ cái không có nội dung C2.
func metadataView(m map[string]any) map[string]any {
	if m == nil {
		return map[string]any{}
	}
	return m
}

func sealView(sl *lookup.Seal) map[string]any {
	v := map[string]any{
		"id": sl.ID.String(), "windowStart": sl.WindowStart.UTC().Format(time.RFC3339Nano),
		"windowEnd": sl.WindowEnd.UTC().Format(time.RFC3339Nano), "entryCount": sl.EntryCount,
		"tenantRoot": hex.EncodeToString(sl.TenantRoot), "prevTenantRoot": nil, "cellSealId": sl.CellSealID.String(),
		"cellRoot": hex.EncodeToString(sl.CellRoot), "tsaLate": sl.TSALate,
	}
	if sl.PrevTenantRoot != nil {
		v["prevTenantRoot"] = hex.EncodeToString(sl.PrevTenantRoot)
	}
	if sl.SignatureValid != nil {
		v["signatureValid"] = *sl.SignatureValid
	}
	if sl.TSAGenTime != nil {
		v["tsaGenTime"] = sl.TSAGenTime.UTC().Format(time.RFC3339Nano)
	}
	return v
}

func anchorViews(as []lookup.Anchor) []map[string]any {
	out := make([]map[string]any, len(as))
	for i, a := range as {
		out[i] = map[string]any{"id": a.ID.String(), "anchorTarget": a.Target,
			"receipt": base64.StdEncoding.EncodeToString(a.Receipt), "anchoredAt": a.AnchoredAt.UTC().Format(time.RFC3339Nano)}
	}
	return out
}

func stepViews(steps []seal.Step) []map[string]any {
	out := make([]map[string]any, len(steps))
	for i, st := range steps {
		side := "PHAI"
		if st.Left {
			side = "TRAI"
		}
		out[i] = map[string]any{"side": side, "hash": hex.EncodeToString(st.Sibling[:])}
	}
	return out
}
