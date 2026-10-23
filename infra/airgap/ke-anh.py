#!/usr/bin/env python3
"""Sinh tệp kê ảnh của bản phát hành cách ly (INF-23, OPS-12, SEC-45, SEC-55).

Đọc bản render `helm template` của vt-platform với values-airgap.yaml (đối số --render, hoặc tự render khi có helm),
gom mọi tham chiếu ảnh trong tài nguyên Kubernetes (khoá image, imageName của CNPG, khối image{repository, digest}
của CR) và ghi deploy/release/<phiên-bản>/images.yaml. Trả mã lỗi 1 khi còn ảnh tham chiếu theo thẻ (không có
@sha256:<64 hex>) hoặc digest giả toàn số 0, để bản cách ly không bao giờ mang ảnh trôi phiên bản.

Cách gọi:
  ke-anh.py --phien-ban okr-lts-2027.1.0 [--render tệp.yaml] [--values tệp]... [--ra deploy/release] [--cho-phep-gia]
--cho-phep-gia chỉ dùng trong kiểm thử: chấp nhận digest giả của bản render CI nhưng vẫn từ chối ảnh theo thẻ.
"""
import argparse
import os
import re
import subprocess
import sys

import yaml

GOC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PLATFORM = os.path.join(GOC, "deploy", "charts", "vt-platform")
DIGEST = re.compile(r"^[^\s@]+@sha256:[0-9a-f]{64}$")
GIA = re.compile(r"@sha256:0{64}$")


def gom(nut, ra, duong=""):
    """Tìm tham chiếu ảnh trong cây tài nguyên; ghi kèm đường dẫn để báo lỗi dễ tra."""
    if isinstance(nut, dict):
        for k, v in nut.items():
            p = f"{duong}.{k}"
            if k in ("image", "imageName") and isinstance(v, str) and v.strip():
                ra.append((v.strip(), p))
            elif k == "image" and isinstance(v, dict) and v.get("repository"):
                tham_chieu = v["repository"]
                if v.get("digest"):
                    tham_chieu += "@" + v["digest"]
                elif v.get("tag"):
                    tham_chieu += ":" + str(v["tag"])
                ra.append((tham_chieu, p))
            else:
                gom(v, ra, p)
    elif isinstance(nut, list):
        for i, x in enumerate(nut):
            gom(x, ra, f"{duong}[{i}]")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phien-ban", required=True)
    ap.add_argument("--render")
    ap.add_argument("--values", action="append", default=[])
    ap.add_argument("--ra", default=os.path.join(GOC, "deploy", "release"))
    ap.add_argument("--cho-phep-gia", action="store_true")
    a = ap.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]*", a.phien_ban):
        sys.exit("ke-anh: phiên bản chỉ gồm chữ thường, số, dấu chấm, gạch ngang")

    if a.render:
        van_ban = open(a.render, encoding="utf-8").read()
    else:
        lenh = ["helm", "template", "vt", PLATFORM, "-f", os.path.join(PLATFORM, "values-airgap.yaml")]
        for f in a.values:
            lenh += ["-f", f]
        van_ban = subprocess.run(lenh, check=True, capture_output=True, text=True).stdout

    anh = {}
    for d in yaml.safe_load_all(van_ban):
        if not d:
            continue
        ra = []
        gom(d.get("spec", {}), ra, "spec")
        for tc, p in ra:
            anh.setdefault(tc, []).append(f"{d['kind']}/{d['metadata']['name']}:{p}")

    loi = []
    for tc, noi in sorted(anh.items()):
        if not DIGEST.match(tc):
            loi.append(f"ảnh theo thẻ, không theo digest: {tc} ({noi[0]})")
        elif GIA.search(tc) and not a.cho_phep_gia:
            loi.append(f"digest giả chưa được pipeline thay: {tc} ({noi[0]})")
    if loi:
        for m in loi:
            print("  LỖI:", m, file=sys.stderr)
        print(f"ke-anh: {len(loi)}/{len(anh)} ảnh không đạt, không ghi tệp kê", file=sys.stderr)
        return 1

    ngoai = sorted({tc.split("/")[0] for tc in anh if not tc.startswith("harbor.vt.local/")})
    if ngoai:
        # Ảnh bên thứ ba vẫn theo digest nên vẫn đóng gói được; tools/load-images.sh nạp cả chúng vào Harbor nội bộ.
        print(f"ke-anh: chú ý, có ảnh từ registry ngoài Harbor (cần mirror theo SEC-55): {', '.join(ngoai)}")
    thu_muc = os.path.join(a.ra, a.phien_ban)
    os.makedirs(thu_muc, exist_ok=True)
    tep = os.path.join(thu_muc, "images.yaml")
    with open(tep, "w", encoding="utf-8") as f:
        f.write(f"# TỆP SINH TỰ ĐỘNG bởi infra/airgap/ke-anh.py (INF-23); không sửa tay.\n"
                f"# Mọi ảnh của bản {a.phien_ban} theo digest, render từ vt-platform với values-airgap.yaml.\n")
        yaml.safe_dump({"release": a.phien_ban, "images": sorted(anh)}, f, allow_unicode=True, sort_keys=False)
    print(f"ke-anh: {len(anh)} ảnh theo digest → {os.path.relpath(tep, GOC)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
