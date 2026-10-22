#!/usr/bin/env python3
"""Sinh cửa sổ đóng băng bổ sung từ kỳ đánh giá (INF-04; OPS-06, TL-04 "Đóng băng thay đổi").

Hai cửa sổ cố định của TL-04 (24 tháng cuối quý tới hết ngày 10 tháng sau, 20/1 tới hết 04/2) đã nằm trong
deploy/argocd/values.yaml. Mỗi tenant có thể đặt mốc hạn khác (config.period của MOD-08), nên tác vụ này đọc kỳ
đánh giá qua GET /api/v1/periods (operationId listPeriods) của từng nguồn, dựng khoảng đóng băng quanh mốc hạn nộp
và mốc khoá kỳ, gộp các khoảng chồng nhau, bỏ khoảng đã nằm trọn trong cửa sổ cố định, rồi ghi
deploy/argocd/values-dong-bang.yaml (khoá syncWindowsBoSung). Với --mo-yeu-cau, tác vụ tạo nhánh, commit có chữ ký
và mở merge request cho người trực duyệt; tác vụ không bao giờ tự gộp.

Biến môi trường: VT_API_TOKEN (token dịch vụ đọc kỳ, không ghi vào tệp), GITLAB_API, GITLAB_PROJECT, GITLAB_TOKEN
(chỉ khi mở merge request qua API GitLab; thiếu thì chỉ đẩy nhánh).

Dùng: sinh_lich.py --nam 2027 --api https://<cổng cell>/api/v1 [--api ...] [--ra tệp] [--mo-yeu-cau]
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

# Loại kỳ có mốc hạn gây tải đỉnh (TL-04: tải tập trung cuối kỳ đánh giá). Kỳ tháng không đóng băng mặc định.
LOAI_MAC_DINH = ("QUY", "NAM", "NAM_HOC")
# Kỳ đã khoá xong (PeriodStatus của common.yaml) không còn mốc hạn phía trước nên không cần đóng băng.
TRANG_THAI_BO_QUA = ("DA_KHOA",)
GIOI_HAN_TRANG = 200  # limit ≤ 200 (quy ước API mục 13 baseline)


def doc_ky(api: str, nam: int, token: str | None, mo=urllib.request.urlopen) -> list[dict]:
    """Đọc mọi trang listPeriods của một nguồn theo con trỏ nextCursor."""
    ky, con_tro = [], None
    while True:
        tham_so = {"year": nam, "limit": GIOI_HAN_TRANG}
        if con_tro:
            tham_so["cursor"] = con_tro
        yc = urllib.request.Request(f"{api.rstrip('/')}/periods?{urllib.parse.urlencode(tham_so)}")
        yc.add_header("Accept", "application/json")
        if token:
            yc.add_header("Authorization", f"Bearer {token}")
        with mo(yc, timeout=30) as tl:
            trang = json.load(tl)
        ky.extend(trang["items"])
        con_tro = trang.get("nextCursor")
        if not con_tro:
            return ky


def _ngay(gt: str | None) -> dt.date | None:
    return dt.date.fromisoformat(gt) if gt else None


def khoang_cua_ky(ky: dict, truoc: int, sau: int) -> tuple[dt.date, dt.date] | None:
    """Khoảng đóng băng [bắt đầu, kết thúc] (tính cả hai đầu) quanh mốc hạn của một kỳ.

    Bắt đầu: trước mốc hạn nộp `truoc` ngày (mốc hạn nộp là submissionDeadline, thiếu thì evidenceDeadline,
    thiếu nữa thì endDate). Kết thúc: sau mốc khoá kỳ dự kiến (milestones.plannedLockDate, thiếu thì mốc hạn nộp)
    `sau` ngày, để đợt tính điểm hàng loạt sau khoá kỳ cũng nằm trong cửa sổ.
    """
    mc = ky.get("milestones") or {}
    han_nop = _ngay(ky.get("submissionDeadline")) or _ngay(mc.get("evidenceDeadline")) or _ngay(ky.get("endDate"))
    if han_nop is None:
        return None
    khoa = _ngay(mc.get("plannedLockDate")) or han_nop
    return han_nop - dt.timedelta(days=truoc), max(khoa, han_nop) + dt.timedelta(days=sau)


def gop(khoang: list[tuple[dt.date, dt.date]]) -> list[tuple[dt.date, dt.date]]:
    """Gộp khoảng chồng nhau hoặc nối liền (cách nhau dưới một ngày)."""
    kq: list[list[dt.date]] = []
    for bd, kt in sorted(khoang):
        if kq and bd <= kq[-1][1] + dt.timedelta(days=1):
            kq[-1][1] = max(kq[-1][1], kt)
        else:
            kq.append([bd, kt])
    return [(a, b) for a, b in kq]


def cua_so_co_dinh(nam: int) -> list[tuple[dt.date, dt.date]]:
    """Hai cửa sổ cố định của TL-04 trong năm `nam` (khớp syncWindows của values.yaml)."""
    kq = [(dt.date(nam, 1, 20), dt.date(nam, 2, 4))]
    for thang in (3, 6, 9, 12):
        bd = dt.date(nam, thang, 24)
        kt = dt.date(nam + 1, 1, 10) if thang == 12 else dt.date(nam, thang + 1, 10)
        kq.append((bd, kt))
    # Cửa sổ quý IV năm trước kéo sang đầu năm nay.
    kq.append((dt.date(nam - 1, 12, 24), dt.date(nam, 1, 10)))
    return kq


def bo_khoang_da_phu(khoang, co_dinh):
    return [(bd, kt) for bd, kt in khoang if not any(a <= bd and kt <= b for a, b in co_dinh)]


def cua_so(khoang: list[tuple[dt.date, dt.date]]) -> list[dict]:
    """Đổi khoảng ngày thành syncWindow của Argo CD: lịch cron theo ngày bắt đầu, thời lượng tính bằng giờ."""
    return [{
        "kind": "deny",
        "schedule": f"0 0 {bd.day} {bd.month} *",
        "duration": f"{((kt - bd).days + 1) * 24}h",
        "applications": ["*"],
        "manualSync": True,
        "timeZone": "Asia/Ho_Chi_Minh",
        "_tu": bd.isoformat(),
        "_den": kt.isoformat(),
    } for bd, kt in khoang]


def sinh(ky_theo_nguon: dict[str, list[dict]], nam: int, truoc: int = 7, sau: int = 3,
         loai=LOAI_MAC_DINH) -> list[dict]:
    khoang = []
    for ds in ky_theo_nguon.values():
        for k in ds:
            if k.get("kind") not in loai or k.get("status") in TRANG_THAI_BO_QUA:
                continue
            kh = khoang_cua_ky(k, truoc, sau)
            if kh and kh[1].year >= nam and kh[0].year <= nam:
                khoang.append(kh)
    return cua_so(bo_khoang_da_phu(gop(khoang), cua_so_co_dinh(nam)))


def ghi_values(ds: list[dict], nam: int, nguon: list[str]) -> str:
    dong = [
        "# TỆP SINH TỰ ĐỘNG bởi deploy/argocd/lich-dong-bang/sinh_lich.py (INF-04, OPS-06); không sửa tay.",
        f"# Năm {nam}, từ kỳ đánh giá của {len(nguon)} nguồn. Lịch cron lặp hằng năm nên tệp được sinh lại mỗi năm",
        "# (và khi tenant đổi mốc hạn); thay đổi đi qua merge request do người trực duyệt.",
        "syncWindowsBoSung:" + (" []" if not ds else ""),
    ]
    for w in ds:
        dong += [
            f"  # {w['_tu']} đến hết {w['_den']}",
            "  - kind: deny",
            f"    schedule: \"{w['schedule']}\"",
            f"    duration: {w['duration']}",
            "    applications: [\"*\"]",
            "    manualSync: true",
            f"    timeZone: {w['timeZone']}",
        ]
    return "\n".join(dong) + "\n"


def mo_yeu_cau(tep: str, nam: int, chay=subprocess.run) -> str:
    """Tạo nhánh, commit có chữ ký GPG (Argo CD chỉ nhận commit ký) và đẩy; mở merge request nếu có API GitLab."""
    nhanh = f"dong-bang/{nam}-{dt.date.today():%Y%m%d}"
    chay(["git", "checkout", "-b", nhanh], check=True)
    chay(["git", "add", tep], check=True)
    chay(["git", "commit", "-S", "-m", f"OPS-06: cửa sổ đóng băng bổ sung năm {nam} từ kỳ đánh giá"], check=True)
    chay(["git", "push", "-u", "origin", nhanh], check=True)
    api, du_an, token = (os.environ.get(k) for k in ("GITLAB_API", "GITLAB_PROJECT", "GITLAB_TOKEN"))
    if api and du_an and token:
        than = json.dumps({
            "source_branch": nhanh, "target_branch": "main",
            "title": f"OPS-06: cửa sổ đóng băng bổ sung năm {nam}",
            "description": "Sinh từ kỳ đánh giá (GET /api/v1/periods). Người trực kiểm tra rồi duyệt; không tự gộp.",
            "remove_source_branch": True,
        }).encode()
        yc = urllib.request.Request(f"{api.rstrip('/')}/projects/{urllib.parse.quote(du_an, safe='')}/merge_requests",
                                    data=than, method="POST",
                                    headers={"PRIVATE-TOKEN": token, "Content-Type": "application/json"})
        with urllib.request.urlopen(yc, timeout=30) as tl:
            return json.load(tl).get("web_url", nhanh)
    return nhanh


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--nam", type=int, default=dt.date.today().year)
    ap.add_argument("--api", action="append", default=[], help="gốc API v1 của một cell hoặc tenant (lặp lại được)")
    ap.add_argument("--tu-tep", action="append", default=[], help="đọc PeriodPage JSON từ tệp (thử nghiệm)")
    ap.add_argument("--truoc", type=int, default=7, help="số ngày đóng băng trước mốc hạn nộp")
    ap.add_argument("--sau", type=int, default=3, help="số ngày đóng băng sau mốc khoá kỳ")
    ap.add_argument("--ra", default=os.path.join(os.path.dirname(__file__), "..", "values-dong-bang.yaml"))
    ap.add_argument("--mo-yeu-cau", action="store_true", help="tạo nhánh, commit ký và mở merge request")
    a = ap.parse_args(argv)
    if not a.api and not a.tu_tep:
        ap.error("cần ít nhất một --api hoặc --tu-tep")
    token = os.environ.get("VT_API_TOKEN")
    nguon = {u: doc_ky(u, a.nam, token) for u in a.api}
    for t in a.tu_tep:
        with open(t, encoding="utf-8") as f:
            nguon[t] = json.load(f)["items"]
    ds = sinh(nguon, a.nam, a.truoc, a.sau)
    with open(a.ra, "w", encoding="utf-8") as f:
        f.write(ghi_values(ds, a.nam, list(nguon)))
    print(f"Đã ghi {len(ds)} cửa sổ bổ sung vào {a.ra}")
    if a.mo_yeu_cau:
        print(f"Đã mở yêu cầu gộp: {mo_yeu_cau(a.ra, a.nam)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
