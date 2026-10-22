"""Kiểm thử không cần Docker của DV-14-05: trích số ký hiệu, ngày, trích yếu; độ nét ảnh; OCR giả lập; client OCR
Viettel với thử lại, ngắt mạch, ảnh không đọc được; ngưỡng khớp nhiệm vụ 0,5."""

from __future__ import annotations

import httpx
import pytest

from app.ocr import fields as fx
from app.ocr.client import MIN_SHARPNESS, MockOcr, OcrUnavailable, Unreadable, ViettelOcr, sharpness
from tests.ocr_samples import TEXT, page
from tests.test_rag import _pdf


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


def test_fields_from_sample_text() -> None:
    f = fx.extract(TEXT)
    assert f.doc_number == "47-BC/BTCĐUK"
    assert f.doc_date == "2026-10-16"
    assert f.doc_summary == "Báo cáo sơ kết công tác tổ chức cơ sở đảng 9 tháng năm 2026"


@pytest.mark.parametrize(("text", "number", "when"), [
    ("Số 12/CT-UBND\nngày 05/11/2026", "12/CT-UBND", "2026-11-05"),
    ("KH số 05-KH/ĐUK ngày 20 tháng 12 năm 2026", "05-KH/ĐUK", "2026-12-20"),
    ("Không có số, ngày 31/02/2026 sai", None, None),
])
def test_fields_variants(text: str, number: str | None, when: str | None) -> None:
    f = fx.extract(text)
    assert (f.doc_number, f.doc_date) == (number, when)


def test_match_threshold() -> None:
    tasks = [{"id": "0192f2c4-5d6e-7f80-91a2-b3c4d5e6f742", "code": "NV-0142",
              "title": "Báo cáo sơ kết công tác tổ chức cơ sở đảng"},
             {"id": "0192f2c4-5d6e-7f80-91a2-b3c4d5e6f743", "code": "NV-0143", "title": "Mua sắm thiết bị"}]
    best, alts = fx.match("Báo cáo sơ kết công tác tổ chức cơ sở đảng 9 tháng năm 2026", tasks)
    assert best is not None and best["code"] == "NV-0142" and best["confidence"] >= 0.5
    assert all(a["code"] != "NV-0142" for a in alts)
    best, _ = fx.match("Kế hoạch chuyển đổi số", tasks)
    assert best is None


def test_sharpness_separates_clear_and_blurred() -> None:
    clear, blurred = sharpness(page()), sharpness(page(blur=4))
    assert clear > MIN_SHARPNESS > blurred, (clear, blurred)


@pytest.mark.anyio
async def test_mock_ocr() -> None:
    ocr = MockOcr()
    r = await ocr.read(page(), "image/png")
    assert fx.extract(r.text).doc_number == "47-BC/BTCĐUK"
    with pytest.raises(Unreadable):
        await ocr.read(page(blur=4), "image/png")
    assert "Bao cao so ket" in (await ocr.read(_pdf("Bao cao so ket"), "application/pdf")).text
    with pytest.raises(Unreadable):
        await ocr.read(_pdf(""), "application/pdf")


@pytest.mark.anyio
async def test_viettel_client_retry_breaker_unreadable() -> None:
    calls: list[int] = []
    plan = [503, 200, 422, 500, 500, 500, 500]

    def handler(req: httpx.Request) -> httpx.Response:
        code = plan[len(calls)] if len(calls) < len(plan) else 500
        calls.append(code)
        if code == 200:
            return httpx.Response(200, json={"model": "viettel-ocr-2026.2",
                                             "pages": [{"page": 1, "text": TEXT, "confidence": 0.97}]})
        return httpx.Response(code)

    http = httpx.AsyncClient(base_url="http://ocr.test", transport=httpx.MockTransport(handler))
    ocr = ViettelOcr(http, "viettel-ocr", failures_to_open=1, open_s=60)
    r = await ocr.read(b"%PDF-1.4", "application/pdf")  # 503 rồi thử lại được 200
    assert r.model == "viettel-ocr-2026.2" and calls == [503, 200]
    with pytest.raises(Unreadable):
        await ocr.read(b"%PDF-1.4", "application/pdf")
    with pytest.raises(OcrUnavailable):
        await ocr.read(b"%PDF-1.4", "application/pdf")  # 500, 500: hết lượt thử, mở mạch
    n = len(calls)
    with pytest.raises(OcrUnavailable):
        await ocr.read(b"%PDF-1.4", "application/pdf")
    assert len(calls) == n  # mạch đang mở: không gọi dịch vụ
    with pytest.raises(Unreadable):
        await ocr.read(page(blur=4), "image/png")  # ảnh mờ bị từ chối trước khi gửi
