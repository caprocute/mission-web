"""OCR minh chứng (DV-14-05, QT-07, SCR-0701/0702 "Đọc văn bản"). extractEvidenceFields: tải tệp người nộp vừa tải
lên qua lõi bằng token chỉ đọc của chính người đó, OCR, trích số ký hiệu, ngày ban hành, trích yếu, gợi ý nhiệm vụ
đang mở khớp nhất. ocrEvidenceObject: tác vụ nội bộ cho evidence-service, search-indexer bóc chữ tệp C1, C2.
Không đọc, không gửi C3 tới OCR (BR-17-07); nhật ký gợi ý không lưu văn bản OCR, chỉ lưu các ô trích được đã che."""

from __future__ import annotations

import hashlib
import json
import uuid
from datetime import UTC, datetime
from typing import Any

import httpx

from app.assist.service import Done
from app.core.auth import Principal
from app.core.core_client import CoreClient
from app.guard import rules
from app.ocr import fields as fx
from app.ocr.client import Ocr, Unreadable
from app.rag.ingest import MAX_ATTACHMENT
from app.store.suggestions import LABEL, Entry, SuggestionStore

USE_CASE = "OCR"
TEMPLATE = "dv14-05.1"
OPEN = "DA_GIAO,DANG_THUC_HIEN"
_MIME = {"application/pdf", "image/jpeg", "image/png"}


class C3Refused(Exception):
    pass


class TooLarge(ValueError):
    pass


def _mime(media: str, data: bytes) -> str:
    if media in _MIME:
        return media
    if data[:5] == b"%PDF-":
        return "application/pdf"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    raise Unreadable("chỉ nhận PDF, JPEG, PNG")


class EvidenceOcr:
    def __init__(self, ocr: Ocr, core: CoreClient, store: SuggestionStore, objects: httpx.AsyncClient | None) -> None:
        self.ocr = ocr
        self.core = core
        self.store = store
        self.objects = objects

    async def _tasks(self, p: Principal) -> list[dict[str, Any]]:
        try:
            page = await self.core.get_json(p, "/api/v1/tasks/mine", {"status": OPEN, "limit": 200})
        except (httpx.HTTPError, ValueError):
            return []
        return [x for x in page.get("items") or [] if isinstance(x, dict) and x.get("id")]

    async def extract(self, p: Principal, body: dict[str, Any]) -> Done:
        oid = str(uuid.UUID(body["objectId"]))
        meta = await self.core.get_json(p, f"/api/v1/files/{oid}/download-url")
        if meta.get("e2ee"):  # minh chứng C3 mã hoá đầu cuối: không tải, không gửi OCR
            raise C3Refused
        if int(meta.get("sizeBytes") or 0) > MAX_ATTACHMENT:
            raise TooLarge
        try:
            data = await self.core.fetch_signed(str(meta["url"]), MAX_ATTACHMENT)
        except ValueError as e:
            raise TooLarge from e
        if meta.get("sha256") and hashlib.sha256(data).hexdigest() != meta["sha256"]:
            raise Unreadable("tệp tải về không khớp mã băm")
        res = await self.ocr.read(data, _mime(str(meta.get("mediaType") or ""), data))
        text = res.text
        f = fx.extract(text)
        subject = f.doc_summary or text[:400]
        best, alts = fx.match(subject, await self._tasks(p))
        out = {"docNumber": f.doc_number, "docDate": f.doc_date,
               "docSummary": rules.mask_pii(f.doc_summary).text if f.doc_summary else None,
               "matchedTask": best, "alternatives": alts}
        entry = Entry(tenant_id=p.tenant_id, user_id=p.user_id, use_case=USE_CASE, model=res.model,
                  prompt_template_version=TEMPLATE, prompt=f"[ocr] {oid}",
                  output_text=json.dumps(out, ensure_ascii=False), kind="MINH_CHUNG", target_ref=oid,
                  confidence=best["confidence"] if best else None,
                  sources=[{"kind": "VAN_BAN", "title": "Tệp minh chứng đã tải lên", "ref": oid}], redact_output=False)
        await self.store.log(entry)
        return Done({"suggestionId": entry.id, "objectId": oid, "model": res.model, "aiGenerated": True, "label": LABEL,
                     **out})

    async def ocr_object(self, body: dict[str, Any]) -> dict[str, Any]:
        if body.get("classification") not in ("C1", "C2"):
            raise C3Refused
        if self.objects is None:
            raise Unreadable("chưa cấu hình kho đối tượng")
        key = str(body["objectKey"])
        r = await self.objects.get("/" + key.lstrip("/"))
        r.raise_for_status()
        data = r.content
        if len(data) > MAX_ATTACHMENT:
            raise TooLarge
        if hashlib.sha256(data).hexdigest() != body["sha256"]:
            raise Unreadable("tệp không khớp mã băm")
        res = await self.ocr.read(data, str(body["mimeType"]))
        return {"objectKey": key, "sha256": body["sha256"], "modelId": res.model,
                "processedAt": datetime.now(UTC).isoformat(), "fullText": res.text,
                "pages": [{"pageNumber": pg.page_number, "text": pg.text, "confidence": round(pg.confidence, 4)}
                          for pg in res.pages]}
