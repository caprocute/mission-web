#!/usr/bin/env python3
"""Đổi chính sách Kyverno sang khoá công khai phát hành cho bản cách ly (INF-23; TL-04 mục 9.9).

Ở SaaS, vt-require-signed-images kiểm chữ ký bằng khoá trong OpenBao Transit (kms: hashivault://cosign-release). Trong
mạng khách hàng không có OpenBao của Viettel, nên mọi khối keys có kms được thay bằng publicKeys là khoá công khai
phát hành; điều kiện chứng thực SLSA (repo, builder của Viettel) giữ nguyên. Tiền tố registry phát hành trong
imageReferences và mẫu ảnh của vt-pod-hardening đổi sang registry nội bộ của khách hàng. Ghi ra --ra, không sửa tệp gốc.
Cách gọi: doi-khoa-kyverno.py --khoa vt-release.pub --registry-goc harbor.vt.local --kho harbor.qp.local --ra <thư mục> <tệp>...
Mô hình đám mây riêng (VH-06) vẫn có OpenBao trong cell nên giữ khoá kms, chỉ đổi registry cho khớp global.doiKhoAnh:
          doi-khoa-kyverno.py --chi-registry --registry-goc harbor.vt.local --kho harbor.kh01.local --ra <thư mục> <tệp>...
"""
import argparse
import os
import sys

import yaml


def doi(nut, khoa, goc, moi, dem):
    if isinstance(nut, dict):
        for k, v in list(nut.items()):
            if khoa and k == "keys" and isinstance(v, dict) and "kms" in v:
                del v["kms"]
                v["publicKeys"] = khoa
                dem["khoa"] += 1
            if isinstance(v, str) and goc in v:
                nut[k] = v.replace(goc, moi)
                dem["registry"] += 1
            else:
                doi(v, khoa, goc, moi, dem)
    elif isinstance(nut, list):
        for i, x in enumerate(nut):
            if isinstance(x, str) and goc in x:
                nut[i] = x.replace(goc, moi)
                dem["registry"] += 1
            else:
                doi(x, khoa, goc, moi, dem)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--khoa")
    ap.add_argument("--chi-registry", action="store_true", help="giữ khoá kms, chỉ đổi tiền tố registry")
    ap.add_argument("--registry-goc", required=True)
    ap.add_argument("--kho", required=True)
    ap.add_argument("--ra", required=True)
    ap.add_argument("tep", nargs="+")
    a = ap.parse_args()
    if bool(a.khoa) == a.chi_registry:
        sys.exit("doi-khoa-kyverno: cần đúng một trong --khoa hoặc --chi-registry")
    khoa = None
    if a.khoa:
        khoa = open(a.khoa, encoding="utf-8").read().strip() + "\n"
        if not khoa.startswith("-----BEGIN PUBLIC KEY-----"):
            sys.exit("doi-khoa-kyverno: --khoa phải là khoá công khai PEM")
    tong = {"khoa": 0, "registry": 0}
    for t in a.tep:
        docs = [d for d in yaml.safe_load_all(open(t, encoding="utf-8")) if d]
        for d in docs:
            doi(d, khoa, a.registry_goc, a.kho, tong)
        with open(os.path.join(a.ra, os.path.basename(t)), "w", encoding="utf-8") as f:
            yaml.safe_dump_all(docs, f, allow_unicode=True, sort_keys=False)
    if khoa and tong["khoa"] == 0:
        sys.exit("doi-khoa-kyverno: không tìm thấy khối kms nào để đổi; chính sách đã khác mẫu?")
    if tong["registry"] == 0:
        sys.exit("doi-khoa-kyverno: không tìm thấy tham chiếu registry nào để đổi; --registry-goc sai?")
    print(f"doi-khoa-kyverno: đổi {tong['khoa']} khoá kiểm, {tong['registry']} tham chiếu registry → {a.ra}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
