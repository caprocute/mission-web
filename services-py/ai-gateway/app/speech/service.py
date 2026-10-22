"""transcribeSpeech của SVC-14 (DV-14-07, SCR-0302 "Nói để nhập", SCR-1803 "Nói"). Âm thanh chỉ nằm trong bộ nhớ
suốt yêu cầu, không ghi đĩa, không ghi nhật ký, chỉ gửi tới bộ nhận dạng trong cell. Văn bản trả về qua rào chắn
đầu ra (cắt 1.000 ký tự, chặn nội dung tiêm lệnh) và không lưu ở ai-gateway: người dùng xác nhận rồi mới lưu qua
API nghiệp vụ (MSG-18-10). Chỉ khi rào chắn chặn mới ghi một dòng `ai.suggestion_log` không có văn bản để gắn
`ai.guardrail_event`."""

from __future__ import annotations

import asyncio
import time
from collections import defaultdict, deque
from typing import Any

from prometheus_client import Counter, Histogram

from app.core.auth import Principal
from app.guard import rules
from app.guard.guards import GUARD_TOTAL, MSG_INJECTION
from app.speech import audio
from app.speech.recognizer import SpeechRecognizer
from app.store.suggestions import Block, Entry, SuggestionStore

MAX_TEXT = 1000
USE_CASE = "GIONG_NOI"
LANGUAGES = ("vi-VN",)

SECONDS = Histogram("ai_speech_seconds", "Thời gian nhận dạng tiếng nói theo độ dài đoạn ghi", ["length"],
                    buckets=(0.25, 0.5, 1, 2, 3, 5, 8, 13, 20))
ERRORS = Counter("ai_speech_errors_total", "Số lần nhận dạng tiếng nói lỗi theo nhà cung cấp", ["provider", "reason"])
CALLS = Counter("ai_speech_requests_total", "Số yêu cầu nhận dạng tiếng nói theo nhà cung cấp", ["provider"])


class Disabled(Exception):
    """Mức suy giảm 1: trợ lý AI tạm tắt."""


class Busy(Exception):
    """Hàng đợi nhận dạng đã đầy."""


class RateLimited(Exception):
    def __init__(self, retry_after: int) -> None:
        super().__init__("quá số lần cho phép")
        self.retry_after = retry_after


class Blocked(Exception):
    def __init__(self, block: Block) -> None:
        super().__init__(block.message)
        self.block = block


class UnsupportedLanguage(Exception):
    pass


def length_label(ms: int) -> str:
    return "lt10s" if ms < 10_000 else "10-30s" if ms < 30_000 else "30-60s"


def clip(text: str, limit: int = MAX_TEXT) -> str:
    text = " ".join(text.split())
    if len(text) <= limit:
        return text
    cut = text[:limit]
    space = cut.rfind(" ")
    return cut[:space] if space >= limit * 0.8 else cut


class RateLimiter:
    """Cửa sổ trượt theo người dùng trong một pod (suy luận: giới hạn chung nhiều pod để ở APISIX)."""

    def __init__(self, per_minute: int) -> None:
        self._n = per_minute
        self._hits: dict[tuple[str, str], deque[float]] = defaultdict(deque)

    def check(self, key: tuple[str, str], now: float | None = None) -> None:
        if self._n <= 0:
            return
        t = time.monotonic() if now is None else now
        q = self._hits[key]
        while q and q[0] <= t - 60:
            q.popleft()
        if len(q) >= self._n:
            raise RateLimited(max(1, int(q[0] + 60 - t) + 1))
        q.append(t)
        if len(self._hits) > 100_000:  # dọn khoá đã hết hạn để bộ nhớ không tăng mãi
            for k in [k for k, v in self._hits.items() if not v or v[-1] <= t - 60]:
                del self._hits[k]


class SpeechService:
    def __init__(self, recognizer: SpeechRecognizer, store: SuggestionStore, *, max_concurrency: int = 4,
                 max_waiting: int = 16, per_minute: int = 10, disabled: bool = False) -> None:
        self._asr = recognizer
        self._store = store
        self._sem = asyncio.Semaphore(max_concurrency)
        self._max = max_concurrency + max_waiting
        self._inflight = 0
        self._limiter = RateLimiter(per_minute)
        self.disabled = disabled

    def admit(self, p: Principal) -> None:
        if self.disabled:
            raise Disabled()
        self._limiter.check((p.tenant_id, p.user_id))

    async def transcribe(self, p: Principal, data: bytes, language: str, purpose: str) -> dict[str, Any]:
        if language not in LANGUAGES:
            raise UnsupportedLanguage(language)
        if self._inflight >= self._max:
            raise Busy()
        self._inflight += 1
        try:
            pcm = await asyncio.to_thread(audio.decode, data)
            provider = self._asr.provider
            CALLS.labels(provider).inc()
            async with self._sem:
                start = time.perf_counter()
                try:
                    t = await self._asr.transcribe(pcm, language)
                except Exception as e:
                    ERRORS.labels(provider, type(e).__name__).inc()
                    raise
                SECONDS.labels(length_label(pcm.duration_ms)).observe(time.perf_counter() - start)
        finally:
            self._inflight -= 1
        text = clip(t.text)
        if rules.is_injection(text):
            GUARD_TOTAL.labels("PROMPT_INJECTION", "CHAN").inc()
            b = Block("PROMPT_INJECTION", "CHAN", MSG_INJECTION, "", {"where": "output", "useCase": USE_CASE})
            await self._store.log(Entry(p.tenant_id, p.user_id, USE_CASE, t.model, "v1",
                                        f"[đoạn ghi âm {pcm.duration_ms} ms, ô {purpose}]", None,
                                        confidence=t.confidence), [b])
            raise Blocked(b)
        out: dict[str, Any] = {"text": text, "durationMs": pcm.duration_ms, "model": t.model}
        if t.confidence is not None:
            out["confidence"] = t.confidence
        return out
