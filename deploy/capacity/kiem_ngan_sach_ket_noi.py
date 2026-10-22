#!/usr/bin/env python3
"""Kiểm ngân sách kết nối CSDL của một cell (INF-03, TL-04 mục 4.7, TL-05 "Ngân sách kết nối theo hồ sơ").

Dựng values đã chồng của cell (mặc định chart con → vt-platform → mô hình → hồ sơ → cell → tệp -f), rồi với pg-core
và pg-flowable kiểm:
  (1) phía client:  Σ(maxReplicas × pool) + C_job + C_admin ≤ N_pooler × max_client_conn
  (2) phía máy chủ: N_pooler × Σ default_pool_size + C_direct + R ≤ max_connections
  (3) CPU primary pg-core: maxReplicas mission-core × câu lệnh mỗi pod ≤ vCPU × 70% / 0,6 ms (kiểm ngược TL-04 4.7)
và cảnh báo khi kết nối hoạt động ngoài khoảng 2–4 lần vCPU primary. Hồ sơ compact gộp pg-flowable, pg-platform,
pg-iam vào pg-core (TL-05), nên client và pool của các cụm đó cộng vào pg-core.
In bảng Markdown (đưa vào báo cáo CI bằng --bao-cao) và trả mã 1 khi có bất đẳng thức bị vi phạm.

Dùng: kiem_ngan_sach_ket_noi.py --cell g4 [-f values-thu.yaml] | --tat-ca  [--mo-hinh saas] [--bao-cao tệp.md]
"""
import argparse
import os
import sys

import dinh_co as dc


def bat_dang_thuc(client, c_job, c_admin, n_pooler, max_client_conn, pool_may_chu, truc_tiep_du_tru, max_conn):
    """Hai bất đẳng thức TL-04 mục 4.7; trả (vế trái 1, vế phải 1, đạt 1, vế trái 2, vế phải 2, đạt 2)."""
    t1, p1 = client + c_job + c_admin, n_pooler * max_client_conn
    t2 = n_pooler * pool_may_chu + truc_tiep_du_tru
    return t1, p1, t1 <= p1, t2, max_conn, t2 <= max_conn


def kiem(v, gd_tep):
    """Danh sách dòng kết quả {cum, kiem, trai, phai, dat, ghi_chu} cho values đã chồng của một cell."""
    kn = gd_tep["ketNoi"]
    ho_so = dc.lay(v, "global.sizing")
    pg = v["data-pg"]
    hs = pg["hoSo"][ho_so]
    gop = pg.get("consolidated") in (True, "true") or (pg.get("consolidated") in ("", None) and ho_so == "compact")
    n_pooler = hs["pooler"]
    max_client = pg["pgbouncer"]["maxClientConn"]
    du_tru = kn["trucTiepVaDuTru"][ho_so]
    rep = dc.so_replica(v)
    pool_mc = sum(m["pool"] for m in v["mission-core"]["datasources"]["modules"].values())
    pool_wf = kn["poolMoiPod"]["workflow-service"]
    wf_max = rep["workflow-api"][1] + rep["workflow-executor"][1]
    pool_core = sum(pg["pgbouncer"]["poolCore"][ho_so].values())
    pool_khac = pg["pgbouncer"]["poolKhac"][ho_so]

    cum = {"pg-core": {"client": rep["mission-core"][1] * pool_mc, "pool": pool_core,
                       "mo_ta": f"mission-core {rep['mission-core'][1]} × {pool_mc}"}}
    flowable = {"client": wf_max * pool_wf, "pool": pool_khac["pg-flowable"],
                "mo_ta": f"workflow API + executor {wf_max} × {pool_wf}"}
    if gop:
        # pg-core gộp nhận client của Flowable và pool máy chủ của bốn cụm ứng dụng (TL-05 "PostgreSQL").
        c = cum["pg-core"]
        c["client"] += flowable["client"]
        c["pool"] += sum(pool_khac[k] for k in ("pg-flowable", "pg-platform", "pg-iam"))
        c["mo_ta"] += f" + {flowable['mo_ta']} (gộp)"
    else:
        cum["pg-flowable"] = flowable

    kq = []
    for ten, c in cum.items():
        max_conn = hs[ten]["maxConn"]
        t1, p1, d1, t2, p2, d2 = bat_dang_thuc(c["client"], kn["cJob"], kn["cAdmin"], n_pooler, max_client,
                                                c["pool"], du_tru, max_conn)
        kq.append(dict(cum=ten, kiem="(1) client → PgBouncer", trai=t1, phai=p1, dat=d1,
                       ghi_chu=f"{c['mo_ta']} + C_job {kn['cJob']} + C_admin {kn['cAdmin']} ≤ {n_pooler} × {max_client}"))
        kq.append(dict(cum=ten, kiem="(2) PgBouncer → PostgreSQL", trai=t2, phai=p2, dat=d2,
                       ghi_chu=f"{n_pooler} × {c['pool']} + trực tiếp và dự trữ {du_tru} ≤ max_connections"))
        cpu = hs[ten]["cpu"]
        hoat_dong = n_pooler * c["pool"]
        r = hoat_dong / cpu
        if not kn["ketNoiTrenVcpu"]["min"] <= r <= kn["ketNoiTrenVcpu"]["max"]:
            kq.append(dict(cum=ten, kiem="cảnh báo: kết nối hoạt động / vCPU", trai=round(r, 1),
                           phai=f"{kn['ketNoiTrenVcpu']['min']}–{kn['ketNoiTrenVcpu']['max']}", dat=None,
                           ghi_chu=f"{hoat_dong} / {cpu} vCPU (khuyến nghị TL-04 mục 4.7, không chặn)"))

    cpu = hs["pg-core"]["cpu"]
    nang_luc = cpu * kn["tyLeCpuPrimaryToiDa"] / (kn["msCpuMoiCauLenh"] / 1000)
    tai = rep["mission-core"][1] * kn["cauLenhMoiPodTrenPrimary"]
    kq.append(dict(cum="pg-core", kiem="(3) CPU primary", trai=tai, phai=round(nang_luc), dat=tai <= nang_luc,
                   ghi_chu=f"{rep['mission-core'][1]} pod × {kn['cauLenhMoiPodTrenPrimary']} câu lệnh/giây ≤ "
                           f"{cpu} vCPU × {kn['tyLeCpuPrimaryToiDa']:.0%} / {kn['msCpuMoiCauLenh']} ms"))
    return kq


def bang(ten, ho_so, kq):
    ra = [f"### Cell {ten} (hồ sơ {ho_so})", "", "| Cụm | Kiểm | Vế trái | Vế phải | Kết quả | Chi tiết |",
          "|---|---|---|---|---|---|"]
    for r in kq:
        kqua = "cảnh báo" if r["dat"] is None else ("đạt" if r["dat"] else "VI PHẠM")
        ra.append(f"| {r['cum']} | {r['kiem']} | {r['trai']} | {r['phai']} | {kqua} | {r['ghi_chu']} |")
    return "\n".join(ra) + "\n"


def main():
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--cell")
    g.add_argument("--tat-ca", action="store_true")
    ap.add_argument("-f", dest="them", action="append", default=[])
    ap.add_argument("--mo-hinh", default="saas")
    ap.add_argument("--gia-dinh")
    ap.add_argument("--bao-cao")
    a = ap.parse_args()
    for t in a.them:
        if not os.path.exists(t):
            ap.error(f"không có tệp {t}")
    gd_tep = dc.gia_dinh(a.gia_dinh)
    ra, vi_pham = ["## Ngân sách kết nối CSDL (TL-04 mục 4.7, TL-05)", ""], 0
    for ten in (dc.cac_cell() if a.tat_ca else [a.cell]):
        c, v = dc.values_cell(ten, a.mo_hinh, a.them)
        kq = kiem(v, gd_tep)
        vi_pham += sum(1 for r in kq if r["dat"] is False)
        ra.append(bang(ten, c["sizing"], kq))
    ra.append(f"Bất đẳng thức bị vi phạm: {vi_pham}")
    txt = "\n".join(ra) + "\n"
    print(txt)
    if a.bao_cao:
        with open(a.bao_cao, "w", encoding="utf-8") as f:
            f.write(txt)
    return 1 if vi_pham else 0


if __name__ == "__main__":
    sys.exit(main())
