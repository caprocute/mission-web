"""Các ca dùng trợ lý tầng 1 và 2 (DV-14-04, QT-17, QT-02, SCR-1701, SCR-0202): soạn mục tiêu và KR bám căn cứ,
soát KR, hỏi đáp quy định, giải thích điểm số, tóm tắt văn bản. Ngữ cảnh đọc qua API lõi bằng token đổi chỉ đọc
của chính người dùng và RAG đã lọc ACL; Gateway lo rào chắn, hàng đợi, nhật ký. Mỗi gợi ý con có mã riêng trong
ai.suggestion_log để DV-14-06 ghi quyết định dùng, sửa hay bỏ. Ai-gateway không ghi gì vào schema nghiệp vụ."""

from __future__ import annotations

import json
import logging
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import httpx

from app.assist import review as rv
from app.core.auth import Principal
from app.core.core_client import C3ForbiddenError, CoreClient
from app.core.pipeline import Accepted, ContextChunk, Gateway, SuggestionRequest
from app.llm.client import Message
from app.llm.fake_json import schema_instruction
from app.rag.ingest import MAX_ATTACHMENT, AttachmentError, Indexer, document_text
from app.rag.retriever import Retriever
from app.store.suggestions import LABEL, Entry, SuggestionStore

log = logging.getLogger(__name__)

TEMPLATE = "dv14-04.1"
MAX_OBJECTIVES = 3  # BR-17-06
MAX_SUGGESTIONS = 10
DUPLICATE_AT = 0.5

ROLE = ("Đồng chí là trợ lý soạn thảo của nền tảng giao nhiệm vụ, quản lý OKR/KPI cho cơ quan Đảng, Nhà nước. "
        "Trả lời bằng tiếng Việt có dấu, văn phong hành chính, ngắn gọn. Chỉ dựa trên tài liệu tham khảo và dữ liệu "
        "được cung cấp; không bịa số liệu, không chấm điểm, không đề xuất xếp loại, không nhận xét cán bộ.")


class UnsupportedUseCase(ValueError):
    """useCase chưa được hỗ trợ ở điểm vào chung."""


@dataclass(frozen=True)
class Done:
    body: dict[str, Any]


def now_iso() -> str:
    return datetime.now(UTC).isoformat()


def _src_of(ctx: list[ContextChunk], ref: Any) -> dict[str, Any] | None:
    if isinstance(ref, str) and ref.startswith("[#") and ref.endswith("]") and ref[2:-1].isdigit():
        i = int(ref[2:-1])
        return ctx[i - 1].source if 1 <= i <= len(ctx) else None
    return next((c.source for c in ctx if c.ref == ref), None)


class AssistService:
    def __init__(self, gateway: Gateway, store: SuggestionStore, core: CoreClient, retriever: Retriever,
                 indexer: Indexer) -> None:
        self.gw = gateway
        self.store = store
        self.core = core
        self.retriever = retriever
        self.indexer = indexer

    # ───────────── ngữ cảnh qua API lõi ─────────────
    async def _core(self, p: Principal, path: str, params: dict[str, Any] | None = None) -> Any:
        """Đọc lõi; lỗi quyền, không tìm thấy hay lõi gián đoạn thì bỏ phần ngữ cảnh đó (gợi ý vẫn chạy)."""
        try:
            return await self.core.get_json(p, path, params)
        except C3ForbiddenError:
            raise
        except (httpx.HTTPError, ValueError) as e:
            log.info("không đọc được ngữ cảnh lõi", extra={"path": path.split("?")[0], "err": type(e).__name__})
            return None

    async def _policy(self, p: Principal, period_id: str, objective_id: str | None) -> rv.Policy:
        params = {"periodId": period_id, **({"objective": objective_id} if objective_id else {})}
        return rv.Policy.from_context(await self._core(p, "/api/v1/okr/compose-context", params))

    async def _guide(self, p: Principal, query: str, k: int = 4) -> list[ContextChunk]:
        try:
            return await self.retriever.retrieve(p, query, k=k)
        except Exception as e:
            log.warning("truy xuất RAG thất bại", extra={"err": type(e).__name__})
            return []

    async def _log_children(self, p: Principal, parent: dict[str, Any], use_case: str,
                            items: list[tuple[str, dict[str, Any], list[dict[str, Any]], str | None]]) -> None:
        """Mỗi gợi ý con (kind, nội dung, nguồn, mã đối tượng) thành một dòng nhật ký có mã riêng."""
        entries = []
        for kind, body, sources, target in items:
            e = Entry(tenant_id=p.tenant_id, user_id=p.user_id, use_case=use_case, model=str(parent["model"]),
                      prompt_template_version=TEMPLATE, prompt=f"[parent] {parent.get('id', '')}",
                      output_text=json.dumps(body, ensure_ascii=False), sources=sources, kind=kind,
                      target_ref=target, redact_output=False)
            body["id"] = e.id
            entries.append(e)
        await self.store.log_many(entries)

    # ───────────── reviewOkrKeyResults ─────────────
    async def review_okr(self, p: Principal, body: dict[str, Any]) -> Done | Accepted:
        policy = await self._policy(p, body["periodId"], body.get("objectiveId"))
        krs = body["keyResults"]
        findings: list[rv.Finding] = []
        for i, kr in enumerate(krs, 1):
            findings += rv.review(str(kr.get("code") or f"KR {i}"), kr, policy)
        guide = await self._guide(p, "cách viết kết quả then chốt đo được, có chỉ tiêu và đơn vị đo")
        model = self.gw.queue.llm.model
        need = [f for f in findings if f.rule == "BR-02-05"]
        if need:
            schema = {"type": "object", "required": ["items"], "properties": {"items": {"type": "array", "items": {
                "type": "object", "required": ["krCode", "rewrites"], "properties": {
                    "krCode": {"type": "string"},
                    "rewrites": {"type": "array", "maxItems": 3, "items": {"type": "string", "maxLength": 300}},
                    "sources": {"type": "array", "items": {"type": "string"}}}}}}}
            lines = "\n".join(f"- {f.kr_code}: {next((k.get('title') for j, k in enumerate(krs, 1) if str(k.get('code') or f'KR {j}') == f.kr_code), '')}"  # noqa: E501
                              for f in need)
            req = SuggestionRequest(
                use_case="SOAT_KR", template_version=TEMPLATE, context=guide, json_mode=True, output_schema=schema,
                target_ref=body.get("objectiveId"), require_sources=False, max_tokens=700,
                messages=[Message("system", ROLE + " Viết lại mỗi KR dưới đây thành tối đa 3 phương án có chỉ tiêu "
                                  "bằng số, đơn vị đo và thời hạn; trích nguồn [#n] trong trường sources nếu dùng tài "
                                  "liệu.\n" + schema_instruction(schema)),
                          Message("user", f"Mục tiêu: {body['objectiveTitle']}\nCác KR cần viết lại:\n{lines}")])
            res = await self.gw.suggest(p, req)
            if isinstance(res, Accepted):
                return res
            model = str(res["model"])
            if not res.get("blocked") and res.get("outputText"):
                try:
                    items = json.loads(res["outputText"]).get("items") or []
                except ValueError:
                    items = []
                by = {str(x.get("krCode")): x for x in items if isinstance(x, dict)}
                for f in need:
                    got = by.get(f.kr_code) or (items[need.index(f)] if need.index(f) < len(items) else {})
                    f.rewrites = [r for r in (got.get("rewrites") or []) if isinstance(r, str)
                                  and rv.acceptable_rewrite(r, policy)][:3]
        src = [guide[0].source] if guide else []
        out: list[dict[str, Any]] = []
        for f in findings:
            out.append({"suggestionId": "", "krCode": f.kr_code, "severity": f.severity, "rule": f.rule, "msg": f.msg,
                        "message": f.message, "rewrites": f.rewrites, "sources": src})
        await self._log_children(p, {"model": model}, "SOAT_KR",
                                 [(str(x["rule"]), x, list(x["sources"]), body.get("objectiveId")) for x in out])
        for x in out:
            x["suggestionId"] = x.pop("id")
        return Done({"model": model, "createdAt": now_iso(), "aiGenerated": True, "label": LABEL, "findings": out})

    # ───────────── assistOkrDraft ─────────────
    async def assist_okr(self, p: Principal, body: dict[str, Any]) -> Done | Accepted:
        period, unit = body["periodId"], body["unitId"]
        bases: list[dict[str, Any]] = []
        if body.get("basisIds"):
            page = await self._core(p, "/api/v1/okr/bases", {"limit": 200}) or {}
            want = set(body["basisIds"])
            bases = [b for b in page.get("items") or [] if isinstance(b, dict) and b.get("id") in want]
        drafts = [d for d in [await self._core(p, f"/api/v1/okr/objectives/{uuid.UUID(i)}")
                              for i in (body.get("draftObjectiveIds") or [])[:5]] if isinstance(d, dict)]
        for oid in (body.get("attachmentObjectIds") or [])[:3]:
            try:
                name, data = await self.core.download(p, str(uuid.UUID(oid)), MAX_ATTACHMENT)
                await self.indexer.index_attachment(p, name, data)
            except (httpx.HTTPError, ValueError, AttachmentError, KeyError) as e:
                log.info("không lập chỉ mục được tệp căn cứ", extra={"err": type(e).__name__})
        others = await self._core(p, "/api/v1/okr/key-results", {"periodId": period, "scope": "subtree", "limit": 200})
        own = {d.get("id") for d in drafts}
        other_krs = [k for k in (others or {}).get("items") or []
                     if isinstance(k, dict) and k.get("objectiveId") not in own]
        basis_text = "; ".join(f"{b.get('docNumber') or ''} {b.get('title') or ''}".strip() for b in bases)
        ctx = await self._guide(p, f"{body['question']} {basis_text}".strip(), k=6)
        kr_schema = {"type": "object", "required": ["title"], "properties": {
            "title": {"type": "string", "maxLength": 300}, "unit": {"type": ["string", "null"], "maxLength": 32},
            "baselineValue": {"type": ["number", "null"]}, "targetValue": {"type": ["number", "null"]},
            "weight": {"type": ["number", "null"], "minimum": 0, "maximum": 100}}}
        schema = {"type": "object", "required": ["answer", "objectives"], "properties": {
            "answer": {"type": "string"},
            "objectives": {"type": "array", "maxItems": 10, "items": {
                "type": "object", "required": ["title", "keyResults", "sources"], "properties": {
                    "title": {"type": "string", "maxLength": 300}, "reason": {"type": "string"},
                    "sources": {"type": "array", "items": {"type": "string"}},
                    "keyResults": {"type": "array", "minItems": 1, "maxItems": 5, "items": kr_schema}}}}}}
        draft_text = "\n".join(
            f"- {d.get('code') or ''} {d.get('title') or ''}: "
            + "; ".join(str(k.get("title") or "") for k in d.get("keyResults") or [] if isinstance(k, dict))
            for d in drafts)
        user = (f"{body['question']}\nCăn cứ đã chọn: {basis_text or 'không có'}\n"
                f"Bản nháp đang soạn:\n{draft_text or 'không có'}")
        req = SuggestionRequest(
            use_case="SOAN_OKR", template_version=TEMPLATE, context=ctx, json_mode=True, output_schema=schema,
            max_objectives=MAX_OBJECTIVES, target_ref=unit, max_tokens=1200,
            messages=[Message("system", ROLE + f" Đề xuất không quá {MAX_OBJECTIVES} mục tiêu; mỗi mục tiêu 2 đến 5 KR "
                              "có chỉ tiêu bằng số, đơn vị đo, giá trị ban đầu và trọng số; mỗi mục tiêu phải trích "
                              "nguồn [#n] trong trường sources.\n" + schema_instruction(schema)),
                      Message("user", user)])
        res = await self.gw.suggest(p, req)
        if isinstance(res, Accepted):
            return res
        base = {"model": res["model"], "createdAt": res["createdAt"], "aiGenerated": True, "label": LABEL}
        if res.get("blocked"):
            return Done({**base, "answer": "", "suggestions": [], "blocked": res["blocked"]})
        doc = json.loads(res["outputText"] or "{}")
        policy = await self._policy(p, period, None)
        items: list[tuple[str, dict[str, Any], list[dict[str, Any]], str | None]] = []
        for o in (doc.get("objectives") or [])[:MAX_OBJECTIVES]:
            sources = [s for s in (_src_of(req.context, r) for r in o.get("sources") or []) if s]
            if not sources:  # BR-17-02: gợi ý không có nguồn đối chiếu được bị loại
                continue
            krs = [k for k in o.get("keyResults") or [] if isinstance(k, dict) and k.get("title")][:5]
            rv.normalize_weights(krs)
            obj = {"title": str(o["title"])[:300], "keyResults": krs}
            if bases:
                obj["basisRef"] = str(bases[0].get("id"))
            items.append(("MUC_TIEU", {"kind": "MUC_TIEU", "objective": obj, "reason": str(o.get("reason") or ""),
                                       "sources": sources[:5]}, sources[:5], unit))
            for kr in krs:
                best = max(other_krs, key=lambda x: rv.similarity(kr["title"], str(x.get("title") or "")), default=None)
                if best is not None and rv.similarity(kr["title"], str(best.get("title") or "")) >= DUPLICATE_AT:
                    s = [{"kind": "DU_LIEU", "title": f"{best.get('code') or 'KR'} {best.get('title')}",
                          "ref": str(best.get("id"))}]
                    items.append(("LIEN_KET_NGANG", {
                        "kind": "LIEN_KET_NGANG", "text": f'KR "{kr["title"]}" gần giống KR "{best.get("title")}" '
                        "của đơn vị khác. Cân nhắc liên kết ngang thay vì đặt KR trùng.", "sources": s}, s, unit))
        for d in drafts:
            for i, kr in enumerate([k for k in d.get("keyResults") or [] if isinstance(k, dict)], 1):
                code = str(kr.get("code") or f"KR {i}")
                for f in rv.review(code, kr, policy):
                    s = [{"kind": "DU_LIEU", "title": f"Bản nháp {d.get('code') or ''} {d.get('title') or ''}".strip(),
                          "ref": str(d.get("id"))}]
                    items.append(("SOAT_KR", {"kind": "SOAT_KR", "krCode": code, "text": f.message, "sources": s},
                                  s, str(d.get("id"))))
        items = items[:MAX_SUGGESTIONS]
        await self._log_children(p, res, "SOAN_OKR", items)
        return Done({**base, "answer": str(doc.get("answer") or ""), "suggestions": [b for _, b, _, _ in items],
                     "blocked": None})

    # ───────────── requestAiSuggestion ─────────────
    async def ask(self, p: Principal, body: dict[str, Any]) -> Done | Accepted:
        use_case = body["useCase"]
        refs = [r for r in body.get("contextRefs") or [] if isinstance(r, dict)]
        extra: list[ContextChunk] = []
        if use_case == "HOI_DAP_QUY_DINH":
            task = "Trả lời câu hỏi về quy định, hướng dẫn; trích nguồn [#n] sau mỗi ý dùng tài liệu."
        elif use_case == "GIAI_THICH_DIEM":
            task = ("Giải thích cách điểm số đã được tính từ vết tính và quy định, không đưa ra điểm hay mức xếp loại "
                    "mới; trích nguồn [#n].")
            for r in refs:
                if r.get("type") != "SCORE":
                    continue
                pid = str(uuid.UUID(r["id"]))
                trace = await self._core(p, f"/api/v1/appraisals/profiles/{pid}/score-trace")
                if isinstance(trace, dict):
                    steps = "\n".join(
                        f"Bước {s.get('stepNo')}: {s.get('explanation') or s.get('ruleCode')} "
                        f"(vào {json.dumps(s.get('inputs'), ensure_ascii=False)}, "
                        f"ra {json.dumps(s.get('output'), ensure_ascii=False)})"
                        for s in trace.get("steps") or [] if isinstance(s, dict))[:6000]
                    extra.append(ContextChunk(ref=pid, text=f"Vết tính điểm theo {trace.get('policyVersion') or ''}:\n"
                                              + steps, source={"kind": "DU_LIEU", "title": "Vết tính điểm",
                                                               "ref": pid}))
        elif use_case == "TOM_TAT_VAN_BAN":
            task = "Tóm tắt văn bản thành 3 đến 5 ý ngắn, trích nguồn [#n]."
            for r in refs:
                if r.get("type") not in ("SOURCE_DOC", "VAN_BAN"):
                    continue
                did = str(uuid.UUID(r["id"]))
                doc = await self._core(p, f"/api/v1/tasks/source-docs/{did}")
                if isinstance(doc, dict):
                    extra.append(ContextChunk(ref=did, text=document_text(doc)[:8000], source={
                        "kind": "VAN_BAN", "title": f"Văn bản {doc.get('docNumber') or ''}".strip(), "ref": did}))
        else:
            raise UnsupportedUseCase(use_case)
        ctx = extra + await self._guide(p, body["question"], k=6 - min(len(extra), 3))
        req = SuggestionRequest(use_case=use_case, template_version=TEMPLATE, context=ctx,
                                require_sources=bool(ctx), max_tokens=700,
                                messages=[Message("system", ROLE + " " + task), Message("user", body["question"])])
        res = await self.gw.suggest(p, req)
        return res if isinstance(res, Accepted) else Done(res)
