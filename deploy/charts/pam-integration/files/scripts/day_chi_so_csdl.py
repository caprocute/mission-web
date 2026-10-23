#!/usr/bin/env python3
"""Bước báo cáo của CronJob quét vai trò CSDL (INF-27; SEC-58, SEC-42; TL-04 mục 4 ngân sách C_admin).

Đọc kết quả của quet-csdl.sh ("csdl|loai|vai_tro|chi_tiet" và "tong|dba_ops|pha_kinh"), bỏ ngoại lệ có ghi danh
còn hạn, ghi mỗi phát hiện một dòng JSON dạng ECS (event.dataset security → sys.audit.v1 → otel-siem) và đẩy chỉ số
lên Pushgateway. Chỉ dùng thư viện chuẩn. Biến: CUM, CELL, KET_QUA, NGOAI_LE (JSON), NGAN_SACH, PUSHGATEWAY.
"""
import datetime as dt
import json
import os
import sys
import urllib.request

THONG_TIN = {"pha-kinh-dang-mo"}


def doc_ket_qua(dong):
    """Gộp phát hiện theo (loai, vai_tro) trên mọi CSDL của cụm."""
    gop = {}
    for d in dong:
        d = d.strip()
        if not d:
            continue
        phan = d.split("|", 3)
        if len(phan) < 4:
            continue
        db, loai, vai_tro, chi_tiet = phan
        muc = gop.setdefault((loai, vai_tro), {"loai": loai, "vai_tro": vai_tro, "csdl": [], "chi_tiet": set()})
        muc["csdl"].append(db)
        muc["chi_tiet"].update(x for x in chi_tiet.split(",") if x)
    return [dict(m, chi_tiet=sorted(m["chi_tiet"]), csdl=sorted(set(m["csdl"]))) for m in gop.values()]


def loc_ngoai_le(phat_hien, ngoai_le, hom_nay):
    """Ngoại lệ còn hạn (denNgay ≥ hôm nay) bỏ khỏi phát hiện; ngoại lệ quá hạn không còn tác dụng."""
    con_han = {n["vaiTro"] for n in ngoai_le if str(n.get("denNgay", "")) >= hom_nay.isoformat()}
    giu, bo = [], []
    for p in phat_hien:
        (bo if p["vai_tro"] in con_han and p["loai"] not in THONG_TIN else giu).append(p)
    return giu, bo


def nhan(**kv):
    return ",".join(f'{k}="{str(v).replace(chr(92), chr(92) * 2).replace(chr(34), chr(92) + chr(34))}"' for k, v in kv.items())


def chi_so(cum, phat_hien, bo, ket_noi, so_csdl, now):
    d = ["# HELP vt_pam_cua_sau_csdl 1 với mỗi vai trò đọc được bảng nghiệp vụ ngoài vai trò dịch vụ và phá kính.",
         "# TYPE vt_pam_cua_sau_csdl gauge"]
    for p in phat_hien:
        if p["loai"] not in THONG_TIN:
            d.append(f'vt_pam_cua_sau_csdl{{{nhan(cluster=cum, loai=p["loai"], vai_tro=p["vai_tro"])}}} 1')
    d += ["# HELP vt_pam_ngoai_le_csdl 1 với mỗi vai trò đang được miễn theo ngoại lệ có ghi danh.",
          "# TYPE vt_pam_ngoai_le_csdl gauge"]
    for v in sorted({p["vai_tro"] for p in bo}):
        d.append(f'vt_pam_ngoai_le_csdl{{{nhan(cluster=cum, vai_tro=v)}}} 1')
    pk = sum(1 for p in phat_hien if p["loai"] == "pha-kinh-dang-mo")
    tong, dba, pkn = ket_noi
    d += ["# HELP vt_pam_pha_kinh_dang_mo Số người dùng phá kính đang hiệu lực trên cụm.",
          "# TYPE vt_pam_pha_kinh_dang_mo gauge", f'vt_pam_pha_kinh_dang_mo{{{nhan(cluster=cum)}}} {pk}',
          "# HELP vt_pam_ket_noi_quan_tri Số kết nối đang mở của thành viên dba_ops và phá kính.",
          "# TYPE vt_pam_ket_noi_quan_tri gauge",
          f'vt_pam_ket_noi_quan_tri{{{nhan(cluster=cum, vai_tro="tong")}}} {tong}',
          f'vt_pam_ket_noi_quan_tri{{{nhan(cluster=cum, vai_tro="dba_ops")}}} {dba}',
          f'vt_pam_ket_noi_quan_tri{{{nhan(cluster=cum, vai_tro="pha_kinh")}}} {pkn}',
          "# HELP vt_pam_quet_csdl_so_csdl Số CSDL đã quét ở lần chạy.", "# TYPE vt_pam_quet_csdl_so_csdl gauge",
          f'vt_pam_quet_csdl_so_csdl{{{nhan(cluster=cum)}}} {so_csdl}',
          "# HELP vt_pam_quet_last_run_timestamp_seconds Thời điểm lần quét chạy xong.",
          "# TYPE vt_pam_quet_last_run_timestamp_seconds gauge",
          f"vt_pam_quet_last_run_timestamp_seconds {int(now.timestamp())}"]
    return "\n".join(d) + "\n"


def ecs(hanh_dong, ket_qua, thong_diep, now, **them):
    return json.dumps({"@timestamp": now.strftime("%Y-%m-%dT%H:%M:%S.%fZ")[:-4] + "Z",
                       "event": {"dataset": "security", "module": "pam", "kind": "alert" if ket_qua == "failure" else "event",
                                 "category": ["database", "iam"], "action": hanh_dong, "outcome": ket_qua},
                       "service": {"name": "pam-quet-csdl"}, "message": thong_diep, "pam": them}, ensure_ascii=False)


def doc_ket_noi(s):
    phan = (s.strip() or "0|0|0").split("|")
    return tuple(int(x or 0) for x in (phan + ["0", "0", "0"])[:3])


def main():
    kq = os.environ.get("KET_QUA", "/ket-qua")
    cum = os.environ.get("CUM", "khong-ro")
    now = dt.datetime.now(dt.timezone.utc)
    phat_hien = doc_ket_qua(open(f"{kq}/phat-hien.txt").read().splitlines())
    giu, bo = loc_ngoai_le(phat_hien, json.loads(os.environ.get("NGOAI_LE", "[]")), now.date())
    ket_noi = doc_ket_noi(open(f"{kq}/ket-noi.txt").read())
    so_csdl = int(open(f"{kq}/so-csdl.txt").read().strip() or 0)
    ngan_sach = int(os.environ.get("NGAN_SACH", "10"))
    for p in giu:
        tt = p["loai"] in THONG_TIN
        print(ecs(f'pam.{p["loai"]}-csdl', "success" if tt else "failure",
                  f'{cum}: {p["loai"]} {p["vai_tro"]} ({", ".join(p["chi_tiet"])}) tại {", ".join(p["csdl"])}', now,
                  cluster=cum, **p))
    for p in bo:
        print(ecs("pam.ngoai-le-csdl", "success", f'{cum}: {p["vai_tro"]} được miễn theo ngoại lệ ({p["loai"]})', now,
                  cluster=cum, **p))
    if ket_noi[0] > ngan_sach:
        print(ecs("pam.vuot-ngan-sach-ket-noi", "failure", f"{cum}: {ket_noi[0]} kết nối quản trị, ngân sách {ngan_sach}", now,
                  cluster=cum, ket_noi=ket_noi[0]))
    noi_dung = chi_so(cum, giu, bo, ket_noi, so_csdl, now)
    if os.environ.get("PUSHGATEWAY"):
        req = urllib.request.Request(f'{os.environ["PUSHGATEWAY"]}/metrics/job/pam-quet-csdl/cell/{os.environ.get("CELL", "khong-ro")}/cluster/{cum}',
                                     data=noi_dung.encode(), method="PUT", headers={"Content-Type": "text/plain; version=0.0.4"})
        urllib.request.urlopen(req, timeout=15).close()
    else:
        sys.stdout.write(noi_dung)


if __name__ == "__main__":
    main()
