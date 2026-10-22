"""Truy xuất ngữ cảnh RAG cho một người hỏi (BR-17-03): chỉ đoạn của tenant của người hỏi hoặc tài liệu dùng chung,
ACL giao với đơn vị đang chọn của người hỏi (hoặc chính người hỏi với tệp vừa tải lên), mức C0, C1."""

from __future__ import annotations

from vtplatform.tenant import is_valid_uuid

from app.core.auth import Principal
from app.core.pipeline import ContextChunk
from app.rag.embed import Embedder
from app.rag.store import ChunkStore, Hit

HEADER = "Nguồn: "


def header(title: str, location: str) -> str:
    return HEADER + title + (f"; {location}" if location else "")


def parse_header(content: str) -> tuple[str, str, str]:
    first, _, body = content.partition("\n")
    if not first.startswith(HEADER):
        return "Tài liệu", "", content
    title, _, loc = first[len(HEADER):].rpartition("; ") if "; " in first else (first[len(HEADER):], "", "")
    return title, loc, body


def to_context(h: Hit) -> ContextChunk:
    title, loc, body = parse_header(h.content)
    src = {"kind": "VAN_BAN", "title": title, "ref": h.id, "excerpt": body[:300]}
    if loc:
        src["location"] = loc
    return ContextChunk(ref=h.id, text=h.content, source=src, classification="C1", chunk_id=h.id)


class Retriever:
    def __init__(self, store: ChunkStore, embedder: Embedder) -> None:
        self._store = store
        self._emb = embedder

    def readers(self, p: Principal) -> list[str]:
        return [x for x in (p.unit_id, p.user_id) if x and is_valid_uuid(x)]

    async def retrieve(self, p: Principal, query: str, k: int = 6) -> list[ContextChunk]:
        [vec] = await self._emb.embed([query])
        hits = await self._store.retrieve(tenant_id=p.tenant_id, readers=self.readers(p), vector=vec,
                                          model=self._emb.model, k=k)
        return [to_context(h) for h in hits]
