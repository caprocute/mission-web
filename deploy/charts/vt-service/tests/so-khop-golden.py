#!/usr/bin/env python3
"""So khớp manifest do vt-service sinh với golden TL-04 mục 5.5 (INF-01).

Quy tắc: mọi trường có trong golden phải có trong bản render với cùng giá trị (golden là tập con).
Danh sách so theo phần tử: mỗi phần tử golden phải khớp ít nhất một phần tử của bản render.
Trường bỏ qua: image (golden dùng digest giữ chỗ REPLACE_WITH_DIGEST_FROM_CI).
Dùng: so-khop-golden.py <golden.yaml> <render.yaml>
"""
import sys

import yaml



def chuan(v):
    if isinstance(v, bool):
        return str(v).lower()
    if isinstance(v, (int, float)):
        return str(int(v)) if float(v).is_integer() else str(v)
    return str(v)


def khop(g, r, duong):
    loi = []
    if isinstance(g, dict):
        if not isinstance(r, dict):
            return [f"{'.'.join(duong)}: cần object, bản render là {type(r).__name__}"]
        for k, gv in g.items():
            p = duong + [str(k)]
            if k == "image" and any(x.startswith("containers") for x in duong):
                continue
            if k not in r:
                loi.append(f"{'.'.join(p)}: thiếu trong bản render")
            else:
                loi += khop(gv, r[k], p)
    elif isinstance(g, list):
        if not isinstance(r, list):
            return [f"{'.'.join(duong)}: cần danh sách"]
        for i, gp in enumerate(g):
            if not any(not khop(gp, rp, duong) for rp in r):
                ung_vien = [khop(gp, rp, duong + [f"[{i}]"]) for rp in r]
                ly_do = min(ung_vien, key=len) if ung_vien else ["danh sách rỗng"]
                loi.append(f"{'.'.join(duong)}[{i}]: không phần tử nào khớp; gần nhất: {ly_do[:3]}")
    else:
        if chuan(g) != chuan(r):
            loi.append(f"{'.'.join(duong)}: golden={g!r} render={r!r}")
    return loi


def main():
    golden = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
    render = [d for d in yaml.safe_load_all(open(sys.argv[2])) if d]
    tong = []
    for g in golden:
        dich = [r for r in render if r.get("kind") == g["kind"] and r["metadata"]["name"] == g["metadata"]["name"]]
        if not dich:
            tong.append(f"{g['kind']}/{g['metadata']['name']}: không có trong bản render")
            continue
        for l in khop(g, dich[0], [g["kind"]]):
            tong.append(l)
    if tong:
        print("GOLDEN LỆCH:")
        for l in tong:
            print("  -", l)
        sys.exit(1)
    print(f"Golden khớp: {', '.join(g['kind'] for g in golden)} ({len(golden)} tài nguyên)")


if __name__ == "__main__":
    main()
