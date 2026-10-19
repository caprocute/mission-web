#!/usr/bin/env python3
"""Sinh docs/tasks/DANH-MUC-TOAN-HE-THONG.md từ docs/tasks/danh-muc-he-thong.tsv.

Dùng:  python3 scripts/sinh-danh-muc.py
Tệp TSV là nguồn của danh mục task toàn hệ thống (ID, tên, cỡ, phụ thuộc, truy vết,
luồng, nguồn trích, ghi chú). Script kiểm phụ thuộc trỏ tới mã có thật, phát hiện vòng,
tính tầng (số bước phụ thuộc dài nhất) và đường găng theo trọng số S=0,5 / M=1 / L=2.
"""
import collections
import csv
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "docs/tasks/danh-muc-he-thong.tsv")
OUT = os.path.join(ROOT, "docs/tasks/DANH-MUC-TOAN-HE-THONG.md")
WEIGHT = {"S": 0.5, "M": 1.0, "L": 2.0}
STREAMS = {
    "DP": "Điều phối (thread chính)",
    "B1": "Nền móng backend và CSDL",
    "B2": "Cấu hình, Policy Pack, OKR",
    "B3": "Kế hoạch, giao việc, minh chứng",
    "B4": "Đánh giá, hồ sơ số, giám sát, tính điểm",
    "B5": "Định danh, tổ chức, tenant, tích hợp",
    "B6": "Quy trình, sổ cái, ký số, tài liệu",
    "B7": "Thông báo, tìm kiếm, phân tích, AI",
    "B8": "Hạ tầng, CI/CD, quan sát, dự phòng",
    "B9": "Kiểm thử tích hợp, hiệu năng, ATTT, DR",
    "B10": "Di động",
    "B11": "Dữ liệu nghiệp vụ, hệ cũ, vận hành",
    "W": "Phân hệ web (các luồng web hiện có)",
}

groups, rows = [], []
with open(SRC, encoding="utf-8") as f:
    for r in csv.reader(f, delimiter="\t"):
        if not r or r[0].startswith("#ID"):
            continue
        if r[0].startswith("## "):
            groups.append((r[0][3:], r[1], []))
            continue
        r = (r + [""] * 8)[:8]
        rows.append(r)
        groups[-1][2].append(r)

ids = {r[0] for r in rows}
deps, external = {}, set()
for r in rows:
    ds = [d.strip() for d in re.split(r"[,;]", r[3]) if d.strip() and d.strip() not in ("–", "-")]
    deps[r[0]] = [d for d in ds if d in ids]
    external |= {d for d in ds if d not in ids and not d.startswith("WEB-0")}
if external:
    sys.exit(f"Phụ thuộc trỏ tới mã không có trong danh mục: {sorted(external)}")

size = {r[0]: r[2] for r in rows}
level, path = {}, {}


def walk(x, stack=()):
    if x in level:
        return
    if x in stack:
        sys.exit(f"Vòng phụ thuộc tại {x}")
    for d in deps[x]:
        walk(d, stack + (x,))
    level[x] = 1 + max((level[d] for d in deps[x]), default=0)
    best = max((path[d] for d in deps[x]), key=lambda p: p[0], default=(0.0, []))
    path[x] = (best[0] + WEIGHT.get(size[x], 1.0), best[1] + [x])


for x in ids:
    walk(x)

cards = {}
for p in glob.glob(os.path.join(ROOT, "docs/tasks/*.md")):
    m = re.match(r"([A-Z]+(?:-\d+)+)-[a-z]", os.path.basename(p))
    if m:
        cards[m.group(1)] = os.path.basename(p)

crit = max(path.values(), key=lambda p: p[0])
total = sum(WEIGHT.get(size[x], 1.0) for x in ids)
by_stream = collections.defaultdict(list)
for r in rows:
    by_stream[r[5]].append(r[0])


def link(x):
    return f"[{x}]({cards[x]})" if x in cards else x


out = [
    "> Tệp sinh tự động bằng `python3 scripts/sinh-danh-muc.py` từ `docs/tasks/danh-muc-he-thong.tsv`; sửa TSV rồi chạy lại, không sửa tay tệp này.",
    "",
    "# Danh mục task toàn hệ thống Mission",
    "",
    f"Danh mục có {len(rows)} task ngoài các task web WEB-001..065 đã có trong `docs/tasks/README.md`. "
    f"Tổng khối lượng quy đổi là {total:g} đơn vị (S = 0,5; M = 1; L = 2), đường găng dài {len(crit[1])} task "
    f"với {crit[0]:g} đơn vị. Cột \"Tầng\" là số bước phụ thuộc dài nhất tính từ gốc: các task cùng tầng và khác luồng "
    "có thể chạy song song. Thẻ task chi tiết nằm cạnh tệp này, theo mẫu `_MAU-TASK-HE-THONG.md`. "
    "Trạng thái thật của từng task xem trong git và `docs/tasks/README.md` do thread điều phối cập nhật.",
    "",
    "## Đường găng",
    "",
    " → ".join(link(x) for x in crit[1]),
    "",
    "## Luồng song song",
    "",
    "| Luồng | Phạm vi | Số task | Khối lượng |",
    "|---|---|---|---|",
]
for s, name in STREAMS.items():
    xs = by_stream.get(s, [])
    out.append(f"| {s} | {name} | {len(xs)} | {sum(WEIGHT.get(size[x], 1.0) for x in xs):g} |")
for g, title, rs in groups:
    out += ["", f"## {g} · {title}", "", "| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |", "|---|---|---|---|---|---|---|---|"]
    for r in rs:
        cells = [link(r[0]), r[1], r[2], r[3], r[4], r[5], str(level[r[0]]), r[7]]
        out.append("| " + " | ".join(c.replace("|", "\\|") for c in cells) + " |")
out.append("")
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(out))
print(f"Đã ghi {OUT}: {len(rows)} task, {len(cards)} thẻ, đường găng {len(crit[1])} task / {crit[0]:g} đơn vị")
