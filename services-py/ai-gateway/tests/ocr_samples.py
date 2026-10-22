"""Bộ ảnh mẫu cho kiểm thử OCR: ảnh chụp văn bản rõ nét và cùng ảnh đó bị nhoè, dựng tất định bằng Pillow. Chữ
mẫu ghi trong siêu dữ liệu PNG `ocr-text` để bản OCR giả lập đọc lại."""

from __future__ import annotations

import io

from PIL import Image, ImageDraw, ImageFilter, PngImagePlugin

TEXT = """ĐẢNG BỘ KHỐI CÁC CƠ QUAN
BAN TỔ CHỨC
Số: 47-BC/BTCĐUK
Hà Nội, ngày 16 tháng 10 năm 2026
BÁO CÁO
Sơ kết công tác tổ chức cơ sở đảng 9 tháng năm 2026
Kính gửi: Ban Thường vụ Đảng uỷ Khối"""


def page(blur: float = 0.0, text: str = TEXT) -> bytes:
    img = Image.new("L", (1240, 1754), 255)
    d = ImageDraw.Draw(img)
    y = 120
    for line in text.splitlines():
        for k in range(3):  # chữ đậm để có biên rõ
            d.text((120 + k, y), line.encode("ascii", "replace").decode(), fill=0)
        y += 48
    for row in range(20):  # các dòng nội dung giả lập
        d.line((120, 600 + row * 40, 1100, 600 + row * 40), fill=40, width=3)
    if blur:
        img = img.filter(ImageFilter.GaussianBlur(blur))
    info = PngImagePlugin.PngInfo()
    info.add_itxt("ocr-text", text)
    out = io.BytesIO()
    img.save(out, "PNG", pnginfo=info)
    return out.getvalue()
