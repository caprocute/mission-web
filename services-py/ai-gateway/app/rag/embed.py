"""Client mô hình nhúng tiếng Việt tự lưu trữ, 1.024 chiều (TL-01 "Kiến trúc cổng mô hình"). Gọi theo lô qua API
tương thích OpenAI `POST /v1/embeddings` (vLLM, TEI, llama.cpp), giới hạn số lô đồng thời. HashEmbedder là bản
giả lập tất định cho dev và kiểm thử (YC-PT-13): băm từ và cặp từ đã bỏ dấu vào 1.024 chiều rồi chuẩn hoá."""

from __future__ import annotations

import asyncio
import hashlib
import itertools
import math
from typing import Protocol

import httpx

from app.guard.text import fold

DIM = 1024


class EmbedError(Exception):
    """Mô hình nhúng lỗi hoặc trả sai số chiều."""


class Embedder(Protocol):
    @property
    def model(self) -> str: ...

    async def embed(self, texts: list[str]) -> list[list[float]]: ...


class OpenAiEmbedder:
    def __init__(self, http: httpx.AsyncClient, model: str, batch: int = 32, concurrency: int = 2) -> None:
        self._http = http
        self._model = model
        self._batch = batch
        self._sem = asyncio.Semaphore(concurrency)

    @property
    def model(self) -> str:
        return self._model

    async def _one(self, texts: list[str]) -> list[list[float]]:
        async with self._sem:
            try:
                r = await self._http.post("/v1/embeddings", json={"model": self._model, "input": texts})
                r.raise_for_status()
                data = sorted(r.json()["data"], key=lambda d: d["index"])
                out = [[float(x) for x in d["embedding"]] for d in data]
            except (httpx.HTTPError, KeyError, TypeError, ValueError) as e:
                raise EmbedError(type(e).__name__) from e
        if len(out) != len(texts) or any(len(v) != DIM for v in out):
            raise EmbedError("số véc-tơ hoặc số chiều không khớp")
        return out

    async def embed(self, texts: list[str]) -> list[list[float]]:
        parts = [texts[i: i + self._batch] for i in range(0, len(texts), self._batch)]
        res = await asyncio.gather(*(self._one(p) for p in parts))
        return [v for r in res for v in r]


class HashEmbedder:
    def __init__(self, model: str = "hash-vi-1024") -> None:
        self._model = model

    @property
    def model(self) -> str:
        return self._model

    @staticmethod
    def vector(text: str) -> list[float]:
        words = [w for w in "".join(c if c.isalnum() else " " for c in fold(text)).split() if len(w) > 1]
        v = [0.0] * DIM
        for feat in [*words, *(a + "_" + b for a, b in itertools.pairwise(words))]:
            h = hashlib.blake2b(feat.encode(), digest_size=8).digest()
            idx = int.from_bytes(h[:4], "little") % DIM
            v[idx] += 1.0 if h[4] & 1 else -1.0
        n = math.sqrt(sum(x * x for x in v)) or 1.0
        return [x / n for x in v]

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [self.vector(t) for t in texts]


def to_pg(v: list[float]) -> str:
    return "[" + ",".join(f"{x:.6g}" for x in v) + "]"
