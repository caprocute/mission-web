#!/usr/bin/env python3
"""Kiểm tra bộ hướng dẫn sử dụng (VH-05).

Văn bản hiển thị (bỏ chú thích HTML và đoạn mã) không được chứa mã thiết kế
(SCR-, QT-, BR-, MSG-, ADR-, EVT-), đường dẫn /api/ hay mã trạng thái thô dạng
CHU_HOA_GACH_DUOI. Mọi ảnh được tham chiếu phải tồn tại trong anh/.
Mã thoát 0 khi đạt, 1 khi có lỗi.
"""
import re
import sys
from pathlib import Path

THU_MUC = Path(__file__).resolve().parent
CAM = [
    (re.compile(r"\b(SCR|QT|BR|MSG|ADR|EVT|NFR|SEC)-\d"), "mã thiết kế"),
    (re.compile(r"/api/"), "đường dẫn API"),
    (re.compile(r"\b[A-Z]{2,}_[A-Z_]{2,}\b"), "mã trạng thái thô"),
]
ANH = re.compile(r"!\[[^\]]*\]\(([^)\s]+)\)")


def van_ban_hien_thi(noi_dung: str) -> str:
    noi_dung = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), noi_dung, flags=re.S)
    noi_dung = re.sub(r"```.*?```", lambda m: "\n" * m.group(0).count("\n"), noi_dung, flags=re.S)
    return re.sub(r"`[^`\n]*`", "", noi_dung)


def main() -> int:
    loi = []
    tep = sorted(THU_MUC.glob("*.md"))
    for p in tep:
        nd = p.read_text(encoding="utf-8")
        for so, dong in enumerate(van_ban_hien_thi(nd).splitlines(), 1):
            for mau, ten in CAM:
                for m in mau.finditer(dong):
                    loi.append(f"{p.name}:{so}: {ten} '{m.group(0)}' trong văn bản hiển thị")
        for m in ANH.finditer(nd):
            if not (THU_MUC / m.group(1)).is_file():
                loi.append(f"{p.name}: thiếu ảnh {m.group(1)}")
    for l in loi:
        print(l)
    print(f"Đã kiểm {len(tep)} tệp: {'đạt' if not loi else f'{len(loi)} lỗi'}.")
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(main())
