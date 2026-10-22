"""Kiểm thử tích hợp DV-14-02 trên PostgreSQL 17 + pgvector: cách ly ACL theo đơn vị trên 1.000 truy vấn ngẫu nhiên,
tài liệu C2/C3 không vào kho, EVT-24 giao lại không sinh đoạn trùng, kho dùng chung và tệp đính kèm riêng tư.
Đo hiệu năng truy xuất bật bằng AI_PERF_CHUNKS=<số đoạn>."""

from __future__ import annotations

import asyncio
import os
import random
import time
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

import asyncpg
import psycopg
import pytest

from app.core.auth import Principal
from app.core.core_client import C3ForbiddenError
from app.rag.chunker import chunk_text
from app.rag.embed import HashEmbedder
from app.rag.ingest import Indexer
from app.rag.retriever import Retriever
from app.rag.store import ACL_ALL, SHARED_TENANT, ChunkStore
from tests.conftest import REPO

pytestmark = pytest.mark.integration

WORDS = ("kế hoạch tiến độ chỉ tiêu hồ sơ nghiệm thu đánh giá cán bộ đơn vị nhiệm vụ báo cáo quý năm sở ngành "
         "chuyển đổi số cải cách hành chính dịch vụ công kiểm tra giám sát tài chính ngân sách đầu tư").split()


class FakeCore:
    def __init__(self) -> None:
        self.docs: dict[str, Any] = {}

    async def get_json_with(self, _tok: Any, path: str, _params: Any = None) -> Any:
        doc = self.docs[path.rsplit("/", 1)[1]]
        if doc.get("classification") == "C3":
            raise C3ForbiddenError
        return doc


class FakeToken:
    async def get(self) -> str:
        return "tok"


def run(db: dict[str, str], body: Callable[[Indexer, Retriever, FakeCore], Awaitable[Any]]) -> Any:
    async def go() -> Any:
        pool = await asyncpg.create_pool(db["svc"], min_size=1, max_size=4)
        try:
            emb, core = HashEmbedder(), FakeCore()
            store = ChunkStore(pool)
            idx = Indexer(pool, store, emb, core, FakeToken())  # type: ignore[arg-type]
            return await body(idx, Retriever(store, emb), core)
        finally:
            await pool.close()

    return asyncio.run(go())


def who(tenant: str, unit: str | None = None, user: str | None = None) -> Principal:
    return Principal(user or str(uuid.uuid4()), tenant, ("member",), "t", unit_id=unit)


def text(rng: random.Random, n: int = 120) -> str:
    return "Điều 1. Nội dung\n1. " + " ".join(rng.choice(WORDS) for _ in range(n)) + "."


def test_unit_acl_never_leaks_over_1000_random_queries(db: dict[str, str]) -> None:
    tenant, unit_a, unit_b = (str(uuid.uuid4()) for _ in range(3))
    rng = random.Random(14)

    async def body(idx: Indexer, ret: Retriever, _core: FakeCore) -> None:
        for i in range(80):
            await idx.index_text(tenant_id=tenant, source_type="VAN_BAN_CHI_DAO", source_id=str(uuid.uuid4()),
                                 title=f"Văn bản {i}", text=text(rng), acl=[unit_a if i % 2 else unit_b])
        p = who(tenant, unit_a)
        seen_own = 0
        for _ in range(1000):
            q = " ".join(rng.choice(WORDS) for _ in range(rng.randint(2, 8)))
            hits = await ret._store.retrieve(tenant_id=tenant, readers=ret.readers(p),
                                             vector=(await ret._emb.embed([q]))[0], model=ret._emb.model, k=6)
            assert all(unit_a in h.acl_unit_ids or ACL_ALL in h.acl_unit_ids for h in hits)
            assert all(unit_b not in h.acl_unit_ids or unit_a in h.acl_unit_ids for h in hits)
            seen_own += len(hits)
        assert seen_own >= 1000 * 5  # lọc chặt vẫn đủ k nhờ iterative_scan

    run(db, body)


def evt(tenant: str, doc_id: str, version: int) -> tuple[Any, dict[str, Any]]:
    from app.rag.consumer import to_event

    ev = to_event("integ.document.received.v1", 0, version, {"id": str(uuid.uuid4()), "tenantid": tenant})
    return ev, {"documentId": doc_id, "docNumber": "12/CT-UBND", "issuer": "UBND tỉnh", "aggregateVersion": version}


def source_doc(doc_id: str, body: str, cls: str = "C1") -> dict[str, Any]:
    return {"id": doc_id, "title": "Chỉ thị", "classification": cls,
            "paragraphs": [{"no": 1, "text": body, "assignment": True}]}


def count(db: dict[str, str], sql: str, *args: Any) -> int:
    with psycopg.connect(db["admin"]) as c:
        row = c.execute(sql, args).fetchone()
        return int(row[0]) if row else 0


def test_evt24_redelivery_and_new_version(db: dict[str, str]) -> None:
    tenant, doc = str(uuid.uuid4()), str(uuid.uuid4())
    rng = random.Random(24)
    v1, v2 = text(rng, 1500), text(rng, 300)

    async def body(idx: Indexer, _ret: Retriever, core: FakeCore) -> list[str]:
        core.docs[doc] = source_doc(doc, v1)
        ev, payload = evt(tenant, doc, 1)
        first = await idx.handle_document_received(ev, payload)
        again = await idx.handle_document_received(ev, payload)  # cùng mã sự kiện: giao lại
        ev2, _ = evt(tenant, doc, 1)  # mã sự kiện khác, cùng phiên bản: cũ hơn hoặc bằng
        stale = await idx.handle_document_received(ev2, payload)
        n1 = count(db, "SELECT count(*) FROM ai.doc_chunk WHERE source_id = %s", doc)
        assert n1 == len(chunk_text(document_text_of(core.docs[doc])))
        core.docs[doc] = source_doc(doc, v2)
        ev3, payload3 = evt(tenant, doc, 2)
        newer = await idx.handle_document_received(ev3, payload3)
        n2 = count(db, "SELECT count(*) FROM ai.doc_chunk WHERE source_id = %s", doc)
        assert n1 > n2 >= 1
        return [first.value, again.value, stale.value, newer.value]

    outcomes = run(db, body)
    assert outcomes[0] == outcomes[3] and outcomes[1] != outcomes[0] and outcomes[2] != outcomes[0]


def document_text_of(doc: dict[str, Any]) -> str:
    from app.rag.ingest import document_text

    return document_text(doc)


def test_c2_c3_documents_never_indexed(db: dict[str, str]) -> None:
    tenant = str(uuid.uuid4())
    docs = {cls: str(uuid.uuid4()) for cls in ("C2", "C3")}

    async def body(idx: Indexer, _ret: Retriever, core: FakeCore) -> None:
        for cls, d in docs.items():
            core.docs[d] = source_doc(d, "1. Nội dung mật về nhân sự.", cls)
            await idx.handle_document_received(*evt(tenant, d, 1))
        with pytest.raises(ValueError):
            await idx.index_text(tenant_id=tenant, source_type="VAN_BAN", source_id=str(uuid.uuid4()),
                                 title="x", text="1. abc", acl=[ACL_ALL], classification="C2")

    run(db, body)
    assert count(db, "SELECT count(*) FROM ai.doc_chunk WHERE tenant_id = %s", tenant) == 0
    assert count(db, "SELECT count(*) FROM ai.doc_chunk WHERE classification NOT IN ('C0', 'C1')") == 0
    assert count(db, """SELECT count(*) FROM ai.guardrail_event WHERE tenant_id = %s AND rule_code = 'C2_REQUEST'
                        AND detail->>'reason' = 'INDEX_SKIPPED'""", tenant) == 2


def test_shared_corpus_visible_to_all_tenants_attachment_private(db: dict[str, str]) -> None:
    import json

    manifest = json.loads((REPO / "data" / "seed" / "ai-corpus" / "danh-muc.json").read_text(encoding="utf-8"))
    t1, t2 = str(uuid.uuid4()), str(uuid.uuid4())
    owner, other = who(t1, str(uuid.uuid4())), who(t1, str(uuid.uuid4()))

    async def body(idx: Indexer, ret: Retriever, _core: FakeCore) -> None:
        n = await idx.load_corpus(manifest, lambda f: (REPO / "data" / "seed" / "ai-corpus" / f).read_text("utf-8"))
        assert n >= 1
        for p in (who(t1), who(t2)):
            hits = await ret.retrieve(p, "cách viết mục tiêu và kết quả then chốt", k=3)
            assert hits and hits[0].source is not None and hits[0].source["title"]
        from tests.test_rag import _docx

        data = _docx(["Điều 1. Kế hoạch riêng", "1. Bí danh dự án Sao Mai lập kế hoạch tiết kiệm năng lượng."])
        res = await idx.index_attachment(owner, "ke-hoach.docx", data)
        assert res.chunks == 1
        mine = await ret.retrieve(owner, "dự án Sao Mai tiết kiệm năng lượng", k=10)
        assert any("Sao Mai" in (h.text or "") for h in mine)
        theirs = await ret.retrieve(other, "dự án Sao Mai tiết kiệm năng lượng", k=10)
        assert not any("Sao Mai" in (h.text or "") for h in theirs)

    run(db, body)
    assert count(db, "SELECT count(*) FROM ai.doc_chunk WHERE tenant_id = %s", SHARED_TENANT) >= 1


@pytest.mark.skipif(not os.environ.get("AI_PERF_CHUNKS"), reason="đặt AI_PERF_CHUNKS để đo hiệu năng truy xuất")
def test_retrieval_latency(db: dict[str, str]) -> None:
    total, tenant = int(os.environ["AI_PERF_CHUNKS"]), str(uuid.uuid4())
    units = [str(uuid.uuid4()) for _ in range(50)]
    rng = random.Random(1)
    with psycopg.connect(db["admin"], autocommit=True) as c:
        c.execute("""INSERT INTO ai.doc_chunk (id, tenant_id, source_type, source_id, chunk_no, content, embedding,
                         embed_model, acl_unit_ids, classification)
                     SELECT gen_random_uuid(), %s, 'VAN_BAN', gen_random_uuid(), 0, 'x',
                            (SELECT array_agg(random() - 0.5)::vector(1024) FROM generate_series(1, 1024 + g * 0)),
                            'hash-1024', ARRAY[(%s::uuid[])[1 + g %% 50]], 'C1'
                       FROM generate_series(1, %s) g""", (tenant, units, total))
        c.execute("ANALYZE ai.doc_chunk")

    async def body(_idx: Indexer, ret: Retriever, _core: FakeCore) -> list[float]:
        out = []
        for _ in range(200):
            vec = [rng.random() - 0.5 for _ in range(1024)]
            t = time.perf_counter()
            hits = await ret._store.retrieve(tenant_id=tenant, readers=[rng.choice(units)], vector=vec,
                                             model="hash-1024", k=8)
            out.append(time.perf_counter() - t)
            assert len(hits) == 8
        return sorted(out)

    lat = run(db, body)
    p95 = lat[int(len(lat) * 0.95)] * 1000
    print(f"\n{total} đoạn, 200 truy vấn lọc ACL 1/50: p50 {lat[100] * 1000:.1f} ms, p95 {p95:.1f} ms")
