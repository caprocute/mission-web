#!/usr/bin/env python3
"""NEN-007 · Đối chiếu contracts/events/catalog.yaml với bảng EVT của baseline mục 7 và TL-03.

Dùng:
  python3 contracts/events/tools/doi-chieu-tl.py \
      /mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md \
      /mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md

So khớp 26 dòng theo: mã, tên nghiệp vụ, topic, bên phát, bên tiêu thụ (baseline);
topic, outbox nguồn, khoá, phân loại (TL-03 "Danh mục lược đồ EVT-01 đến EVT-26");
rồi so cột "Trường dữ liệu chính" của TL-03 với trường trong tệp .avsc (thiếu là lệch,
thêm chỉ liệt kê để người duyệt xem). In bảng đối chiếu, trả mã 1 nếu có lệch. Cần PyYAML.
"""
import json
import pathlib
import re
import sys

import yaml

CATALOG = pathlib.Path(__file__).resolve().parent.parent / "catalog.yaml"


def rows(md_path):
    out = {}
    for line in pathlib.Path(md_path).read_text(encoding="utf-8").splitlines():
        if line.startswith("| EVT-"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            out[cells[0]] = cells
    return out


def strip_note(text):
    """Bỏ phần chú thích trong ngoặc và dấu `...` của Markdown."""
    return re.sub(r"\s*\([^)]*\)", "", text).replace("`", "").strip()


def norm_outbox(text):
    t = text.replace("`", "").strip()
    if t.startswith("Producer ClickHouse"):
        return None
    if t == "outbox của module yêu cầu":
        return "*.outbox_event"
    return t


def norm_producer(text):
    t = strip_note(text)
    return "*" if t == "mọi module" else t


def main(baseline_md, tl03_md):
    base = rows(baseline_md)
    tl03 = rows(tl03_md)
    catalog = yaml.safe_load(CATALOG.read_text(encoding="utf-8"))
    events = catalog["events"]
    bad = 0
    print("| Mã | Topic | Khoá | Outbox | Phân loại | Tên | Phát | Tiêu thụ | Kết quả |")
    print("|---|---|---|---|---|---|---|---|---|")
    if len(events) != 26 or len(base) != 26 or len(tl03) != 26:
        print(f"Số dòng: catalog={len(events)}, baseline={len(base)}, TL-03={len(tl03)}")
        bad += 1
    for e in events:
        code = e["code"]
        b = base.get(code)
        t = tl03.get(code)
        if b is None or t is None:
            print(f"| {code} | thiếu ở baseline hoặc TL-03 |||||||  LỆCH |")
            bad += 1
            continue
        expected = {
            "topic": (t[1], b[2]),
            "key": (t[3],),
            "outbox": (norm_outbox(t[2]),),
            "classification": (t[5],),
            "name": (strip_note(b[1]),),
            "producer": (norm_producer(b[3]),),
            "consumers": ([c.strip() for c in strip_note(b[4]).split(",")],),
        }
        diffs = []
        for field, sources in expected.items():
            actual = e.get(field)
            for src in sources:
                src_val = src.replace("`", "") if isinstance(src, str) else src
                if actual != src_val:
                    diffs.append(f"{field}: catalog={actual!r} nguồn={src_val!r}")
        status = "khớp" if not diffs else "LỆCH: " + "; ".join(diffs)
        bad += bool(diffs)
        print(
            f"| {code} | {e['topic']} | {e['key']} | {e['outbox']} | {e['classification']} | "
            f"{e['name']} | {e['producer']} | {', '.join(e['consumers'])} | {status} |"
        )
    print(f"\nTổng: {len(events)} dòng, {bad} dòng lệch.")

    # Trường dữ liệu chính (TL-03) so với trường trong .avsc: thiếu là lỗi, thêm chỉ liệt kê.
    print("\n| Mã | Trường TL-03 thiếu trong lược đồ | Trường lược đồ thêm ngoài cột TL-03 |")
    print("|---|---|---|")
    for e in events:
        t = tl03.get(e["code"])
        if t is None:
            continue
        cell = re.sub(r"\([^)]*\)", "", t[4])
        tl_fields = {x.strip() for x in cell.split(",") if re.fullmatch(r"[a-z][A-Za-z0-9]*", x.strip())}
        schema = json.loads((CATALOG.parent / e["schema"]).read_text(encoding="utf-8"))
        avsc = {f["name"] for f in schema["fields"]}
        missing = sorted(tl_fields - avsc)
        extra = sorted(avsc - tl_fields)
        bad += bool(missing)
        print(f"| {e['code']} | {', '.join(missing) or '-'} | {', '.join(extra) or '-'} |")
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
