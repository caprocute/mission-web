"""Ghi và truy xuất ai.doc_chunk (TL-03 "Schema ai", ADR-28). Truy xuất lọc trước theo tenant, mức dữ liệu, mô hình
nhúng và ACL bằng điều kiện WHERE, rồi xếp hạng HNSW cosine với `hnsw.iterative_scan = relaxed_order` của
pgvector 0.8 để bộ lọc chặt vẫn đủ k kết quả."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Any

from vtplatform.db import in_tenant_tx

from app.rag.chunker import Chunk
from app.rag.embed import to_pg

SHARED_TENANT = "00000000-0000-0000-0000-000000000000"
ACL_ALL = "00000000-0000-0000-0000-000000000000"
ALLOWED = ("C0", "C1")


@dataclass(frozen=True)
class Hit:
    id: str
    tenant_id: str
    source_type: str
    source_id: str
    chunk_no: int
    content: str
    acl_unit_ids: tuple[str, ...]
    score: float


def chunk_id(tenant_id: str, source_id: str, no: int, model: str) -> uuid.UUID:
    """Mã đoạn tất định theo khoá UQ của TL-03, để giao lại sự kiện không sinh đoạn trùng."""
    return uuid.uuid5(uuid.UUID(source_id), f"{tenant_id}|{no}|{model}")


class ChunkStore:
    def __init__(self, pool: Any) -> None:
        self._pool = pool

    async def replace_source(self, tx: Any, *, tenant_id: str, source_type: str, source_id: str,
                             chunks: list[Chunk], vectors: list[list[float]], model: str, acl: list[str],
                             classification: str) -> int:
        """Thay toàn bộ đoạn của một nguồn với một mô hình nhúng; đoạn của mô hình khác giữ nguyên."""
        if classification not in ALLOWED:
            raise ValueError("chỉ tài liệu C0, C1 được đưa vào kho RAG")
        if not acl:
            raise ValueError("đoạn phải có ít nhất một đơn vị được xem")
        await tx.execute("DELETE FROM ai.doc_chunk WHERE tenant_id = $1 AND source_id = $2 AND embed_model = $3",
                         uuid.UUID(tenant_id), uuid.UUID(source_id), model)
        acl_ids = [uuid.UUID(a) for a in acl]
        for c, v in zip(chunks, vectors, strict=True):
            await tx.execute(
                """INSERT INTO ai.doc_chunk (id, tenant_id, source_type, source_id, chunk_no, content, embedding,
                       embed_model, acl_unit_ids, classification)
                   VALUES ($1, $2, $3, $4, $5, $6, $7::vector, $8, $9::uuid[], $10)""",
                chunk_id(tenant_id, source_id, c.no, model), uuid.UUID(tenant_id), source_type,
                uuid.UUID(source_id), c.no, c.text, to_pg(v), model, acl_ids, classification)
        return len(chunks)

    async def index(self, *, tenant_id: str, **kw: Any) -> int:
        async with in_tenant_tx(self._pool, tenant_id) as tx:
            n: int = await self.replace_source(tx, tenant_id=tenant_id, **kw)
            return n

    async def retrieve(self, *, tenant_id: str, readers: list[str], vector: list[float], model: str,
                       k: int = 8, ef_search: int = 100) -> list[Hit]:
        acl = [uuid.UUID(r) for r in {*readers, ACL_ALL}]
        async with in_tenant_tx(self._pool, tenant_id) as tx:
            await tx.execute("SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true)")
            await tx.execute("SELECT set_config('hnsw.ef_search', $1, true)", str(max(ef_search, k)))
            rows = await tx.fetch(
                """SELECT id, tenant_id, source_type, source_id, chunk_no, content, acl_unit_ids,
                          embedding <=> $1::vector AS distance
                     FROM ai.doc_chunk
                    WHERE tenant_id = ANY($2::uuid[]) AND classification = ANY($3::varchar[])
                      AND embed_model = $4 AND acl_unit_ids && $5::uuid[]
                    ORDER BY embedding <=> $1::vector
                    LIMIT $6""",
                to_pg(vector), [uuid.UUID(tenant_id), uuid.UUID(SHARED_TENANT)], list(ALLOWED), model, acl, k)
        allowed = {str(a) for a in acl}
        hits = []
        for r in rows:
            ids = tuple(str(a) for a in r["acl_unit_ids"])
            # Kiểm lại ở tầng ứng dụng: không bao giờ trả đoạn ngoài ACL dù câu truy vấn có lỗi.
            if not allowed.intersection(ids) or str(r["tenant_id"]) not in (tenant_id, SHARED_TENANT):
                continue
            hits.append(Hit(str(r["id"]), str(r["tenant_id"]), r["source_type"], str(r["source_id"]), r["chunk_no"],
                            r["content"], ids, 1.0 - float(r["distance"])))
        return hits
