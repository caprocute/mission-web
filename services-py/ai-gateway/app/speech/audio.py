"""Kiểm và chuẩn hoá đoạn ghi âm hoàn toàn trong bộ nhớ (DV-14-07). Kiểu tệp xác định từ chữ ký đầu tệp và việc
giải mã được thật, không tin `Content-Type` khai báo. Thời lượng tính từ số mẫu đã giải mã, không tin thời lượng
ghi trong vỏ tệp. Đầu ra là WAV PCM 16 bit, 16 kHz, một kênh, định dạng mà bộ nhận dạng cần. Giải mã bằng PyAV
(thư viện FFmpeg đóng gói sẵn), đọc từ BytesIO nên không ghi tệp tạm."""

from __future__ import annotations

import io
import wave
from dataclasses import dataclass

import av

MAX_BYTES = 2 * 1024 * 1024
MAX_SECONDS = 60.0
RATE = 16000
# Chữ ký đầu tệp của bốn kiểu hợp đồng cho phép và tên vỏ tệp FFmpeg tương ứng.
_FORMATS = {"audio/webm": ("matroska", "webm"), "audio/ogg": ("ogg",), "audio/wav": ("wav",),
            "audio/mp4": ("mov", "mp4", "m4a")}


class BadAudio(Exception):
    """Không phải âm thanh thuộc bốn kiểu cho phép, hoặc giải mã không được."""


class TooLong(Exception):
    """Quá 60 giây hoặc quá 2 MB (AI-413-AUDIO-TOO-LARGE)."""


@dataclass(frozen=True)
class Pcm:
    wav: bytes = b""
    duration_ms: int = 0
    mime: str = ""
    # Lời thoại mẫu ghi trong siêu dữ liệu `transcript` hoặc `comment`, chỉ bản giả lập đọc (YC-PT-13).
    hint: str = ""


def sniff(data: bytes) -> str | None:
    if data[:4] == b"\x1a\x45\xdf\xa3":
        return "audio/webm"
    if data[:4] == b"OggS":
        return "audio/ogg"
    if data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        return "audio/wav"
    if data[4:8] == b"ftyp":
        return "audio/mp4"
    return None


def decode(data: bytes, max_seconds: float = MAX_SECONDS) -> Pcm:
    if len(data) > MAX_BYTES:
        raise TooLong("quá 2 MB")
    mime = sniff(data)
    if mime is None:
        raise BadAudio("không phải tệp âm thanh được hỗ trợ")
    out = io.BytesIO()
    samples = 0
    limit = int(max_seconds * RATE)
    try:
        with av.open(io.BytesIO(data), "r") as c:
            if not any(n in _FORMATS[mime] for n in c.format.name.split(",")) or not c.streams.audio:
                raise BadAudio("nội dung không khớp kiểu tệp")
            meta = {**c.metadata, **c.streams.audio[0].metadata}
            hint = next((str(v) for k, v in meta.items() if k.lower() in ("transcript", "comment")), "")
            resampler = av.AudioResampler(format="s16", layout="mono", rate=RATE)
            with wave.open(out, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(RATE)
                for frame in c.decode(c.streams.audio[0]):
                    for f in resampler.resample(frame):
                        samples += f.samples
                        if samples > limit:
                            raise TooLong("quá 60 giây")
                        w.writeframes(bytes(f.planes[0])[: f.samples * 2])
                for f in resampler.resample(None):
                    samples += f.samples
                    w.writeframes(bytes(f.planes[0])[: f.samples * 2])
    except (TooLong, BadAudio):
        raise
    except (av.FFmpegError, ValueError, IndexError, UnicodeError) as e:
        raise BadAudio("giải mã không được") from e
    if samples > limit:
        raise TooLong("quá 60 giây")
    if samples == 0:
        raise BadAudio("không có âm thanh")
    return Pcm(out.getvalue(), samples * 1000 // RATE, mime, hint.strip())
