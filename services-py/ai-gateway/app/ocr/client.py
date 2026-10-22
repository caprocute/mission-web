"""Client OCR tiếng Việt cho ảnh và PDF (DV-14-05, TL-01 "Kiến trúc cổng mô hình"): dịch vụ OCR của Viettel AI qua
HTTP, có thời hạn, giới hạn đồng thời, thử lại một lần khi lỗi tạm thời và ngắt mạch khi lỗi liên tiếp (tương
đương Resilience4j của dịch vụ Java). Ảnh không đủ nét bị từ chối trước khi gửi (MSG-07-06). Chế độ "mock" là bản
giả lập cho dev và kiểm thử (YC-PT-13): PDF có lớp chữ đọc bằng pypdf, ảnh đọc chữ mẫu ghi trong siêu dữ liệu PNG
`ocr-text` hoặc chú thích JPEG."""

from __future__ import annotations

import asyncio
import io
import time
from dataclasses import dataclass
from typing import Protocol

import httpx
from PIL import Image, ImageFilter, ImageStat, UnidentifiedImageError

MIN_SHARPNESS = 800.0  # phương sai biên ảnh xám thu về cạnh dài 1.024 điểm ảnh (suy luận, đo trên bộ ảnh mẫu)
MIN_CONFIDENCE = 0.6
IMAGE_TYPES = ("image/jpeg", "image/png")


class Unreadable(Exception):
    """Tệp không đọc được: ảnh mờ, độ tin cậy OCR thấp, PDF quét không có chữ (AI-422-UNREADABLE)."""


class OcrUnavailable(Exception):
    """Dịch vụ OCR lỗi hoặc đang ngắt mạch."""


@dataclass(frozen=True)
class OcrPage:
    page_number: int
    text: str
    confidence: float


@dataclass(frozen=True)
class OcrResult:
    pages: list[OcrPage]
    model: str

    @property
    def text(self) -> str:
        return "\n".join(p.text for p in self.pages)


class Ocr(Protocol):
    @property
    def model(self) -> str: ...

    async def read(self, data: bytes, mime: str) -> OcrResult: ...


def sharpness(data: bytes) -> float:
    try:
        img = Image.open(io.BytesIO(data))
        img.load()
    except (UnidentifiedImageError, OSError) as e:
        raise Unreadable("không mở được ảnh") from e
    gray = img.convert("L")
    gray.thumbnail((1024, 1024))
    return float(ImageStat.Stat(gray.filter(ImageFilter.FIND_EDGES)).var[0])


def check_image(data: bytes, mime: str) -> None:
    if mime in IMAGE_TYPES and sharpness(data) < MIN_SHARPNESS:
        raise Unreadable("ảnh chưa đủ nét")


def _confident(r: OcrResult) -> OcrResult:
    pages = [p for p in r.pages if p.text.strip()]
    if not pages or sum(p.confidence for p in pages) / len(pages) < MIN_CONFIDENCE:
        raise Unreadable("độ tin cậy nhận dạng thấp")
    return OcrResult(pages, r.model)


class MockOcr:
    def __init__(self, model: str = "mock-ocr-vi") -> None:
        self._model = model

    @property
    def model(self) -> str:
        return self._model

    async def read(self, data: bytes, mime: str) -> OcrResult:
        check_image(data, mime)
        if mime == "application/pdf":
            from pypdf import PdfReader

            try:
                reader = PdfReader(io.BytesIO(data))
                pages = [OcrPage(i, (pg.extract_text() or "").strip(), 0.99) for i, pg in enumerate(reader.pages, 1)]
            except Exception as e:
                raise Unreadable("không đọc được PDF") from e
            return _confident(OcrResult(pages, self._model))
        img = Image.open(io.BytesIO(data))
        text = str(img.info.get("ocr-text") or img.info.get("comment", b"") or "")
        if isinstance(img.info.get("comment"), bytes):
            text = img.info["comment"].decode("utf-8", "replace")
        return _confident(OcrResult([OcrPage(1, text.strip(), 0.95)], self._model))


class ViettelOcr:
    """`POST {base}/v1/ocr` multipart (`file`, `lang=vi`), trả `{"model", "pages": [{"page","text","confidence"}]}`.
    Định dạng là suy luận khi chưa có tài liệu API chính thức; đổi ở đây khi có (wiki/14)."""

    def __init__(self, http: httpx.AsyncClient, model: str, *, concurrency: int = 4, timeout_s: float = 30,
                 failures_to_open: int = 5, open_s: float = 30) -> None:
        self._http = http
        self._model = model
        self._sem = asyncio.Semaphore(concurrency)
        self._timeout = timeout_s
        self._fails = 0
        self._limit = failures_to_open
        self._open_s = open_s
        self._open_until = 0.0

    @property
    def model(self) -> str:
        return self._model

    async def read(self, data: bytes, mime: str) -> OcrResult:
        check_image(data, mime)
        if time.monotonic() < self._open_until:
            raise OcrUnavailable("ngắt mạch")
        async with self._sem:
            for attempt in (1, 2):
                try:
                    r = await self._http.post("/v1/ocr", files={"file": ("tep", data, mime)}, data={"lang": "vi"},
                                              timeout=self._timeout)
                    if r.status_code == 422:
                        raise Unreadable("dịch vụ OCR không đọc được tệp")
                    r.raise_for_status()
                    body = r.json()
                    pages = [OcrPage(int(p.get("page") or i), str(p.get("text") or ""), float(p.get("confidence") or 0))
                             for i, p in enumerate(body.get("pages") or [], 1) if isinstance(p, dict)]
                    self._fails = 0
                    return _confident(OcrResult(pages, str(body.get("model") or self._model)))
                except Unreadable:
                    raise
                except (httpx.HTTPError, ValueError, TypeError) as e:
                    transient = not isinstance(e, httpx.HTTPStatusError) or e.response.status_code >= 500
                    if attempt == 1 and transient:
                        await asyncio.sleep(0.2)
                        continue
                    self._fails += 1
                    if self._fails >= self._limit:
                        self._open_until = time.monotonic() + self._open_s
                    raise OcrUnavailable(type(e).__name__) from e
        raise OcrUnavailable("không gọi được dịch vụ OCR")
