"""Bước 4: gọi mô hình qua API tương thích OpenAI của vLLM, có thời hạn, giới hạn đồng thời và hàng đợi suy
luận đo được (HPA co giãn theo độ dài hàng đợi, TL-01 SVC-14). Hàng đợi đầy thì ném QueueFull để API trả 202
kèm job (MSG-17-03). Chế độ "mock" là bản giả lập tất định cho dev và kiểm thử (YC-PT-13)."""

from __future__ import annotations

import asyncio
import hashlib
import time
from dataclasses import dataclass
from typing import Protocol

import httpx
from prometheus_client import Gauge, Histogram

from app.llm.fake_json import fake_reply

QUEUE_LENGTH = Gauge("ai_inference_queue_length", "Số yêu cầu suy luận đang chờ (chỉ số HPA)")
IN_FLIGHT = Gauge("ai_inference_in_flight", "Số yêu cầu suy luận đang chạy")
LATENCY = Histogram("ai_inference_seconds", "Thời gian một lần suy luận", buckets=(0.25, 0.5, 1, 2, 4, 8, 16, 32))


@dataclass(frozen=True)
class Message:
    role: str  # system, user, assistant
    content: str


@dataclass(frozen=True)
class Completion:
    text: str
    model: str
    prompt_tokens: int = 0
    completion_tokens: int = 0


class LlmError(Exception):
    """Mô hình lỗi hoặc hết thời hạn."""


class QueueFull(Exception):
    """Hàng đợi suy luận đã đầy."""


class Llm(Protocol):
    @property
    def model(self) -> str: ...

    async def complete(self, messages: list[Message], *, max_tokens: int = 512, temperature: float = 0.2,
                       json_mode: bool = False) -> Completion: ...


class OpenAiCompatLlm:
    """vLLM `POST /v1/chat/completions`."""

    def __init__(self, http: httpx.AsyncClient, model: str) -> None:
        self._http = http
        self._model = model

    @property
    def model(self) -> str:
        return self._model

    async def complete(self, messages: list[Message], *, max_tokens: int = 512, temperature: float = 0.2,
                       json_mode: bool = False) -> Completion:
        body: dict[str, object] = {
            "model": self._model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "max_tokens": max_tokens,
            "temperature": temperature,
        }
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        try:
            r = await self._http.post("/v1/chat/completions", json=body)
            r.raise_for_status()
            data = r.json()
            text = data["choices"][0]["message"]["content"]
        except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError) as e:
            raise LlmError(type(e).__name__) from e
        usage = data.get("usage") or {}
        return Completion(str(text), str(data.get("model") or self._model),
                          int(usage.get("prompt_tokens") or 0), int(usage.get("completion_tokens") or 0))


class MockLlm:
    """Bản giả lập: trả lời tất định theo băm lời nhắc, có thể đặt độ trễ để thử hàng đợi."""

    def __init__(self, model: str = "mock-llm-vi", delay_s: float = 0.0) -> None:
        self._model = model
        self.delay_s = delay_s

    @property
    def model(self) -> str:
        return self._model

    async def complete(self, messages: list[Message], *, max_tokens: int = 512, temperature: float = 0.2,
                       json_mode: bool = False) -> Completion:
        if self.delay_s:
            await asyncio.sleep(self.delay_s)
        prompt = "\n".join(m.content for m in messages)
        digest = hashlib.sha256(prompt.encode()).hexdigest()[:8]
        cite = " [#1]." if "[#1]" in prompt else "."
        text = fake_reply(prompt, digest) if json_mode else f"Nội dung gợi ý mẫu {digest}{cite}"
        return Completion(text, self._model)


class InferenceQueue:
    """Giới hạn số suy luận đồng thời và số yêu cầu chờ. Vượt số chờ thì QueueFull ngay, không chặn."""

    def __init__(self, llm: Llm, max_concurrency: int, max_waiting: int) -> None:
        if max_concurrency < 1 or max_waiting < 0:
            raise ValueError("cấu hình hàng đợi suy luận không hợp lệ")
        self.llm = llm
        self._sem = asyncio.Semaphore(max_concurrency)
        self._max_waiting = max_waiting
        self._waiting = 0

    @property
    def waiting(self) -> int:
        return self._waiting

    def has_room(self) -> bool:
        return self._waiting < self._max_waiting or not self._sem.locked()

    async def run(self, messages: list[Message], *, timeout_s: float, bypass_cap: bool = False,
                  max_tokens: int = 512, json_mode: bool = False) -> Completion:
        """bypass_cap dùng cho việc đã hoãn (202): chờ lượt mà không tính vào giới hạn chờ đồng bộ."""
        if not bypass_cap and self._sem.locked() and self._waiting >= self._max_waiting:
            raise QueueFull
        self._waiting += 1
        QUEUE_LENGTH.set(self._waiting)
        try:
            await self._sem.acquire()
        finally:
            self._waiting -= 1
            QUEUE_LENGTH.set(self._waiting)
        IN_FLIGHT.inc()
        start = time.monotonic()
        try:
            async with asyncio.timeout(timeout_s):
                return await self.llm.complete(messages, max_tokens=max_tokens, json_mode=json_mode)
        except TimeoutError as e:
            raise LlmError("hết thời hạn suy luận") from e
        finally:
            LATENCY.observe(time.monotonic() - start)
            IN_FLIGHT.dec()
            self._sem.release()
