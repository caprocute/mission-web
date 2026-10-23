#!/usr/bin/env python3
"""VH-07: kiểm mọi đường dẫn bằng chứng ghi trong hồ sơ cấp độ 4 đều tồn tại trong repo.

Quét mọi tệp Markdown trong docs/attt/cap-do-4/ và kiểm hai loại tham chiếu:
- đường dẫn trong dấu backtick bắt đầu bằng một thư mục gốc của repo (deploy/, backend/, ...), tính từ gốc repo;
- liên kết Markdown tương đối tới tệp khác của hồ sơ, tính từ thư mục của tệp đang đọc.
Đồng thời kiểm hồ sơ không chứa địa chỉ IPv4 cụ thể (quy định: không ghi địa chỉ máy chủ vào repo).

Dùng: python3 docs/attt/cap-do-4/kiem-lien-ket.py   (mã thoát 1 khi có đường dẫn hỏng)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

HO_SO = Path(__file__).resolve().parent
GOC = HO_SO.parents[2]

THU_MUC_GOC = (
    "deploy/", "backend/", "frontend/", "services-go/", "services-py/", "infra/", "ci/", "ops/",
    "docs/", "contracts/", "data/", "analytics/", "wiki/", ".semgrep/",
)
TEP_GOC = {".gitleaks.toml", "Jenkinsfile", "AGENTS.md", "TIEN-DO-HE-THONG.md"}

BACKTICK = re.compile(r"`([^`\s]+)`")
LIEN_KET = re.compile(r"\]\(([^)\s#]+)(?:#[^)]*)?\)")
IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")


def la_duong_dan(tok: str) -> bool:
    return tok in TEP_GOC or tok.startswith(THU_MUC_GOC)


def kiem() -> int:
    loi: list[str] = []
    so_tham_chieu = 0
    for md in sorted(HO_SO.rglob("*.md")):
        ten = md.relative_to(GOC)
        for so_dong, dong in enumerate(md.read_text(encoding="utf-8").splitlines(), 1):
            for tok in BACKTICK.findall(dong):
                tok = tok.rstrip(".,;:")
                if not la_duong_dan(tok) or any(c in tok for c in "<>*{}"):
                    continue
                so_tham_chieu += 1
                if not (GOC / tok).exists():
                    loi.append(f"{ten}:{so_dong}: không có `{tok}`")
            for dich in LIEN_KET.findall(dong):
                if re.match(r"^[a-z]+:", dich):
                    continue
                so_tham_chieu += 1
                if not (md.parent / dich).resolve().exists():
                    loi.append(f"{ten}:{so_dong}: liên kết hỏng ({dich})")
            for ip in IPV4.findall(dong):
                if all(0 <= int(p) <= 255 for p in ip.split(".")):
                    loi.append(f"{ten}:{so_dong}: có địa chỉ IP {ip}")
    for dong in loi:
        print(dong)
    print(f"Đã kiểm {so_tham_chieu} tham chiếu, {len(loi)} lỗi.")
    return 1 if loi else 0


if __name__ == "__main__":
    sys.exit(kiem())
