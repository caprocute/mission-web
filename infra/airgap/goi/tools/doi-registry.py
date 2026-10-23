#!/usr/bin/env python3
"""Bộ lọc manifest (INF-23, dùng sau helm template): trỏ mọi ảnh trong manifest sang Harbor nội bộ của khách hàng, giữ nguyên digest.

Đọc bảng đối chiếu do tools/load-images.sh ghi (ảnh gốc<TAB>ảnh đã nạp) và thay đúng tên kho của từng ảnh gốc, kể cả
ảnh bên thứ ba (quay.io, ghcr.io…) và dạng tách repository/digest trong CR. Ảnh còn trỏ ra registry ngoài bảng thì
dừng với lỗi, vì trong mạng cách ly pod đó sẽ không kéo được ảnh.
Cách gọi: helm template … | doi-registry.py <anh-da-nap.tsv> > manifest.yaml
"""
import re
import sys


def main():
    bang = {}
    for dong in open(sys.argv[1], encoding="utf-8"):
        goc, moi = dong.rstrip("\n").split("\t")
        bang[goc.split("@")[0]] = moi.split("@")[0]
    van_ban = sys.stdin.read()
    mau = re.compile(r"(?<![\w./-])(" + "|".join(re.escape(k) for k in sorted(bang, key=len, reverse=True)) + r")(?=[@\"'\s]|$)",
                     re.M)
    van_ban = mau.sub(lambda m: bang[m.group(1)], van_ban)
    con = sorted(set(re.findall(r"^\s*-?\s*image:\s*[\"']?([^\s\"']+@sha256:[0-9a-f]{64})", van_ban, re.M)))
    kho = {v.split("/")[0] for v in bang.values()}
    lot = [x for x in con if x.split("/")[0] not in kho]
    if lot:
        sys.exit("doi-registry: ảnh chưa có trong gói: " + ", ".join(lot))
    sys.stdout.write(van_ban)
    return 0


if __name__ == "__main__":
    sys.exit(main())
