"""Năm bước chung của mọi yêu cầu AI (TL-01 "Kiến trúc cổng mô hình"). Bước 1 (JWT, OPA) ở app.api.deps; bước 2
lắp ngữ cảnh qua CoreClient và RAG do ca dùng truyền vào; Gateway lo bước 3 đến 5: rào chắn đầu vào, gọi mô
hình qua hàng đợi, rào chắn đầu ra, ghi nhật ký. Ca dùng (DV-14-02..06) chỉ dựng lời nhắc và đọc kết quả."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from typing import Any

from app.core.auth import Principal
from app.llm.client import Completion, InferenceQueue, Message, QueueFull
from app.store.suggestions import LABEL, Block, Entry, SuggestionStore

log = logging.getLogger(__name__)


@dataclass
class SuggestionRequest:
    use_case: str
    template_version: str
    messages: list[Message]
    chunk_ids: list[str] = field(default_factory=list)
    sources: list[dict[str, Any]] = field(default_factory=list)
    kind: str | None = None
    target_ref: str | None = None
    json_mode: bool = False
    max_tokens: int = 512


# Rào chắn trả Block để chặn hoặc ghi cảnh báo, None để cho qua (DV-14-03 cài đặt các quy tắc).
InputGuard = Callable[[Principal, SuggestionRequest], Awaitable[Block | None]]
OutputGuard = Callable[[Principal, SuggestionRequest, Completion], Awaitable[Block | None]]


@dataclass(frozen=True)
class Accepted:
    """Hàng đợi đầy hoặc suy luận quá thời gian chờ: trả 202 kèm job (MSG-17-03)."""

    suggestion_id: str

    @property
    def location(self) -> str:
        return f"/api/v1/ai/suggestions/{self.suggestion_id}"

    def job(self) -> dict[str, Any]:
        return {
            "id": self.suggestion_id,
            "type": "AI_SUGGESTION",
            "status": "CHO",
            "progress": 0,
            "message": "Trợ lý đang bận, gợi ý sẽ có sau ít phút.",
            "resultLocation": self.location,
        }


class Overloaded(Exception):
    """Cả hàng đợi và số việc hoãn đều đầy: trả 429."""


class Gateway:
    def __init__(
        self,
        queue: InferenceQueue,
        store: SuggestionStore,
        *,
        timeout_s: float,
        sync_wait_s: float,
        deferred_max: int,
        input_guards: Sequence[InputGuard] = (),
        output_guards: Sequence[OutputGuard] = (),
    ) -> None:
        self.queue = queue
        self.store = store
        self._timeout = timeout_s
        self._sync_wait = sync_wait_s
        self._deferred_max = deferred_max
        self._in = list(input_guards)
        self._out = list(output_guards)
        self._deferred: set[asyncio.Task[dict[str, Any]]] = set()

    @property
    def deferred(self) -> int:
        return len(self._deferred)

    async def suggest(self, p: Principal, req: SuggestionRequest) -> dict[str, Any] | Accepted:
        blocks: list[Block] = []
        for g in self._in:
            b = await g(p, req)
            if b is not None:
                blocks.append(b)
                if b.action == "CHAN":
                    return await self._blocked(p, req, blocks, b)
        entry = Entry(tenant_id=p.tenant_id, user_id=p.user_id, use_case=req.use_case, model=self.queue.llm.model,
                      prompt_template_version=req.template_version, prompt=_prompt_text(req.messages),
                      output_text=None, chunk_ids=req.chunk_ids, sources=req.sources, kind=req.kind,
                      target_ref=req.target_ref)
        if not self.queue.has_room():
            return self._defer(p, req, entry, blocks)
        task = asyncio.create_task(self._infer(p, req, entry, blocks, bypass_cap=False))
        try:
            return await asyncio.wait_for(asyncio.shield(task), self._sync_wait)
        except QueueFull:
            return self._defer(p, req, entry, blocks)
        except TimeoutError:
            self._track(task)
            return Accepted(entry.id)

    def _defer(self, p: Principal, req: SuggestionRequest, entry: Entry, blocks: list[Block]) -> Accepted:
        if len(self._deferred) >= self._deferred_max:
            raise Overloaded
        self._track(asyncio.create_task(self._infer(p, req, entry, blocks, bypass_cap=True)))
        return Accepted(entry.id)

    def _track(self, task: asyncio.Task[dict[str, Any]]) -> None:
        self._deferred.add(task)

        def done(t: asyncio.Task[dict[str, Any]]) -> None:
            self._deferred.discard(t)
            if not t.cancelled() and t.exception() is not None:
                log.error("gợi ý hoãn thất bại", extra={"err": type(t.exception()).__name__})

        task.add_done_callback(done)

    async def _infer(self, p: Principal, req: SuggestionRequest, entry: Entry, blocks: list[Block],
                     *, bypass_cap: bool) -> dict[str, Any]:
        c = await self.queue.run(req.messages, timeout_s=self._timeout, bypass_cap=bypass_cap,
                                 max_tokens=req.max_tokens, json_mode=req.json_mode)
        entry.model = c.model
        for g in self._out:
            b = await g(p, req, c)
            if b is not None:
                blocks.append(b)
                if b.action == "CHAN":
                    return await self._blocked(p, req, blocks, b, entry)
        entry.output_text = c.text
        await self.store.log(entry, blocks)
        return _api(entry)

    async def _blocked(self, p: Principal, req: SuggestionRequest, blocks: list[Block], b: Block,
                       entry: Entry | None = None) -> dict[str, Any]:
        e = entry or Entry(tenant_id=p.tenant_id, user_id=p.user_id, use_case=req.use_case,
                           model=self.queue.llm.model, prompt_template_version=req.template_version,
                           prompt=_prompt_text(req.messages), output_text=None, kind=req.kind,
                           target_ref=req.target_ref)
        e.output_text = None
        await self.store.log(e, blocks)
        out = _api(e)
        out["sources"] = []
        out["blocked"] = b.to_api()
        return out


def _prompt_text(messages: list[Message]) -> str:
    return "\n".join(f"[{m.role}] {m.content}" for m in messages)


def _api(e: Entry) -> dict[str, Any]:
    return {
        "id": e.id,
        "useCase": e.use_case,
        "kind": e.kind,
        "model": e.model,
        "createdAt": e.created_at.isoformat(),
        "aiGenerated": True,
        "label": LABEL,
        "outputText": e.output_text,
        "sources": e.sources,
        "confidence": e.confidence,
        "decision": None,
        "blocked": None,
    }
