#!/usr/bin/env python3
"""Bộ lọc manifest (INF-23, dùng sau helm template): trỏ mọi ảnh trong manifest sang Harbor nội bộ của khách hàng, giữ nguyên digest.

Đọc bảng đối chiếu do tools/load-images.sh ghi (ảnh gốc<TAB>ảnh đã nạp) và thay đúng tên kho của từng ảnh gốc, kể cả
ảnh bên thứ ba (quay.io, ghcr.io…) và dạng tách repository/digest trong CR. Ảnh còn trỏ ra registry ngoài bảng thì
dừng với lỗi, vì trong mạng cách ly pod đó sẽ không kéo được ảnh.
Cách gọi: helm template … | doi-registry.py <anh-da-nap.tsv> > manifest.yaml
Mô hình đám mây riêng (VH-06) đi qua Argo CD nên không lọc được bản render; cùng bảng đối chiếu đó sinh tệp values
global.doiKhoAnh để cell-base dựng chính sách Kyverno vt-doi-kho-anh đổi ảnh lúc tạo pod:
          doi-registry.py --sinh-doi-kho-anh <anh-da-nap.tsv> > values-doi-kho-anh.yaml
"""
import os
import re
import sys


def sinh_doi_kho_anh(tep):
    """Rút tiền tố từ bảng đối chiếu: phần đuôi chung của ảnh gốc và ảnh đã nạp là đường dẫn giữ nguyên, phần đầu là
    tiền tố cần đổi. Hai dòng cho cùng tiền tố gốc mà khác tiền tố đích thì dừng, vì một luật đổi không phục vụ được cả hai."""
    bang = {}
    for dong in open(tep, encoding="utf-8"):
        if not dong.strip():
            continue
        goc, moi = (x.split("@")[0] for x in dong.rstrip("\n").split("\t"))
        duoi = os.path.commonprefix([goc[::-1], moi[::-1]])[::-1]
        duoi = duoi[duoi.find("/") + 1:] if "/" in duoi else ""
        tg, td = goc[:len(goc) - len(duoi)], moi[:len(moi) - len(duoi)]
        if not tg.endswith("/") or not td.endswith("/"):
            sys.exit(f"doi-registry: không rút được tiền tố từ dòng {goc} → {moi}")
        if bang.get(tg, td) != td:
            sys.exit(f"doi-registry: tiền tố {tg} trỏ tới hai đích {bang[tg]} và {td}")
        bang[tg] = td
    for tg in bang:
        if any(td.startswith(tg) for td in bang.values()):
            sys.exit(f"doi-registry: tiền tố đích bắt đầu bằng tiền tố gốc {tg}, luật đổi sẽ chồng lên nhau")
    print("# Sinh bởi doi-registry.py --sinh-doi-kho-anh (VH-06); đặt sau values của cell.")
    print("global:\n  doiKhoAnh:")
    for tg in sorted(bang):
        print(f'    "{tg}": "{bang[tg]}"')
    return 0


def main():
    if sys.argv[1] == "--sinh-doi-kho-anh":
        return sinh_doi_kho_anh(sys.argv[2])
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
