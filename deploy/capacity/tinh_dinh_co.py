#!/usr/bin/env python3
"""Tính lại bảng định cỡ TL-05 từ capacity/assumptions.yaml và so với values của bốn hồ sơ (INF-03, vòng hiệu chuẩn
TL-05). In bảng tải đầu vào (chương 2), số pod cần ở đỉnh và maxReplicas tối thiểu của các service đồng bộ, số pod
của tác vụ lô theo NFR-06, số node pool app-general và app-worker (chương 4), rồi đánh dấu chênh lệch quá 20%.

Dùng: tinh_dinh_co.py [--ho-so M ...] [--gia-dinh tệp] [--bao-cao tệp.md] [--nghiem]
--nghiem trả mã 1 khi values thấp hơn mức tính lại quá ngưỡng (chiều "thiếu" cần mở yêu cầu thay đổi values).
"""
import argparse
import math
import sys

import dinh_co as dc

NGUONG = 0.2


def tai(gd, a):
    """Bảng "Tải đầu vào theo hồ sơ cell" cho A tài khoản."""
    ccu = gd["GD-02"]["giaTri"] * a
    rps_thuong = ccu / gd["GD-03"]["giaTri"]
    rps_dinh = gd["GD-04"]["giaTri"] * rps_thuong
    ghi = gd["GD-05"]["giaTri"]["ghi"] * rps_dinh
    ut = gd["GD-10"]["giaTri"]
    return {
        "Tài khoản thiết kế": a,
        "CCU": ccu,
        "RPS thường": rps_thuong,
        "RPS đỉnh": rps_dinh,
        "Request ghi đỉnh/giây": ghi,
        "Sự kiện nghiệp vụ đỉnh/giây": gd["GD-06"]["giaTri"] * ghi,
        "Bản ghi CDC đỉnh/giây": gd["GD-14"]["giaTri"] * ghi,
        "User task hoàn thành đỉnh/giây": ut["lanMoiGiay"] * a / ut["taiKhoan"],
        "Đăng nhập đỉnh/giây": a / gd["GD-11"]["giaTri"],
        "Phiếu sinh và ký mỗi đợt": a / gd["GD-23"]["giaTri"],
    }


def pod_can(gd, ct, a, rps_dinh):
    """{workload: (số pod cần ở đỉnh, maxReplicas tối thiểu)} theo công thức TL-05."""
    tl, tp = gd["GD-07"]["giaTri"], gd["GD-08"]["giaTri"]
    kq = {}
    for ten, _, _, _, _, khoa in dc.WORKLOAD:
        if not khoa:
            continue
        p = 1.0 if khoa == "api-gateway" else tl[khoa]
        n = math.ceil(round(p * rps_dinh / tp[khoa], 6))
        kq[ten] = (n, math.ceil(ct["heSoDuPhongVung"] * n))
    s = ct["scoring"]
    n = s["heSoMucTieu1Gio"] * math.ceil(a / (s["gioTinhLai"] * 3600 * s["hoSoMoiGiayMoiPod"]))
    kq["scoring-service"] = (n, n)
    g = ct["gotenberg"]
    n = math.ceil((a / gd["GD-23"]["giaTri"]) * g["giayCpuMoiPhieu"] / (g["gioSinhPhieu"] * 3600 * g["hieuSuat"] * g["vcpuMoiPod"]))
    kq["gotenberg"] = (n, n)
    return kq


def node(gd, rep):
    """{pool: (CPU ở min, CPU ở max, node đỉnh)} với 5% OPA (GD-13) và hệ số xếp lịch GD-12."""
    x = gd["GD-12"]["giaTri"]
    he_so = {"app-general": x["appGeneral"], "app-worker": x["appWorker"]}
    opa = 1 + gd["GD-13"]["giaTri"]
    kq = {}
    for pool, u in he_so.items():
        cmin = sum((rep[t][0] or 0) * c for t, p, c, *_ in dc.WORKLOAD if p == pool) * opa
        cmax = sum((rep[t][1] or 0) * c for t, p, c, *_ in dc.WORKLOAD if p == pool) * opa
        kq[pool] = (cmin, cmax, math.ceil(round(cmax / (x["vcpuKhaDung"] * u), 6)))
    return kq


def chenh(dang_co, can):
    if not can:
        return ""
    r = (dang_co - can) / can
    if r < -NGUONG:
        return f"THIẾU {r:+.0%}"
    if r > NGUONG:
        return f"thừa {r:+.0%}"
    return ""


def so(x):
    return f"{x:,.0f}".replace(",", ".") if x >= 10 else f"{x:.1f}".replace(".", ",")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ho-so", nargs="*", default=dc.HO_SO)
    ap.add_argument("--gia-dinh")
    ap.add_argument("--bao-cao")
    ap.add_argument("--nghiem", action="store_true")
    a = ap.parse_args()
    gd_tep = dc.gia_dinh(a.gia_dinh)
    gd, ct = gd_tep["gd"], gd_tep["congThuc"]
    tk = gd["GD-01"]["giaTri"]
    ra, thieu = [], 0

    ra.append("## Tải đầu vào theo hồ sơ (TL-05 chương 2)\n")
    bang = {h: tai(gd, tk[h]) for h in a.ho_so}
    ra.append("| Đại lượng | " + " | ".join(a.ho_so) + " |")
    ra.append("|---" * (len(a.ho_so) + 1) + "|")
    for k in next(iter(bang.values())):
        ra.append(f"| {k} | " + " | ".join(so(bang[h][k]) for h in a.ho_so) + " |")

    for h in a.ho_so:
        v = dc.values(h)
        rep = dc.so_replica(v)
        can = pod_can(gd, ct, tk[h], bang[h]["RPS đỉnh"])
        ra.append(f"\n## Hồ sơ {h}: pod ở đỉnh và maxReplicas (TL-05 chương 4)\n")
        ra.append("| Workload | Pod cần ở đỉnh | maxReplicas tối thiểu | Values (min / max) | Chênh > 20% |")
        ra.append("|---|---|---|---|---|")
        for ten, (n, toi_thieu) in can.items():
            mn, mx = rep[ten]
            c = chenh(mx, toi_thieu)
            thieu += c.startswith("THIẾU")
            ra.append(f"| {ten} | {n} | {toi_thieu} | {mn} / {mx} | {c} |")
        np_ = v.get("nodePools", {})
        ra.append("\n| Pool | CPU yêu cầu ở min | CPU yêu cầu ở max | Node đỉnh tính lại | nodePools.max | Chênh > 20% |")
        ra.append("|---|---|---|---|---|---|")
        for pool, (cmin, cmax, n) in node(gd, rep).items():
            mx = (np_.get(pool) or {}).get("max")
            c = chenh(mx, n) if mx else "pool hội tụ (compact)"
            thieu += c.startswith("THIẾU")
            ra.append(f"| {pool} | {cmin:.0f} | {cmax:.0f} | {n} | {mx if mx else '–'} | {c} |")

    txt = "\n".join(ra) + "\n"
    print(txt)
    if a.bao_cao:
        with open(a.bao_cao, "w", encoding="utf-8") as f:
            f.write(txt)
    print(f"Chênh lệch theo chiều thiếu: {thieu}")
    return 1 if a.nghiem and thieu else 0


if __name__ == "__main__":
    sys.exit(main())
