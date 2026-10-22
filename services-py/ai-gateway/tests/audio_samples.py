"""Đoạn ghi âm mẫu dựng trong bộ nhớ bằng PyAV: âm sin 440 Hz (không có tiếng người thật hay dữ liệu cá nhân), lời
thoại mẫu ghi trong siêu dữ liệu `comment` để bản nhận dạng giả lập đọc."""

from __future__ import annotations

import array
import io
import math

import av

CODECS = {"webm": "libopus", "ogg": "libopus", "wav": "pcm_s16le", "mp4": "aac"}
SAY = "Chi bộ 3 chưa nộp biên bản sinh hoạt tháng mười."


def clip(fmt: str = "webm", seconds: float = 2.0, say: str = SAY) -> bytes:
    buf = io.BytesIO()
    with av.open(buf, "w", format=fmt) as c:
        if say:
            c.metadata["comment"] = say
        codec = CODECS[fmt]
        s = c.add_stream(codec, rate=48000 if codec == "libopus" else 16000)
        assert isinstance(s, av.AudioStream)
        s.layout = "mono"
        rate = s.rate
        size = 960 if codec == "libopus" else 1024
        total, t = int(seconds * rate), 0
        while t < total:
            k = min(size, total - t)
            pcm = array.array("h", (int(6000 * math.sin(2 * math.pi * 440 * (t + i) / rate)) for i in range(k)))
            fr = av.AudioFrame(format="s16", layout="mono", samples=k)
            fr.planes[0].update(pcm.tobytes())
            fr.sample_rate = rate
            fr.pts = t
            for pkt in s.encode(fr):
                c.mux(pkt)
            t += k
        for pkt in s.encode(None):
            c.mux(pkt)
    return buf.getvalue()
