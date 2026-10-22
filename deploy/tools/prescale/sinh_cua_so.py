#!/usr/bin/env python3
"""Sinh cửa sổ tăng trước của KEDA từ kỳ đánh giá của một cell (INF-16; TL-04 mục 6.4, OPS-06).

Cửa sổ deadline-default (tuần cuối quý, ngày 1–10 đầu quý, 20/1 đến 1/2) nằm cố định trong values của từng chart
(autoscaling.keda.preScale) và luôn là sàn. Tenant có thể đặt mốc hạn khác (config.period của MOD-08), nên tác vụ
hằng ngày này đọc kỳ qua GET /api/v1/periods (listPeriods) của cell, dựng cửa sổ quanh mốc nộp minh chứng và mốc
hạn nộp theo muc-tang-truoc.yaml, gộp cửa sổ chồng nhau cùng mức, bỏ cửa sổ nằm trọn trong deadline-default cùng
mức, rồi ghi deploy/cells/<cell>/prescale-windows.yaml (khoá global.cuaSoTangTruoc mà vt-service và
workflow-service đổi thành trigger cron 06:00–23:00 giờ Việt Nam). Với --mo-yeu-cau, tác vụ tạo nhánh, commit có
chữ ký và mở merge request cho người trực duyệt; không bao giờ tự gộp.

Biểu thức cron không có năm, nên tệp chỉ giữ cửa sổ chưa kết thúc và phải được sinh lại hằng ngày; cửa sổ đã qua bị
bỏ ở lần sinh kế tiếp, trước khi cùng ngày năm sau tới.

Biến môi trường: VT_API_TOKEN, GITLAB_API, GITLAB_PROJECT, GITLAB_TOKEN (như sinh_lich.py của INF-04).
Dùng: sinh_cua_so.py --cell g4 --api https://<cổng cell>/api/v1 [--tu-tep kỳ.json] [--hom-nay 2027-03-01] [--mo-yeu-cau]
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

import yaml

GOC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
sys.path.insert(0, os.path.join(GOC, "deploy", "argocd", "lich-dong-bang"))
from sinh_lich import TRANG_THAI_BO_QUA, doc_ky  # noqa: E402  (đọc listPeriods theo con trỏ, limit 200)

MUC_MAC_DINH = os.path.join(os.path.dirname(__file__), "muc-tang-truoc.yaml")


def _ngay(gt):
    return dt.date.fromisoformat(gt) if gt else None


def moc(ky: dict, loai: str) -> dt.date | None:
    mc = ky.get("milestones") or {}
    if loai == "hanMinhChung":
        return _ngay(mc.get("evidenceDeadline")) or _ngay(ky.get("endDate"))
    return _ngay(ky.get("submissionDeadline")) or _ngay(mc.get("evidenceDeadline")) or _ngay(ky.get("endDate"))


def mac_dinh(muc: str, loai_ky: str, nam: int) -> list[tuple[dt.date, dt.date]]:
    """Cửa sổ deadline-default cùng mức trong năm `nam` (khớp trigger cron cố định của chart)."""
    if loai_ky == "QUY" and muc == "cuoiKy":
        return [(dt.date(nam, t, 24), dt.date(nam, t, 30 if t in (6, 9) else 31)) for t in (3, 6, 9, 12)]
    if loai_ky == "QUY" and muc == "hanNop":
        return [(dt.date(nam, t, 1), dt.date(nam, t, 10)) for t in (1, 4, 7, 10)]
    return [(dt.date(nam, 1, 20), dt.date(nam, 2, 1))]


def gop(khoang):
    kq = []
    for bd, kt in sorted(khoang):
        if kq and bd <= kq[-1][1] + dt.timedelta(days=1):
            kq[-1][1] = max(kq[-1][1], kt)
        else:
            kq.append([bd, kt])
    return [(a, b) for a, b in kq]


def lich(bd: dt.date, kt: dt.date) -> list[dict]:
    """Tách khoảng ngày thành đoạn trong từng tháng: mỗi đoạn là một trigger cron (ngày a-b, tháng m)."""
    kq, d = [], bd
    while d <= kt:
        cuoi_thang = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1) - dt.timedelta(days=1)
        het = min(kt, cuoi_thang)
        kq.append({"ngay": str(d.day) if d == het else f"{d.day}-{het.day}", "thang": d.month})
        d = het + dt.timedelta(days=1)
    return kq


def sinh(ky_ds: list[dict], muc_cfg: dict, hom_nay: dt.date) -> list[dict]:
    theo_muc: dict[tuple[str, str], list] = {}
    for k in ky_ds:
        loai = k.get("kind")
        if loai not in muc_cfg or k.get("status") in TRANG_THAI_BO_QUA:
            continue
        for ten_muc, m in muc_cfg[loai].items():
            mc = moc(k, m["moc"])
            if mc is None:
                continue
            theo_muc.setdefault((loai, ten_muc), []).append((mc - dt.timedelta(days=int(m["ngayTruoc"])), mc))
    ds = []
    for (loai, ten_muc), khoang in sorted(theo_muc.items()):
        m = muc_cfg[loai][ten_muc]
        for bd, kt in gop(khoang):
            if kt < hom_nay:
                continue
            md = [w for n in range(bd.year, kt.year + 1) for w in mac_dinh(ten_muc, loai, n)]
            if any(a <= bd and kt <= b for a, b in md):
                continue
            ds.append({
                "ten": f"ky-{loai.lower().replace('_', '-')}-{ten_muc.lower()}-{bd:%Y%m%d}",
                "tu": bd.isoformat(), "den": kt.isoformat(),
                "lich": lich(bd, kt), "soPod": dict(m["soPod"]),
            })
    return sorted(ds, key=lambda w: (w["tu"], w["ten"]))


def ghi(ds: list[dict], cell: str, hom_nay: dt.date) -> str:
    dau = [
        "# TỆP SINH TỰ ĐỘNG bởi deploy/tools/prescale/sinh_cua_so.py (INF-16; TL-04 mục 6.4, OPS-06); không sửa tay.",
        f"# Cell {cell}, sinh ngày {hom_nay.isoformat()} từ kỳ đánh giá (GET /api/v1/periods). Cửa sổ deadline-default",
        "# nằm trong values của từng chart và luôn là sàn; tệp này chỉ thêm cửa sổ theo mốc hạn riêng của tenant.",
    ]
    return "\n".join(dau) + "\n" + yaml.safe_dump({"global": {"cuaSoTangTruoc": ds}}, allow_unicode=True,
                                                  sort_keys=False, default_flow_style=None, width=120)


def mo_yeu_cau(tep: str, cell: str, chay=subprocess.run) -> str:
    nhanh = f"tang-truoc/{cell}-{dt.date.today():%Y%m%d}"
    tieu_de = f"OPS-06: cửa sổ tăng trước KEDA của cell {cell} từ kỳ đánh giá"
    chay(["git", "checkout", "-b", nhanh], check=True)
    chay(["git", "add", tep], check=True)
    chay(["git", "commit", "-S", "-m", tieu_de], check=True)
    chay(["git", "push", "-u", "origin", nhanh], check=True)
    api, du_an, token = (os.environ.get(k) for k in ("GITLAB_API", "GITLAB_PROJECT", "GITLAB_TOKEN"))
    if api and du_an and token:
        than = json.dumps({"source_branch": nhanh, "target_branch": "main", "title": tieu_de,
                           "description": "Sinh từ kỳ đánh giá (GET /api/v1/periods). Người trực kiểm tra rồi duyệt; "
                                          "không tự gộp.", "remove_source_branch": True}).encode()
        yc = urllib.request.Request(f"{api.rstrip('/')}/projects/{urllib.parse.quote(du_an, safe='')}/merge_requests",
                                    data=than, method="POST",
                                    headers={"PRIVATE-TOKEN": token, "Content-Type": "application/json"})
        with urllib.request.urlopen(yc, timeout=30) as tl:
            return json.load(tl).get("web_url", nhanh)
    return nhanh


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--cell", required=True, help="mã cell, ví dụ g4 (thư mục deploy/cells/<cell>)")
    ap.add_argument("--api", action="append", default=[], help="gốc API v1 của cell hoặc tenant (lặp lại được)")
    ap.add_argument("--tu-tep", action="append", default=[], help="đọc PeriodPage JSON từ tệp (thử nghiệm)")
    ap.add_argument("--hom-nay", type=dt.date.fromisoformat, default=dt.date.today())
    ap.add_argument("--muc", default=MUC_MAC_DINH, help="tệp mức tăng trước theo loại kỳ")
    ap.add_argument("--ra", help="tệp ra; mặc định deploy/cells/<cell>/prescale-windows.yaml")
    ap.add_argument("--mo-yeu-cau", action="store_true", help="tạo nhánh, commit ký và mở merge request")
    a = ap.parse_args(argv)
    if not a.api and not a.tu_tep:
        ap.error("cần ít nhất một --api hoặc --tu-tep")
    with open(a.muc, encoding="utf-8") as f:
        muc_cfg = yaml.safe_load(f)
    token = os.environ.get("VT_API_TOKEN")
    ky_ds = []
    for u in a.api:
        for nam in (a.hom_nay.year, a.hom_nay.year + 1):
            ky_ds += doc_ky(u, nam, token)
    for t in a.tu_tep:
        with open(t, encoding="utf-8") as f:
            ky_ds += json.load(f)["items"]
    ds = sinh(ky_ds, muc_cfg, a.hom_nay)
    ra = a.ra or os.path.join(GOC, "deploy", "cells", a.cell, "prescale-windows.yaml")
    with open(ra, "w", encoding="utf-8") as f:
        f.write(ghi(ds, a.cell, a.hom_nay))
    print(f"Đã ghi {len(ds)} cửa sổ tăng trước vào {ra}")
    if a.mo_yeu_cau:
        print(f"Đã mở yêu cầu gộp: {mo_yeu_cau(ra, a.cell)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
