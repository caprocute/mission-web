#!/usr/bin/env python3
"""Kiểm các PolicyException trong exceptions/ và ho-so/ (INF-07): đủ năm chú thích vt.io/*, ngày hết hạn đúng
định dạng và chưa qua, thời hạn không quá 180 ngày kể từ hôm nay. Tệp bắt đầu bằng "_" là mẫu, bỏ qua.
Dùng: kiem-ngoai-le.py [--ngay YYYY-MM-DD]; trả mã 1 khi có ngoại lệ sai hoặc đã hết hạn."""
import argparse, datetime as dt, glob, os, sys, yaml

D = os.path.dirname(os.path.abspath(__file__))
CAN = ["vt.io/ly-do", "vt.io/nguoi-xin", "vt.io/nguoi-duyet", "vt.io/het-han", "vt.io/phieu"]
ap = argparse.ArgumentParser(); ap.add_argument("--ngay"); a = ap.parse_args()
hom_nay = dt.date.fromisoformat(a.ngay) if a.ngay else dt.date.today()
loi = 0; dem = 0
for f in sorted(glob.glob(f"{D}/exceptions/*.yaml") + glob.glob(f"{D}/ho-so/*/*.yaml")):
    if os.path.basename(f).startswith("_"):
        continue
    for d in yaml.safe_load_all(open(f)):
        if not d or d.get("kind") != "PolicyException":
            continue
        dem += 1
        ten = f"{os.path.relpath(f, D)}:{d['metadata']['name']}"
        ann = d["metadata"].get("annotations") or {}
        thieu = [k for k in CAN if not str(ann.get(k, "")).strip()]
        if thieu:
            loi += 1; print(f"SAI {ten}: thiếu chú thích {', '.join(thieu)}"); continue
        try:
            han = dt.date.fromisoformat(str(ann["vt.io/het-han"]))
        except ValueError:
            loi += 1; print(f"SAI {ten}: vt.io/het-han phải dạng YYYY-MM-DD"); continue
        if han < hom_nay:
            loi += 1; print(f"HẾT HẠN {ten}: {han}")
        elif (han - hom_nay).days > 180:
            loi += 1; print(f"SAI {ten}: hạn {han} quá 180 ngày")
print(f"{dem} ngoại lệ, {loi} lỗi")
sys.exit(1 if loi else 0)
