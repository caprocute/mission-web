#!/usr/bin/env python3
"""Dựng bản in PDF cho bộ hướng dẫn sử dụng (VH-05) bằng Pandoc.

Mỗi tệp .md trong thư mục này sinh một tệp PDF cùng tên trong ban-in/.
Cần pandoc và một bộ dựng PDF hỗ trợ Unicode (mặc định xelatex; đặt biến
môi trường PDF_ENGINE để đổi, ví dụ weasyprint hoặc wkhtmltopdf). Khi máy
chưa có bộ dựng PDF, công cụ sinh tệp HTML độc lập (ảnh nhúng sẵn) để mở
bằng trình duyệt và in hoặc lưu thành PDF.
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

THU_MUC = Path(__file__).resolve().parent
RA = THU_MUC / "ban-in"


def main() -> int:
    if not shutil.which("pandoc"):
        print("Chưa cài pandoc; cài pandoc (https://pandoc.org) rồi chạy lại.", file=sys.stderr)
        return 2
    may = os.environ.get("PDF_ENGINE", "xelatex")
    duoi = ".pdf" if shutil.which(may) else ".html"
    if duoi == ".html":
        print(f"Không thấy bộ dựng PDF '{may}'; sinh HTML để in từ trình duyệt.")
    RA.mkdir(exist_ok=True)
    loi = 0
    for p in sorted(THU_MUC.glob("*.md")):
        dich = RA / (p.stem + duoi)
        lenh = ["pandoc", p.name, "-o", str(dich), "--resource-path", str(THU_MUC), "-V", "lang=vi"]
        if duoi == ".pdf":
            lenh += [f"--pdf-engine={may}", "-V", "mainfont=DejaVu Sans", "-V", "geometry:margin=2cm"]
        else:
            lenh += ["--standalone", "--embed-resources", "--metadata", f"pagetitle={p.stem}"]
        kq = subprocess.run(lenh, cwd=THU_MUC, capture_output=True, text=True)
        if kq.returncode:
            loi += 1
            print(f"Lỗi khi dựng {p.name}:\n{kq.stderr}", file=sys.stderr)
        else:
            print(f"Đã dựng {dich.relative_to(THU_MUC)}")
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(main())
