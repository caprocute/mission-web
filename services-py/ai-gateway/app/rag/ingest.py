"""Lập chỉ mục kho RAG (DV-14-02): văn bản chỉ đạo nhận qua EVT-24, kho văn bản dùng chung trong data/seed/ai-corpus
và tệp người dùng vừa đính kèm vào câu hỏi. Chỉ tài liệu C0, C1 được nhúng; tài liệu C2, C3 bị bỏ qua và ghi
ai.guardrail_event."""

from __future__ import annotations

import hashlib
import io
import json
import logging
import uuid
import zipfile
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any
from xml.etree import ElementTree

from vtplatform.db import in_tenant_tx
from vtplatform.kafka import InboxGuard, InboxOutcome, IncomingEvent

from app.assist.extract import Extraction, Extractor
from app.core.auth import Principal
from app.core.core_client import C3ForbiddenError, CoreClient
from app.core.service_token import ServiceToken
from app.rag.chunker import chunk_text
from app.rag.embed import Embedder
from app.rag.retriever import header
from app.rag.store import ACL_ALL, SHARED_TENANT, ChunkStore

log = logging.getLogger(__name__)

CONSUMER = "ai-gateway"
TOPIC_EVT24 = "integ.document.received.v1"
MAX_ATTACHMENT = 20 * 1024 * 1024
_W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


class AttachmentError(ValueError):
    """Tệp đính kèm không đọc được hoặc quá lớn."""


@dataclass(frozen=True)
class Indexed:
    source_id: str
    chunks: int


def document_text(doc: dict[str, Any]) -> str:
    """Toàn văn văn bản đến theo lược đồ SourceDoc của tasks.yaml."""
    parts = [doc.get("issuerHeader") or "", doc.get("title") or "", doc.get("preamble") or ""]
    parts += [p.get("text", "") for p in doc.get("paragraphs") or [] if isinstance(p, dict)]
    return "\n".join(x.strip() for x in parts if isinstance(x, str) and x.strip())


def extract_text(filename: str, data: bytes) -> str:
    if len(data) > MAX_ATTACHMENT:
        raise AttachmentError("tệp vượt 20 MB")
    name = filename.lower()
    if name.endswith(".pdf") or data[:5] == b"%PDF-":
        from pypdf import PdfReader

        try:
            reader = PdfReader(io.BytesIO(data))
            return "\n".join((pg.extract_text() or "") for pg in reader.pages)
        except Exception as e:
            raise AttachmentError("không đọc được tệp PDF") from e
    if name.endswith(".docx"):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                info = z.getinfo("word/document.xml")
                if info.file_size > 50 * 1024 * 1024:
                    raise AttachmentError("tệp DOCX bất thường")
                root = ElementTree.fromstring(z.read(info))  # noqa: S314 - expat có chặn mở rộng thực thể
        except (KeyError, zipfile.BadZipFile, ElementTree.ParseError) as e:
            raise AttachmentError("không đọc được tệp DOCX") from e
        paras = ["".join(t.text or "" for t in p.iter(_W + "t")) for p in root.iter(_W + "p")]
        return "\n".join(p for p in paras if p.strip())
    raise AttachmentError("chỉ nhận tệp PDF hoặc DOCX")


class Indexer:
    def __init__(self, pool: Any, store: ChunkStore, embedder: Embedder, core: CoreClient | None = None,
                 service_token: ServiceToken | None = None, extractor: Extractor | None = None) -> None:
        self._pool = pool
        self._store = store
        self._emb = embedder
        self._core = core
        self._token = service_token
        self._inbox = InboxGuard("ai", CONSUMER)
        self.extractor = extractor

    async def _prepare(self, title: str, text: str) -> tuple[list[Any], list[list[float]]]:
        chunks = chunk_text(text)
        chunks = [type(c)(c.no, header(title, c.location) + "\n" + c.text, c.location, c.words) for c in chunks]
        vectors = await self._emb.embed([c.text for c in chunks]) if chunks else []
        return chunks, vectors

    async def index_text(self, *, tenant_id: str, source_type: str, source_id: str, title: str, text: str,
                         acl: list[str], classification: str = "C1") -> Indexed:
        chunks, vectors = await self._prepare(title, text)
        n = await self._store.index(tenant_id=tenant_id, source_type=source_type, source_id=source_id,
                                    chunks=chunks, vectors=vectors, model=self._emb.model, acl=acl,
                                    classification=classification)
        return Indexed(source_id, n)

    async def index_attachment(self, p: Principal, filename: str, data: bytes) -> Indexed:
        """Lập chỉ mục đồng bộ tệp vừa đính kèm vào câu hỏi, chỉ người tải lên đọc được (ACL là mã người dùng)."""
        text = extract_text(filename, data)
        sid = str(uuid.uuid5(uuid.UUID(p.user_id), hashlib.sha256(data).hexdigest()))
        return await self.index_text(tenant_id=p.tenant_id, source_type="TEP_DINH_KEM", source_id=sid,
                                     title=filename[:200], text=text, acl=[p.user_id])

    async def _seen(self, ev: IncomingEvent) -> bool:
        async with in_tenant_tx(self._pool, ev.tenant_id) as tx:
            n = await tx.fetchval("SELECT count(*) FROM ai.inbox_processed WHERE consumer = $1 AND event_id = $2",
                                  CONSUMER, uuid.UUID(ev.event_id))
        return bool(n)

    async def _acl(self, tok: Any, doc: dict[str, Any]) -> list[str]:
        """Đơn vị nhận và các cấp trên của nó (suy luận theo ABAC cây tổ chức). Hợp đồng SourceDoc chưa có mã đơn
        vị nhận; khi thiếu thì mở cho cả tenant (văn bản chỉ đạo C1 nội bộ tenant, ghi ở wiki/14)."""
        units = [u for u in doc.get("recipientUnitIds") or [] if isinstance(u, str)]
        if not units or self._core is None:
            return [ACL_ALL]
        acl: list[str] = []
        for u in units:
            unit = await self._core.get_json_with(tok, f"/api/v1/org/units/{uuid.UUID(u)}")
            acl += [u, *(a["id"] for a in unit.get("ancestors") or [] if isinstance(a, dict) and "id" in a)]
        return list(dict.fromkeys(acl))

    async def handle_document_received(self, ev: IncomingEvent, payload: dict[str, Any]) -> InboxOutcome:
        """EVT-24: đọc toàn văn qua API lõi bằng token ai_readonly, chia đoạn, nhúng, thay đoạn cũ của văn bản."""
        if self._core is None or self._token is None:
            raise RuntimeError("chưa cấu hình API lõi cho consumer EVT-24")
        if await self._seen(ev):
            return InboxOutcome.DUPLICATE_EVENT
        doc_id = str(uuid.UUID(payload["documentId"]))
        tok = await self._token.get()
        skip: str | None = None
        doc: dict[str, Any] = {}
        try:
            doc = await self._core.get_json_with(tok, f"/api/v1/tasks/source-docs/{doc_id}")
            cls = doc.get("classification") or "C1"
            skip = cls if cls not in ("C0", "C1") else None
        except C3ForbiddenError:
            skip = "C3"
        chunks: list[Any] = []
        vectors: list[list[float]] = []
        acl: list[str] = []
        version = int(payload.get("aggregateVersion") or 0)
        ex: Extraction | None = None
        if skip is None:
            title = f"Văn bản {payload.get('docNumber') or ''} của {payload.get('issuer') or ''}".strip()
            chunks, vectors = await self._prepare(title, document_text(doc))
            acl = await self._acl(tok, doc)
            if self.extractor is not None:
                async with in_tenant_tx(self._pool, ev.tenant_id) as tx:
                    await self.extractor.mark(tx, ev.tenant_id, doc_id, version, "DANG_TRICH",
                                              self.extractor.queue.llm.model)
                ex = await self.extractor.extract(tok, ev.tenant_id, doc_id, version, doc, payload)

        async def apply(tx: Any, _: IncomingEvent) -> None:
            if skip is not None:
                now = datetime.now(UTC)
                await tx.execute("SELECT ai.fn_ensure_day_partitions($1::date, 1)", now.date())
                await tx.execute(
                    """INSERT INTO ai.guardrail_event (created_day, id, tenant_id, rule_code, action, detail,
                           created_at) VALUES ($1, $2, $3, 'C2_REQUEST', 'CHAN', $4::jsonb, $5)""",
                    now.date(), uuid.uuid4(), uuid.UUID(ev.tenant_id),
                    json.dumps({"reason": "INDEX_SKIPPED", "classification": skip, "sourceId": doc_id}), now)
                if self.extractor is not None:
                    await self.extractor.mark(tx, ev.tenant_id, doc_id, version, "KHONG_TRICH_DUOC",
                                              self.extractor.queue.llm.model)
                return
            await self._store.replace_source(tx, tenant_id=ev.tenant_id, source_type="VAN_BAN_CHI_DAO",
                                             source_id=doc_id, chunks=chunks, vectors=vectors,
                                             model=self._emb.model, acl=acl, classification="C1")
            if self.extractor is not None and ex is not None:
                await self.extractor.write(tx, ev.tenant_id, doc_id, version, ex)

        ev.aggregate_type = ev.aggregate_type or "document"
        ev.aggregate_id = ev.aggregate_id or doc_id
        ev.aggregate_version = version
        return await self._inbox.process(self._pool, ev, apply)

    async def load_corpus(self, manifest: dict[str, Any], read: Any) -> int:
        """Nạp kho văn bản dùng chung: tenant dùng chung, ACL mở, C0 (văn bản công khai)."""
        total = 0
        for item in manifest["documents"]:
            res = await self.index_text(tenant_id=SHARED_TENANT, source_type=item["sourceType"],
                                        source_id=item["sourceId"], title=item["title"], text=read(item["file"]),
                                        acl=[ACL_ALL], classification="C0")
            total += res.chunks
        return total
