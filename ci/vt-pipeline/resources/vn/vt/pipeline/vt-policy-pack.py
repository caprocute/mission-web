#!/usr/bin/env python3
"""Tiện ích pipeline Policy Pack (INF-06, OPS-10). Cần PyYAML.

    vt-policy-pack.py ds <thư mục gói> [gói...]          # in "id<TAB>version<TAB>đường dẫn" của từng gói
    vt-policy-pack.py kiem-ky <thư mục gói> [gói...]     # bộ ca vàng phải do nghiệp vụ ký (J6) trước khi phát hành
    vt-policy-pack.py dong-goi <thư mục gói> <ra/> [gói...]  # tar.gz tất định + manifest sha256 từng tệp

Gói là thư mục có policy.yaml (metadata.id, metadata.version). Bộ ca vàng nằm ở tests/*.yaml của gói.
"""
import hashlib, io, json, os, sys, tarfile, yaml


def cac_goi(goc, chon):
    for d, _, tep in sorted(os.walk(goc)):
        if "policy.yaml" in tep:
            m = yaml.safe_load(open(os.path.join(d, "policy.yaml"), encoding="utf-8"))["metadata"]
            if not chon or m["id"] in chon:
                yield m["id"], str(m["version"]), d


def ds(goc, chon):
    for i, v, d in cac_goi(goc, chon):
        print(f"{i}\t{v}\t{d}")


def kiem_ky(goc, chon):
    loi = 0
    for i, v, d in cac_goi(goc, chon):
        bo = [os.path.join(d, "tests", t) for t in sorted(os.listdir(os.path.join(d, "tests"))) if t.endswith((".yaml", ".yml"))] if os.path.isdir(os.path.join(d, "tests")) else []
        if not bo:
            print(f"HỎNG {i}@{v}: không có bộ ca vàng"); loi += 1; continue
        for t in bo:
            n = yaml.safe_load(open(t, encoding="utf-8")) or {}
            if n.get("gia_lap") or n.get("trang_thai_ky") != "DA_KY" or not n.get("nguoi_ky") or n.get("yeu_cau_dat") != 1.0:
                print(f"HỎNG {i}@{v}: {os.path.relpath(t, goc)} chưa được nghiệp vụ ký (trang_thai_ky={n.get('trang_thai_ky')}, "
                      f"gia_lap={n.get('gia_lap')}, yeu_cau_dat={n.get('yeu_cau_dat')})"); loi += 1
            else:
                print(f"ĐẠT {i}@{v}: {os.path.relpath(t, goc)} do {n['nguoi_ky']} ký")
    sys.exit(1 if loi else 0)


def dong_goi(goc, ra, chon):
    os.makedirs(ra, exist_ok=True)
    for i, v, d in cac_goi(goc, chon):
        ten = f"{i.replace('/', '__')}-{v}"
        manifest = {"id": i, "version": v, "files": {}}
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w", format=tarfile.PAX_FORMAT) as tar:
            for duong, _, tep in sorted(os.walk(d)):
                for t in sorted(tep):
                    p = os.path.join(duong, t); rel = os.path.relpath(p, d)
                    b = open(p, "rb").read()
                    manifest["files"][rel] = hashlib.sha256(b).hexdigest()
                    ti = tarfile.TarInfo(rel); ti.size = len(b); ti.mtime = 0; ti.mode = 0o644; ti.uname = ti.gname = ""
                    tar.addfile(ti, io.BytesIO(b))
        import gzip
        open(os.path.join(ra, ten + ".tar.gz"), "wb").write(gzip.compress(buf.getvalue(), 9, mtime=0))
        json.dump(manifest, open(os.path.join(ra, ten + ".manifest.json"), "w"), ensure_ascii=False, indent=2, sort_keys=True)
        print(os.path.join(ra, ten + ".tar.gz"))


lenh, goc, *con = sys.argv[1:]
if lenh == "ds":
    ds(goc, con)
elif lenh == "kiem-ky":
    kiem_ky(goc, con)
elif lenh == "dong-goi":
    dong_goi(goc, con[0], con[1:])
else:
    sys.exit(__doc__)
