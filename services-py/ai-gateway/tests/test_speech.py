"""Kiểm thử đơn vị DV-14-07: kiểm kiểu tệp thật, giải mã và chuẩn hoá trong bộ nhớ, giới hạn 60 giây, cắt văn bản,
giới hạn tốc độ, client ASR tương thích OpenAI."""

from __future__ import annotations

import io
import json
import wave

import httpx
import pytest

from app.speech import audio
from app.speech.recognizer import AsrUnavailable, OpenAiAsr, Unrecognized, _confidence
from app.speech.service import RateLimited, RateLimiter, clip, length_label
from tests.audio_samples import SAY
from tests.audio_samples import clip as sample


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.mark.parametrize("fmt", ["webm", "ogg", "wav", "mp4"])
def test_decode_four_containers_to_16k_mono_wav(fmt: str) -> None:
    pcm = audio.decode(sample(fmt, 2.0))
    assert pcm.mime == f"audio/{fmt}" and pcm.hint == SAY
    assert 1900 <= pcm.duration_ms <= 2100
    with wave.open(io.BytesIO(pcm.wav)) as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, 16000)
        assert abs(w.getnframes() - pcm.duration_ms * 16) <= 16


def test_spoofed_files_rejected() -> None:
    with pytest.raises(audio.BadAudio):
        audio.decode(b"<html>khong phai am thanh</html>" * 10)
    with pytest.raises(audio.BadAudio):  # chữ ký WebM nhưng thân là rác
        audio.decode(b"\x1a\x45\xdf\xa3" + b"\x00" * 4000)
    from tests.ocr_samples import page

    with pytest.raises(audio.BadAudio):
        audio.decode(page())


def test_too_long_and_too_big() -> None:
    with pytest.raises(audio.TooLong):
        audio.decode(sample("wav", 61.0))
    with pytest.raises(audio.TooLong):
        audio.decode(b"OggS" + b"\x00" * audio.MAX_BYTES)


def test_clip_and_labels() -> None:
    long = "Chi bộ chưa nộp biên bản " * 80
    out = clip(long)
    assert len(out) <= 1000 and not out.endswith(" ") and long.startswith(out)
    assert clip("  một   hai  ") == "một hai"
    assert [length_label(x) for x in (4200, 15000, 59000)] == ["lt10s", "10-30s", "30-60s"]


def test_rate_limiter() -> None:
    r = RateLimiter(2)
    r.check(("t", "u"), 0)
    r.check(("t", "u"), 1)
    r.check(("t", "khac"), 1)
    with pytest.raises(RateLimited) as e:
        r.check(("t", "u"), 2)
    assert 1 <= e.value.retry_after <= 59
    r.check(("t", "u"), 61)


def test_confidence() -> None:
    assert _confidence({"segments": [{"avg_logprob": -0.1}, {"avg_logprob": -0.3}]}) == pytest.approx(0.8187, 1e-3)
    assert _confidence({"text": "x"}) is None


@pytest.mark.anyio
async def test_openai_asr_retries_then_opens_circuit() -> None:
    calls: list[httpx.Request] = []

    def ok(r: httpx.Request) -> httpx.Response:
        calls.append(r)
        if len(calls) == 1:
            return httpx.Response(503)
        return httpx.Response(200, json={"text": " Chi bộ 3 chưa nộp. ", "segments": [{"avg_logprob": -0.05}]})

    pcm = audio.decode(sample("wav", 1.0))
    asr = OpenAiAsr(httpx.AsyncClient(base_url="http://asr.test", transport=httpx.MockTransport(ok)), "viettel-asr-vi")
    t = await asr.transcribe(pcm, "vi-VN")
    assert t.text == "Chi bộ 3 chưa nộp." and t.confidence == pytest.approx(0.951, 1e-3) and len(calls) == 2
    body = calls[1].content
    assert b'name="language"\r\n\r\nvi' in body and b"RIFF" in body and b'name="model"' in body

    def down(_: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    bad = OpenAiAsr(httpx.AsyncClient(base_url="http://asr.test", transport=httpx.MockTransport(down)), "m",
                    failures_to_open=2)
    for _ in range(2):
        with pytest.raises(AsrUnavailable):
            await bad.transcribe(pcm, "vi-VN")
    with pytest.raises(AsrUnavailable, match="ngắt mạch"):
        await bad.transcribe(pcm, "vi-VN")

    def empty(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=json.dumps({"text": ""}))

    none = OpenAiAsr(httpx.AsyncClient(base_url="http://asr.test", transport=httpx.MockTransport(empty)), "m")
    with pytest.raises(Unrecognized):
        await none.transcribe(pcm, "vi-VN")
