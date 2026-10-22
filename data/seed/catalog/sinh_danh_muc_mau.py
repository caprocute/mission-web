#!/usr/bin/env python3
"""Sinh tệp Excel danh mục công việc mẫu theo khối (DL-06).

Đọc `danh-muc-mau.csv` (nguồn duy nhất, UTF-8), kiểm từng dòng theo cùng quy tắc máy chủ dùng khi
nhập (`CatalogSheet` và `CatalogRules` của mission-core, BR-04-14, BR-04-03) rồi ghi một tệp
`danh-muc-mau-<khối>.xlsx` cho mỗi khối K1..K9. Tệp có đúng tiêu đề cột mà `importCatalogExcel`
nhận; cột "Ghi chú" máy chủ bỏ qua và chỉ để đánh dấu mọi dòng là mục mẫu.

Cách dùng:
    python3 sinh_danh_muc_mau.py            # kiểm rồi sinh tệp .xlsx cạnh tệp này
    python3 sinh_danh_muc_mau.py --kiem     # chỉ kiểm, không ghi tệp

Mã thoát 1 khi có dòng sai. Cần openpyxl.
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
NGUON = HERE / "danh-muc-mau.csv"
KHOI = [f"K{i}" for i in range(1, 10)]

# Tiêu đề cột đúng nhãn SCR-0401 "Nhập từ Excel"; phần trong ngoặc máy chủ bỏ qua khi nhận diện.
TIEU_DE = [
    ("ma", "Mã công việc"),
    ("ten", "Tên công việc"),
    ("san_pham", "Sản phẩm"),
    ("don_vi_tinh", "Đơn vị tính"),
    ("muc_kho", "Mức khó (1-5)"),
    ("diem_chuan", "Điểm chuẩn (0-100)"),
    ("tieu_chuan", "Tiêu chuẩn chất lượng"),
    ("minh_chung", "Minh chứng yêu cầu"),
    ("nhom", "Nhóm mặc định (Thường xuyên / Then chốt)"),
    ("thoi_han", "Thời hạn chuẩn (ngày làm việc)"),
]
GHI_CHU = "Ghi chú"
DANH_DAU_MAU = "Mục mẫu DL-06, giả lập; tenant rà soát và sửa trước khi áp dụng"

# Từ mơ hồ mặc định của Policy Pack (CatalogPolicy.vagueWords), BR-04-03.
TU_MO_HO = ["tăng cường", "đẩy mạnh", "kịp thời", "nâng cao", "tích cực", "chú trọng"]

MA = re.compile(r"TC-([A-Z0-9]{1,8})-(\d{1,4})")
NHOM = {"thuongxuyen": "Thường xuyên", "thenchot": "Then chốt"}
DO_DAI = {"ten": 500, "san_pham": 500, "don_vi_tinh": 50, "tieu_chuan": 2000, "minh_chung": 1000}


def bo_dau(s: str) -> str:
    """Bỏ dấu tiếng Việt, chữ thường (giống AccentFold của máy chủ)."""
    s = s.replace("đ", "d").replace("Đ", "D")
    s = unicodedata.normalize("NFD", s)
    return "".join(c for c in s if unicodedata.category(c) != "Mn").lower()


def tu_mo_ho(ten: str) -> str | None:
    gap = " " + re.sub(r"[^a-z0-9]+", " ", bo_dau(ten)) + " "
    for w in TU_MO_HO:
        k = re.sub(r"[^a-z0-9]+", " ", bo_dau(w)).strip()
        if k and f" {k} " in gap:
            return w
    return None


def kiem_dong(d: dict[str, str]) -> list[str]:
    """Lỗi của một dòng nguồn; danh sách rỗng là hợp lệ."""
    loi: list[str] = []
    khoi = (d.get("khoi") or "").strip()
    if khoi not in KHOI:
        loi.append(f"khối '{khoi}' không thuộc K1..K9")
    ma = (d.get("ma") or "").strip()
    m = MA.fullmatch(ma)
    if not m or len(ma) > 20:
        loi.append(f"mã '{ma}' không đúng dạng TC-xx-nn")
    elif m.group(1) != khoi:
        loi.append(f"mã '{ma}' không mang tiền tố khối {khoi}")
    for k, toi_da in DO_DAI.items():
        v = (d.get(k) or "").strip()
        if not v:
            loi.append(f"thiếu {k}")
        elif len(v) > toi_da:
            loi.append(f"{k} dài quá {toi_da} ký tự")
    w = tu_mo_ho(d.get("ten") or "")
    if w:
        loi.append(f"tên chứa từ mơ hồ '{w}'")
    try:
        muc = int(d.get("muc_kho") or "")
        if not 1 <= muc <= 5:
            loi.append("mức khó ngoài 1..5")
    except ValueError:
        loi.append("mức khó không phải số nguyên")
    try:
        diem = float(d.get("diem_chuan") or "")
        if not 0 <= diem <= 100:
            loi.append("điểm chuẩn ngoài 0..100")
    except ValueError:
        loi.append("điểm chuẩn không phải số")
    if re.sub(r"[^a-z]+", "", bo_dau(d.get("nhom") or "")) not in NHOM:
        loi.append("nhóm phải là Thường xuyên hoặc Then chốt")
    han = (d.get("thoi_han") or "").strip()
    if han and (not han.isdigit() or int(han) < 1):
        loi.append("thời hạn chuẩn phải là số ngày lớn hơn 0")
    return loi


def doc_nguon(path: Path = NGUON) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def kiem(dong: list[dict[str, str]]) -> list[str]:
    """Lỗi toàn bộ nguồn: lỗi từng dòng, mã trùng, khối thiếu hoặc thiếu một trong hai nhóm."""
    loi: list[str] = []
    da_co: set[str] = set()
    for i, d in enumerate(dong, start=2):
        for e in kiem_dong(d):
            loi.append(f"dòng {i}: {e}")
        ma = (d.get("ma") or "").strip()
        if ma in da_co:
            loi.append(f"dòng {i}: mã '{ma}' trùng")
        da_co.add(ma)
    for k in KHOI:
        nhom = {d["nhom"].strip() for d in dong if d.get("khoi") == k}
        if not nhom:
            loi.append(f"khối {k} chưa có mục nào")
        elif nhom != set(NHOM.values()):
            loi.append(f"khối {k} phải có cả mục Thường xuyên và Then chốt")
    return loi


def so(v: str) -> int | float:
    """Số nguyên ghi thành ô số nguyên để máy chủ đọc ra \"30\" chứ không phải \"30.0\"."""
    f = float(v)
    return int(f) if f.is_integer() else f


def ghi_xlsx(dong: list[dict[str, str]], khoi: str, dich: Path) -> int:
    from openpyxl import Workbook
    from openpyxl.styles import Font

    wb = Workbook()
    ws = wb.active
    ws.title = "Danh mục"
    ws.append([nhan for _, nhan in TIEU_DE] + [GHI_CHU])
    for c in ws[1]:
        c.font = Font(bold=True)
    n = 0
    for d in dong:
        if d["khoi"] != khoi:
            continue
        ws.append(
            [
                d["ma"],
                d["ten"],
                d["san_pham"],
                d["don_vi_tinh"],
                int(d["muc_kho"]),
                so(d["diem_chuan"]),
                d["tieu_chuan"],
                d["minh_chung"],
                d["nhom"],
                int(d["thoi_han"]) if d["thoi_han"] else None,
                DANH_DAU_MAU,
            ]
        )
        n += 1
    for col, rong in zip("ABCDEFGHIJK", (12, 48, 32, 12, 10, 12, 60, 48, 16, 14, 40)):
        ws.column_dimensions[col].width = rong
    ws.freeze_panes = "A2"
    # Cố định siêu dữ liệu để tệp sinh lại không đổi nội dung khi nguồn không đổi.
    from datetime import datetime

    moc = datetime(2026, 10, 22)
    wb.properties.created = moc
    wb.properties.modified = moc
    wb.properties.creator = "Mission DL-06"
    wb.save(dich)
    return n


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--kiem", action="store_true", help="chỉ kiểm nguồn, không ghi tệp")
    ap.add_argument("--dich", type=Path, default=HERE, help="thư mục ghi tệp .xlsx")
    a = ap.parse_args(argv)
    dong = doc_nguon()
    loi = kiem(dong)
    for e in loi:
        print(f"LỖI {e}", file=sys.stderr)
    if loi:
        return 1
    print(f"Nguồn hợp lệ: {len(dong)} mục, {len(KHOI)} khối.")
    if a.kiem:
        return 0
    for k in KHOI:
        dich = a.dich / f"danh-muc-mau-{k}.xlsx"
        print(f"{dich.name}: {ghi_xlsx(dong, k, dich)} mục")
    return 0


if __name__ == "__main__":
    sys.exit(main())
