"""Cổng nhận dạng tiếng nói tiếng Việt (DV-14-07). TL chưa chỉ định bộ nhận dạng; bản hiện thực gọi dịch vụ ASR
chạy trong cell qua API tương thích OpenAI `POST /v1/audio/transcriptions` (vLLM và phần lớn máy chủ ASR mã mở
đều có), tên mô hình theo ví dụ hợp đồng của Viettel AI (suy luận, wiki/14). Có thời hạn, giới hạn đồng thời, thử
lại một lần khi lỗi tạm thời và ngắt mạch khi lỗi liên tiếp như client OCR. Bản giả lập đọc lời thoại mẫu trong
siêu dữ liệu tệp (YC-PT-13)."""

from __future__ import annotations

import asyncio
import math
import time
from dataclasses import dataclass
from typing import Protocol

import httpx

from app.speech.audio import Pcm


class Unrecognized(Exception):
    """Không nhận ra lời nói nào."""


class AsrUnavailable(Exception):
    """Bộ nhận dạng lỗi hoặc đang ngắt mạch."""


@dataclass(frozen=True)
class Transcript:
    text: str
    confidence: float | None
    model: str


class SpeechRecognizer(Protocol):
    @property
    def provider(self) -> str: ...

    async def transcribe(self, audio: Pcm, language: str) -> Transcript: ...


class MockRecognizer:
    provider = "mock"

    def __init__(self, model: str = "mock-asr-vi") -> None:
        self._model = model

    async def transcribe(self, audio: Pcm, language: str) -> Transcript:
        if not audio.hint:
            raise Unrecognized("không có lời thoại mẫu")
        return Transcript(audio.hint, 0.9, self._model)


class OpenAiAsr:
    provider = "openai"

    def __init__(self, http: httpx.AsyncClient, model: str, *, concurrency: int = 4, timeout_s: float = 20,
                 failures_to_open: int = 5, open_s: float = 30) -> None:
        self._http = http
        self._model = model
        self._sem = asyncio.Semaphore(concurrency)
        self._timeout = timeout_s
        self._fails = 0
        self._limit = failures_to_open
        self._open_s = open_s
        self._open_until = 0.0

    async def transcribe(self, audio: Pcm, language: str) -> Transcript:
        if time.monotonic() < self._open_until:
            raise AsrUnavailable("ngắt mạch")
        form = {"model": self._model, "language": language.split("-")[0], "response_format": "verbose_json",
                "temperature": "0"}
        async with self._sem:
            for attempt in (1, 2):
                try:
                    r = await self._http.post("/v1/audio/transcriptions", data=form,
                                              files={"file": ("audio.wav", audio.wav, "audio/wav")},
                                              timeout=self._timeout)
                    r.raise_for_status()
                    body = r.json()
                    self._fails = 0
                    text = str(body.get("text") or "").strip()
                    if not text:
                        raise Unrecognized("không nhận ra lời nói")
                    return Transcript(text, _confidence(body), str(body.get("model") or self._model))
                except Unrecognized:
                    raise
                except (httpx.HTTPError, ValueError, TypeError, AttributeError) as e:
                    transient = not isinstance(e, httpx.HTTPStatusError) or e.response.status_code >= 500
                    if attempt == 1 and transient:
                        await asyncio.sleep(0.2)
                        continue
                    self._fails += 1
                    if self._fails >= self._limit:
                        self._open_until = time.monotonic() + self._open_s
                    raise AsrUnavailable(type(e).__name__) from e
        raise AsrUnavailable("không gọi được")


def _confidence(body: dict[str, object]) -> float | None:
    """Độ tin cậy = exp(trung bình avg_logprob của các đoạn), kẹp trong [0, 1]; không có thì bỏ trống."""
    segs = body.get("segments")
    vals = [float(s["avg_logprob"]) for s in segs if isinstance(s, dict) and isinstance(
        s.get("avg_logprob"), int | float)] if isinstance(segs, list) else []
    if not vals:
        return None
    return round(min(1.0, max(0.0, math.exp(sum(vals) / len(vals)))), 4)
