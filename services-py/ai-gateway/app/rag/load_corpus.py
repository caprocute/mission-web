"""Nạp kho văn bản dùng chung: `python -m app.rag.load_corpus <thư mục có danh-muc.json>` với DATABASE_URL của svc_ai
và cấu hình mô hình nhúng như dịch vụ. Chạy lại an toàn: mỗi văn bản được thay toàn bộ đoạn của mô hình hiện hành."""

from __future__ import annotations

import asyncio
import json
import pathlib
import sys

import asyncpg

from app.core.config import Settings
from app.main import build_embedder
from app.rag.ingest import Indexer
from app.rag.store import ChunkStore


async def main(folder: pathlib.Path) -> int:
    s = Settings.from_env()
    pool = await asyncpg.create_pool(s.database_url, min_size=1, max_size=2)
    emb, http = build_embedder(s)
    try:
        manifest = json.loads((folder / "danh-muc.json").read_text(encoding="utf-8"))
        n = await Indexer(pool, ChunkStore(pool), emb).load_corpus(
            manifest, lambda f: (folder / f).read_text(encoding="utf-8"))
    finally:
        await pool.close()
        if http is not None:
            await http.aclose()
    print(f"đã nạp {n} đoạn")
    return n


if __name__ == "__main__":
    asyncio.run(main(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "data/seed/ai-corpus")))
