#!/usr/bin/env python3
"""Công cụ của kế hoạch đưa tenant vào sử dụng và mở rộng theo nhóm (VH-08, OPS-07, OPS-04, OPS-06).

Lệnh:
  kiem-tra   Kiểm bảng nhóm nhom-tenant.yaml: mỗi tenant đúng một nhóm, mô hình khớp cell, nhóm bắt đầu
             từ đầu kỳ, mốc sẵn sàng trước cửa sổ đóng băng, nhóm sau chỉ vào khi nhóm trước đã ra,
             sức chứa cell (cảnh báo khi vượt ngưỡng rà soát OPS-23). In lịch các mốc của từng nhóm.
  dien-tap   Diễn tập đưa một nhóm vào sử dụng trên bản giả lập: nạp và đối soát dữ liệu khởi tạo
             (số dòng và băm), đi qua vòng đời tenant theo đúng bảng chuyển trạng thái của
             tenant-registry, bật cờ, mở đăng nhập, rồi tắt cờ một tenant và tạm ngừng một tenant.
             Ghi bảng theo dõi tỉ lệ sử dụng.
  tu-kiem    Tự kiểm công cụ: đối soát phải phát hiện dòng mất, dòng sửa, dòng trùng; bảng chuyển trạng
             thái phải chặn bước nhảy sai.

Ví dụ:
  python3 docs/van-hanh/dua_vao_su_dung.py kiem-tra
  python3 docs/van-hanh/dua_vao_su_dung.py dien-tap --nhom N-THU --tat-co huyen-thu-nghiem-mot \
      --tam-ngung tinh-thu-nghiem-hai --ghi docs/van-hanh/theo-doi-ti-le.csv
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import io
import sys
import unicodedata
from pathlib import Path

import yaml

THU_MUC = Path(__file__).resolve().parent
REPO = THU_MUC.parents[1]

# Bảng chuyển trạng thái chép đúng services-go/tenant-registry/internal/tenant/domain.go (TL-02a QT-01).
CHUYEN = {
    "": {"KHOI_TAO"},
    "KHOI_TAO": {"DANG_CAP_PHAT", "DA_CHAM_DUT"},
    "DANG_CAP_PHAT": {"DANG_CAU_HINH", "KHOI_TAO"},
    "DANG_CAU_HINH": {"SAN_SANG"},
    "SAN_SANG": {"DANG_VAN_HANH"},
    "DANG_VAN_HANH": {"TAM_NGUNG", "DANG_CHUYEN_CELL"},
    "TAM_NGUNG": {"DANG_VAN_HANH", "DA_CHAM_DUT"},
    "DANG_CHUYEN_CELL": {"DANG_VAN_HANH"},
    "DA_CHAM_DUT": set(),
}

# Sức chứa thiết kế theo hạng cell (GD-01, deploy/capacity/assumptions.yaml); đọc lại từ tệp nếu có.
SUC_CHUA_MAC_DINH = {"compact": 50000, "S": 300000, "M": 800000, "L": 1200000}

# Cờ bật cho một tenant khi đưa vào sử dụng (OPS-04). Tên mô tả, tên thật theo danh mục cờ của cell.
CO_MAC_DINH = ["mo-dang-nhap", "giao-dien-moi", "nhac-viec-qua-thong-bao"]


class LoiKeHoach(Exception):
    pass


# ---------------------------------------------------------------- lịch, cửa sổ đóng băng, ngày làm việc


def dau_ky(ma_ky: str) -> dt.date:
    """'2027-Q2' -> 01/04/2027."""
    nam, quy = ma_ky.split("-Q")
    return dt.date(int(nam), (int(quy) - 1) * 3 + 1, 1)


def cuoi_ky(ma_ky: str) -> dt.date:
    d = dau_ky(ma_ky)
    thang = d.month + 3
    nam = d.year + (thang > 12)
    thang = thang - 12 if thang > 12 else thang
    return dt.date(nam, thang, 1) - dt.timedelta(days=1)


def cua_so_dong_bang(nam: int) -> list[tuple[dt.date, dt.date]]:
    """OPS-06: 24 tháng cuối quý tới 10 tháng đầu quý sau; 20/1 tới 05/2."""
    ds = [(dt.date(nam, 1, 20), dt.date(nam, 2, 5))]
    for thang in (3, 6, 9, 12):
        sau = dt.date(nam + (thang == 12), 1 if thang == 12 else thang + 1, 10)
        ds.append((dt.date(nam, thang, 24), sau))
    return ds


def trong_dong_bang(d: dt.date) -> bool:
    return any(a <= d <= b for nam in (d.year - 1, d.year) for a, b in cua_so_dong_bang(nam))


def lui_ngay_lam_viec(d: dt.date, n: int, nghi: set[dt.date]) -> dt.date:
    while n > 0:
        d -= dt.timedelta(days=1)
        if d.weekday() < 5 and d not in nghi:
            n -= 1
    return d


def tien_ngay_lam_viec(d: dt.date, n: int, nghi: set[dt.date]) -> dt.date:
    while n > 0:
        d += dt.timedelta(days=1)
        if d.weekday() < 5 and d not in nghi:
            n -= 1
    return d


def moc_nhom(ky_dau: str, nghi: set[dt.date]) -> dict[str, dt.date]:
    """Các mốc của một nhóm, tính lùi từ ngày bắt đầu cửa sổ đóng băng trước kỳ đầu (ke-hoach-mo-rong.md mục 5)."""
    t = dau_ky(ky_dau)
    s = next(a for nam in (t.year - 1, t.year) for a, b in cua_so_dong_bang(nam) if a <= t <= b)
    han_san_sang = s - dt.timedelta(days=1)
    m = {
        "xet_vao": lui_ngay_lam_viec(s, 20, nghi),
        "onboarding": lui_ngay_lam_viec(s, 15, nghi),
        "nap_du_lieu": lui_ngay_lam_viec(s, 12, nghi),
        "doi_soat": lui_ngay_lam_viec(s, 8, nghi),
        "bat_co_mo_dang_nhap": lui_ngay_lam_viec(s, 5, nghi),
        "han_san_sang": han_san_sang,
        "bat_dau_dong_bang": s,
        "ky_dau": t,
        "xet_ra": t + dt.timedelta(days=30),
        "het_ky_dau": cuoi_ky(ky_dau),
    }
    return m


# ---------------------------------------------------------------- bảng nhóm


def doc_suc_chua() -> dict[str, int]:
    p = REPO / "deploy/capacity/assumptions.yaml"
    try:
        gd = yaml.safe_load(p.read_text(encoding="utf-8"))["gd"]["GD-01"]["giaTri"]
        return {k: int(v) for k, v in gd.items()}
    except (OSError, KeyError, TypeError, ValueError):
        return dict(SUC_CHUA_MAC_DINH)


def doc_cell(ten: str) -> dict:
    p = REPO / f"deploy/cells/{ten}/cell.yaml"
    if not p.is_file():
        raise LoiKeHoach(f"không có cell '{ten}' trong deploy/cells")
    c = yaml.safe_load(p.read_text(encoding="utf-8"))["cell"]
    return {"ten": c.get("displayName", ten), "hang": c.get("sizing"), "mo_hinh": c.get("tenancy")}


def doc_bang(p: Path) -> dict:
    return yaml.safe_load(p.read_text(encoding="utf-8"))


def kiem_bang(bang: dict) -> tuple[list[str], list[str], dict[str, dict]]:
    loi: list[str] = []
    canh_bao: list[str] = []
    nghi = {dt.date.fromisoformat(str(x)) for x in bang.get("ngay_nghi_them") or []}
    suc_chua = doc_suc_chua()
    nguong = float(bang.get("nguong_ra_soat_cell", 0.8))
    da_gap: dict[str, str] = {}
    lich: dict[str, dict] = {}
    tai_cell: dict[str, int] = {}
    nhom_theo_ma = {n["ma"]: n for n in bang["nhom"]}
    for n in bang["nhom"]:
        ma = n["ma"]
        try:
            m = moc_nhom(n["ky_dau"], nghi)
        except (ValueError, StopIteration) as e:
            loi.append(f"{ma}: kỳ đầu '{n.get('ky_dau')}' không hợp lệ ({e})")
            continue
        lich[ma] = m
        for k in ("onboarding", "nap_du_lieu", "doi_soat", "bat_co_mo_dang_nhap", "han_san_sang"):
            if trong_dong_bang(m[k]):
                loi.append(f"{ma}: mốc {k} {m[k]:%d/%m/%Y} rơi vào cửa sổ đóng băng")
        for t in n["tenant"]:
            if t["ma"] in da_gap:
                loi.append(f"tenant {t['ma']} nằm ở cả nhóm {da_gap[t['ma']]} và {ma}")
            da_gap[t["ma"]] = ma
            try:
                c = doc_cell(t["cell"])
            except LoiKeHoach as e:
                loi.append(f"{ma}/{t['ma']}: {e}")
                continue
            if c["mo_hinh"] != t["mo_hinh"]:
                loi.append(f"{ma}/{t['ma']}: mô hình {t['mo_hinh']} không khớp cell {c['ten']} ({c['mo_hinh']})")
            if not n.get("chi_dien_tap"):
                tai_cell[t["cell"]] = tai_cell.get(t["cell"], 0) + int(t["tai_khoan"])
                cap = suc_chua.get(c["hang"], 0)
                if cap and tai_cell[t["cell"]] > cap * nguong:
                    canh_bao.append(
                        f"{ma}: cell {c['ten']} lên {tai_cell[t['cell']]:,} tài khoản, vượt {nguong:.0%} sức chứa"
                        f" {cap:,}; mở rà soát thêm cell OPS-23 hoặc chuyển tenant OPS-08 trước nhóm này"
                    )
        for truoc in n.get("dieu_kien_truoc") or []:
            if truoc not in lich:
                loi.append(f"{ma}: nhóm điều kiện {truoc} không có hoặc khai báo sau")
                continue
            # Nhóm thí điểm phải xong trọn kỳ đầu và nghiệm thu (khoảng 20 ngày sau hết kỳ, QA-16);
            # nhóm khác phải qua xét ra (30 ngày sau ngày bắt đầu kỳ đầu).
            ra = lich[truoc]["het_ky_dau"] + dt.timedelta(days=20) if truoc == "N0" else lich[truoc]["xet_ra"]
            if nhom_theo_ma[truoc].get("chi_dien_tap"):
                loi.append(f"{ma}: không được phụ thuộc nhóm chỉ diễn tập {truoc}")
            if m["xet_vao"] < ra:
                loi.append(f"{ma}: xét vào {m['xet_vao']:%d/%m/%Y} trước khi nhóm {truoc} ra ({ra:%d/%m/%Y})")
    return loi, canh_bao, lich


def lenh_kiem_tra(a: argparse.Namespace) -> int:
    bang = doc_bang(Path(a.bang))
    loi, canh_bao, lich = kiem_bang(bang)
    print("| Nhóm | Số tenant | Tài khoản | Xét vào | Onboarding | Hạn sẵn sàng | Kỳ đầu | Xét ra |")
    print("|---|---|---|---|---|---|---|---|")
    for n in bang["nhom"]:
        m = lich.get(n["ma"])
        if not m:
            continue
        tk = sum(int(t["tai_khoan"]) for t in n["tenant"])
        nhan = n["ma"] + (" (diễn tập)" if n.get("chi_dien_tap") else "")
        print(
            f"| {nhan} | {len(n['tenant'])} | {tk:,} | {m['xet_vao']:%d/%m/%Y} | {m['onboarding']:%d/%m/%Y} |"
            f" {m['han_san_sang']:%d/%m/%Y} | {m['ky_dau']:%d/%m/%Y} | {m['xet_ra']:%d/%m/%Y} |"
        )
    for c in canh_bao:
        print("CẢNH BÁO:", c)
    for l in loi:
        print("LỖI:", l)
    print(f"Kết quả kiểm bảng nhóm: {'đạt' if not loi else f'{len(loi)} lỗi'}, {len(canh_bao)} cảnh báo.")
    return 1 if loi else 0


# ---------------------------------------------------------------- dữ liệu khởi tạo và đối soát


def chuan_hoa(gt: str) -> str:
    return " ".join(unicodedata.normalize("NFC", gt or "").split())


def doc_csv(p: Path) -> list[dict]:
    with p.open(encoding="utf-8", newline="") as f:
        return [{k: chuan_hoa(v) for k, v in r.items()} for r in csv.DictReader(f)]


def bam(dong: list[dict], khoa: list[str]) -> str:
    h = hashlib.sha256()
    for r in sorted(dong, key=lambda r: [r[k] for k in khoa]):
        h.update(("\x1f".join(r[k] for k in khoa) + "\n").encode("utf-8"))
    return h.hexdigest()


def nap(don_vi: list[dict], nhan_su: list[dict]) -> tuple[list[dict], list[dict], list[str]]:
    """Mô phỏng bước nạp dữ liệu khởi tạo (OPS-07 bước 5): bỏ dòng không hợp lệ, ghi lý do."""
    loai: list[str] = []
    dv_ok: dict[str, dict] = {}
    for r in don_vi:
        if r["ma_don_vi"] in dv_ok:
            loai.append(f"đơn vị trùng mã {r['ma_don_vi']}")
            continue
        dv_ok[r["ma_don_vi"]] = r
    for r in list(dv_ok.values()):
        if r["ma_don_vi_cha"] and r["ma_don_vi_cha"] not in dv_ok:
            loai.append(f"đơn vị {r['ma_don_vi']} có đơn vị cha {r['ma_don_vi_cha']} không tồn tại")
            del dv_ok[r["ma_don_vi"]]
    ns_ok: dict[str, dict] = {}
    for r in nhan_su:
        if r["ma_can_bo"] in ns_ok:
            loai.append(f"cán bộ trùng mã {r['ma_can_bo']}")
        elif r["ma_don_vi"] not in dv_ok:
            loai.append(f"cán bộ {r['ma_can_bo']} thuộc đơn vị {r['ma_don_vi']} không tồn tại")
        else:
            ns_ok[r["ma_can_bo"]] = r
    return list(dv_ok.values()), list(ns_ok.values()), loai


KHOA_DV = ["ma_don_vi", "ten_don_vi", "ma_don_vi_cha"]
KHOA_NS = ["ma_can_bo", "ho_ten", "ma_don_vi", "chuc_vu", "ten_dang_nhap"]


def doi_soat(nguon_dv, nguon_ns, nap_dv, nap_ns) -> list[str]:
    lech = []
    for ten, a, b, khoa in (("đơn vị", nguon_dv, nap_dv, KHOA_DV), ("nhân sự", nguon_ns, nap_ns, KHOA_NS)):
        if len(a) != len(b):
            lech.append(f"{ten}: nguồn {len(a)} dòng, đã nạp {len(b)} dòng")
        elif bam(a, khoa) != bam(b, khoa):
            lech.append(f"{ten}: cùng {len(a)} dòng nhưng băm khác")
    return lech


# ---------------------------------------------------------------- diễn tập


class Tenant:
    def __init__(self, khai_bao: dict):
        self.kb = khai_bao
        self.trang_thai = ""
        self.co: set[str] = set()
        self.dang_nhap = False
        self.ghi = False
        self.nhat_ky: list[str] = []

    def chuyen(self, sang: str, ly_do: str) -> None:
        if sang not in CHUYEN[self.trang_thai]:
            raise LoiKeHoach(f"{self.kb['ma']}: không chuyển được từ '{self.trang_thai}' sang '{sang}'")
        self.trang_thai = sang
        self.dang_nhap = self.ghi = sang == "DANG_VAN_HANH" and "mo-dang-nhap" in self.co
        self.nhat_ky.append(f"{sang}: {ly_do}")


def lenh_dien_tap(a: argparse.Namespace) -> int:
    bang = doc_bang(Path(a.bang))
    nhom = next((n for n in bang["nhom"] if n["ma"] == a.nhom), None)
    if not nhom:
        print(f"Không có nhóm {a.nhom}")
        return 2
    gio = dt.datetime.now().replace(microsecond=0)
    buoc = 0

    def ghi(noi_dung: str) -> None:
        nonlocal buoc
        buoc += 1
        print(f"[{buoc:02d}] {noi_dung}")

    ghi(f"Bắt đầu diễn tập nhóm {nhom['ma']} ({nhom['ten']}), {len(nhom['tenant'])} tenant, lúc {gio:%H:%M:%S %d/%m/%Y}")
    loi_bang, _, _ = kiem_bang(bang)
    ghi(f"Tiêu chí vào: bảng nhóm {'đạt' if not loi_bang else 'KHÔNG ĐẠT'} ({len(loi_bang)} lỗi)")
    if loi_bang:
        for l in loi_bang:
            print("    ", l)
        return 1
    ds: dict[str, Tenant] = {}
    so_lieu: dict[str, dict] = {}
    for kb in nhom["tenant"]:
        t = Tenant(kb)
        ds[kb["ma"]] = t
        thu = THU_MUC / kb["du_lieu"]
        t.chuyen("KHOI_TAO", "hồ sơ tenant đã duyệt")
        t.chuyen("DANG_CAP_PHAT", f"cell {kb['cell']}, mô hình {kb['mo_hinh']}")
        t.chuyen("DANG_CAU_HINH", "cấp phát xong")
        nguon_dv, nguon_ns = doc_csv(thu / "don-vi.csv"), doc_csv(thu / "nhan-su.csv")
        nap_dv, nap_ns, loai = nap(nguon_dv, nguon_ns)
        lech = doi_soat(nguon_dv, nguon_ns, nap_dv, nap_ns)
        ghi(
            f"{kb['ma']}: nạp {len(nap_dv)} đơn vị, {len(nap_ns)} cán bộ; loại {len(loai)} dòng;"
            f" đối soát {'khớp' if not lech else 'LỆCH: ' + '; '.join(lech)}"
            f" (băm nhân sự {bam(nap_ns, KHOA_NS)[:16]}…)"
        )
        if lech or len(nap_ns) != int(kb["tai_khoan"]):
            ghi(f"{kb['ma']}: dừng ở trạng thái {t.trang_thai}, chưa đạt tiêu chí vào V-03")
            return 1
        so_lieu[kb["ma"]] = {"tai_khoan": len(nap_ns)}
        t.chuyen("SAN_SANG", "kiểm tra khói đạt, dữ liệu khởi tạo khớp nguồn")
        t.co |= set(CO_MAC_DINH)
        t.chuyen("DANG_VAN_HANH", "khách hàng xác nhận, bật cờ và mở đăng nhập")
        ghi(f"{kb['ma']}: {t.trang_thai}, cờ bật {sorted(t.co)}, đăng nhập {'mở' if t.dang_nhap else 'đóng'}")
    for ma, t in ds.items():
        so_lieu[ma]["hoat_dong"] = so_lieu[ma]["tai_khoan"] if t.dang_nhap else 0

    if a.tat_co:
        t = ds[a.tat_co]
        truoc = {m: set(x.co) for m, x in ds.items() if m != a.tat_co}
        t.co.discard("giao-dien-moi")
        t.co.discard("nhac-viec-qua-thong-bao")
        khac_doi = [m for m, c in truoc.items() if ds[m].co != c]
        ghi(
            f"Sự cố giả định SEV-3 ở {a.tat_co}: tắt cờ tính năng mới (mức D-1), còn {sorted(t.co)};"
            f" tenant khác {'không bị ảnh hưởng' if not khac_doi else 'BỊ ẢNH HƯỞNG: ' + ', '.join(khac_doi)}"
        )
        if khac_doi:
            return 1
    if a.tam_ngung:
        t = ds[a.tam_ngung]
        t.chuyen("TAM_NGUNG", "sự cố giả định SEV-1, tạm ngừng tenant (mức D-3)")
        ghi(f"{a.tam_ngung}: {t.trang_thai}, đăng nhập {'mở' if t.dang_nhap else 'chặn'}, ghi {'mở' if t.ghi else 'chặn'}; dữ liệu giữ nguyên")
        so_lieu[a.tam_ngung]["hoat_dong_khi_ngung"] = 0
        con_lai = [m for m, x in ds.items() if m != a.tam_ngung and not x.dang_nhap]
        ghi(f"Tenant còn lại của nhóm {'vẫn dùng bình thường' if not con_lai else 'BỊ CHẶN: ' + ', '.join(con_lai)}")
        try:
            t.chuyen("SAN_SANG", "thử quay về trạng thái trước kích hoạt")
            ghi("LỖI: bảng chuyển trạng thái cho phép bước không hợp lệ")
            return 1
        except LoiKeHoach:
            ghi("Bảng chuyển trạng thái chặn bước TAM_NGUNG → SAN_SANG như thiết kế")
        t.chuyen("DANG_VAN_HANH", "đã khắc phục, mở lại")
        ghi(f"{a.tam_ngung}: mở lại, {t.trang_thai}, đăng nhập {'mở' if t.dang_nhap else 'chặn'}")
        if con_lai or not t.dang_nhap:
            return 1
    if a.ghi:
        p = Path(a.ghi)
        with p.open("w", encoding="utf-8", newline="") as f:
            w = csv.writer(f)
            w.writerow(["nhom", "tenant", "ngay_do", "tai_khoan_da_cap", "nguoi_dung_hoat_dong_30_ngay", "ti_le", "ghi_chu"])
            for kb in nhom["tenant"]:
                s = so_lieu[kb["ma"]]
                ghi_chu = "diễn tập, số liệu giả lập"
                if "hoat_dong_khi_ngung" in s:
                    ghi_chu += "; có thời gian tạm ngừng trong diễn tập"
                w.writerow([nhom["ma"], kb["ma"], gio.date().isoformat(), s["tai_khoan"], s["hoat_dong"],
                            f"{s['hoat_dong'] / s['tai_khoan']:.2f}", ghi_chu])
        ghi(f"Đã ghi bảng theo dõi tỉ lệ sử dụng vào {p.name}")
    for ma, t in ds.items():
        print(f"     Vòng đời {ma}: " + " → ".join(x.split(":")[0] for x in t.nhat_ky))
    ghi("Kết thúc diễn tập: đạt")
    return 0


# ---------------------------------------------------------------- tự kiểm


def lenh_tu_kiem(_: argparse.Namespace) -> int:
    dv = [{"ma_don_vi": "A", "ten_don_vi": "Đơn vị A", "ma_don_vi_cha": ""}]
    ns = [{"ma_can_bo": f"C{i}", "ho_ten": f"Cán bộ {i}", "ma_don_vi": "A", "chuc_vu": "Chuyên viên",
           "ten_dang_nhap": f"c{i}"} for i in range(5)]
    ca = []
    ndv, nns, _ = nap(dv, ns)
    ca.append(("dữ liệu sạch khớp", not doi_soat(dv, ns, ndv, nns)))
    ca.append(("mất một dòng bị phát hiện", bool(doi_soat(dv, ns, ndv, nns[:-1]))))
    sua = [dict(r) for r in nns]
    sua[0]["ho_ten"] = "Tên khác"
    ca.append(("sửa một trường bị phát hiện", bool(doi_soat(dv, ns, ndv, sua))))
    ndv2, nns2, loai = nap(dv, ns + [dict(ns[0])])
    ca.append(("dòng trùng bị loại và đối soát báo lệch", bool(loai) and bool(doi_soat(dv, ns + [ns[0]], ndv2, nns2))))
    ca.append(("chuẩn hoá Unicode và khoảng trắng", chuan_hoa("  Nguyễn   Văn ") == "Nguyễn Văn"))
    t = Tenant({"ma": "x"})
    try:
        t.chuyen("DANG_VAN_HANH", "nhảy cóc")
        ca.append(("chặn bước nhảy cóc", False))
    except LoiKeHoach:
        ca.append(("chặn bước nhảy cóc", True))
    ca.append(("01/04 nằm trong đóng băng", trong_dong_bang(dt.date(2027, 4, 1))))
    ca.append(("23/03 ngoài đóng băng", not trong_dong_bang(dt.date(2027, 3, 23))))
    ca.append(("hạn sẵn sàng kỳ Q2/2027 là 23/03/2027", moc_nhom("2027-Q2", set())["han_san_sang"] == dt.date(2027, 3, 23)))
    ok = all(x for _, x in ca)
    for ten, x in ca:
        print(f"{'đạt' if x else 'HỎNG'}  {ten}")
    print(f"Tự kiểm: {sum(x for _, x in ca)}/{len(ca)} ca đạt.")
    return 0 if ok else 1


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bang", default=str(THU_MUC / "nhom-tenant.yaml"))
    sub = ap.add_subparsers(dest="lenh", required=True)
    sub.add_parser("kiem-tra")
    d = sub.add_parser("dien-tap")
    d.add_argument("--nhom", required=True)
    d.add_argument("--tat-co")
    d.add_argument("--tam-ngung")
    d.add_argument("--ghi")
    sub.add_parser("tu-kiem")
    a = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    return {"kiem-tra": lenh_kiem_tra, "dien-tap": lenh_dien_tap, "tu-kiem": lenh_tu_kiem}[a.lenh](a)


if __name__ == "__main__":
    sys.exit(main())
