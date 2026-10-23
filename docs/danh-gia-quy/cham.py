#!/usr/bin/env python3
"""Bộ chấm quý 110 tiêu chí của bản đánh giá kiến trúc (VH-09).

Đọc danh mục tiêu chí `tieu-chi.yaml`, bảng chỉ số `chi-so-chuyen-doi.yaml` và một bản chấm
quý `<năm>-Q<n>.yaml` (hoặc bản chấm cơ sở), tính điểm theo thang của bản đánh giá, so với
bản chấm trước và các mốc đợt, rồi in báo cáo Markdown.

    python docs/danh-gia-quy/cham.py docs/danh-gia-quy/2026-Q4.yaml
    python docs/danh-gia-quy/cham.py docs/danh-gia-quy/2026-Q4.yaml --ra docs/danh-gia-quy/bao-cao/2026-Q4.md
    python docs/danh-gia-quy/cham.py docs/danh-gia-quy/2026-Q4.yaml --nghiem   # cảnh báo thành lỗi

Thang (bản đánh giá, trang 3): Đạt = 1, Một phần = 0,5, Chưa đạt = 0; trục H chấm mức 0–4 quy
về 0–1. Điểm trục là trung bình tiêu chí, điểm tổng là trung bình có trọng số trục (×100);
"/4" là điểm trục nhân 4, ngưỡng đáp ứng cơ bản 3/4.
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml

THU_MUC = Path(__file__).resolve().parent
GOC_REPO = THU_MUC.parent.parent
GIA_TRI_KQ = ("Đạt", "Một phần", "Chưa đạt")
TEN_LOAI = {
    "ma-nguon": "kiểm được trên mã nguồn và kiểm thử tự động",
    "pipeline": "cần pipeline CI thật chạy trên mọi kho",
    "cum-van-hanh": "cần cụm Kubernetes vận hành (staging hoặc production)",
    "thu-tai": "cần thử tải theo PERF trên CELL-PERF",
    "he-thong-ngoai": "cần kết nối hệ thống ngoài thật (đang dùng bản giả lập)",
    "nghiep-vu": "cần nghiệp vụ ký hoặc chạy trọn một kỳ với người dùng thật",
}


class LoiBanCham(Exception):
    """Bản chấm không hợp lệ (thiếu tiêu chí, giá trị sai)."""


@dataclass
class KetQua:
    ma: str
    diem: float  # 0..1
    nhan: str  # "Đạt"/"Một phần"/"Chưa đạt" hoặc "mức n/4"
    bang_chung: list = field(default_factory=list)
    ghi_chu: str = ""
    da_do: bool = False
    chua_do_duoc: str = ""


@dataclass
class BanCham:
    duong_dan: Path
    meta: dict
    ket_qua: dict  # ma -> KetQua
    diem_truc: dict  # truc -> 0..1
    tong: float  # 0..100
    canh_bao: list


def doc_yaml(p: Path) -> dict:
    with open(p, encoding="utf-8") as f:
        return yaml.safe_load(f)


def nap_danh_muc(p: Path | None = None) -> dict:
    dm = doc_yaml(p or THU_MUC / "tieu-chi.yaml")
    dm["_theo_ma"] = {t["ma"]: t for t in dm["tieu_chi"]}
    return dm


def lam_tron(x: float, n: int = 1) -> str:
    """Định dạng số kiểu Việt Nam (dấu phẩy thập phân)."""
    s = f"{x:.{n}f}"
    return s.replace(".", ",")


def _la_duong_dan(s: str) -> bool:
    """Bằng chứng không có khoảng trắng được coi là đường dẫn tương đối với gốc repo."""
    return bool(s) and " " not in s and not s.startswith(("http://", "https://"))


def doc_ket_qua(ma: str, tc: dict, v, giai_doan: str, canh_bao: list) -> KetQua:
    if not isinstance(v, dict):
        v = {"kq": v} if ma[0] != "H" else {"muc": v}
    bang_chung = v.get("bang_chung") or []
    if isinstance(bang_chung, str):
        bang_chung = [bang_chung]
    da_do = bool(v.get("da_do", False))
    if ma[0] == "H":
        muc = v.get("muc")
        if not isinstance(muc, int) or not 0 <= muc <= 4:
            raise LoiBanCham(f"{ma}: trục H cần 'muc' là số nguyên 0–4, nhận {muc!r}")
        kq = KetQua(ma, muc / 4, f"mức {muc}/4", bang_chung, v.get("ghi_chu", ""), da_do,
                    v.get("chua_do_duoc", ""))
        dat_tran = muc == 4
    else:
        nhan = v.get("kq")
        if nhan not in GIA_TRI_KQ:
            raise LoiBanCham(f"{ma}: 'kq' phải là một trong {GIA_TRI_KQ}, nhận {nhan!r}")
        diem = {"Đạt": 1.0, "Một phần": 0.5, "Chưa đạt": 0.0}[nhan]
        kq = KetQua(ma, diem, nhan, bang_chung, v.get("ghi_chu", ""), da_do, v.get("chua_do_duoc", ""))
        dat_tran = nhan == "Đạt"
    # Quy tắc TL-04 chương 13: chỉ chấm "Đạt" khi có bằng chứng nghiệm thu, không chỉ có thiết kế.
    loai = tc["loai_bang_chung"]
    if giai_doan == "phat-trien" and loai != "ma-nguon" and not da_do:
        if not kq.chua_do_duoc:
            kq.chua_do_duoc = TEN_LOAI[loai]
        if dat_tran:
            canh_bao.append(f"{ma}: chấm mức cao nhất nhưng bằng chứng loại '{loai}' chưa đo "
                            f"(đặt 'da_do: true' kèm bằng chứng nếu đã đo)")
    if kq.diem > 0 and not bang_chung:
        canh_bao.append(f"{ma}: có điểm nhưng không ghi bằng chứng")
    for b in bang_chung:
        if _la_duong_dan(b):
            duong = b.split("#")[0].split(":")[0]
            if not (GOC_REPO / duong).exists():
                canh_bao.append(f"{ma}: bằng chứng không tồn tại trong repo: {duong}")
    return kq


def cham(duong_dan: Path, danh_muc: dict | None = None) -> BanCham:
    dm = danh_muc or nap_danh_muc()
    bc = doc_yaml(duong_dan)
    giai_doan = bc.get("giai_doan", "van-hanh")
    raw = bc.get("ket_qua") or {}
    thieu = [m for m in dm["_theo_ma"] if m not in raw]
    la = [m for m in raw if m not in dm["_theo_ma"]]
    if thieu or la:
        raise LoiBanCham(f"{duong_dan.name}: thiếu {thieu or 'không'}; mã lạ {la or 'không'}")
    canh_bao: list = []
    kq = {m: doc_ket_qua(m, dm["_theo_ma"][m], raw[m], giai_doan, canh_bao) for m in dm["_theo_ma"]}
    diem_truc = {}
    for t in dm["truc"]:
        ds = [k.diem for m, k in kq.items() if m[0] == t["ma"]]
        diem_truc[t["ma"]] = sum(ds) / len(ds)
    tong_w = sum(t["trong_so"] for t in dm["truc"])
    tong = 100 * sum(t["trong_so"] * diem_truc[t["ma"]] for t in dm["truc"]) / tong_w
    return BanCham(duong_dan, bc, kq, diem_truc, tong, canh_bao)


def diem_tran_phat_trien(dm: dict) -> float:
    """Điểm cao nhất đạt được khi chưa có môi trường vận hành: tiêu chí kiểm trên mã nguồn đạt,
    tiêu chí khác dừng ở Một phần, trục H dừng ở mức 3."""
    truc = {}
    for t in dm["tieu_chi"]:
        if t["ma"][0] == "H":
            d = 0.75
        else:
            d = 1.0 if t["loai_bang_chung"] == "ma-nguon" else 0.5
        truc.setdefault(t["ma"][0], []).append(d)
    tong_w = sum(x["trong_so"] for x in dm["truc"])
    return 100 * sum(x["trong_so"] * sum(truc[x["ma"]]) / len(truc[x["ma"]]) for x in dm["truc"]) / tong_w


def tim_ban_truoc(ban: BanCham) -> Path | None:
    ten = ban.meta.get("so_sanh_voi")
    if ten:
        p = (ban.duong_dan.parent / ten).resolve()
        if not p.exists():
            raise LoiBanCham(f"so_sanh_voi trỏ tới tệp không có: {ten}")
        return p
    return None


def _so_sanh(gia_tri, phep: str, nguong) -> bool | None:
    if gia_tri is None or nguong is None or isinstance(gia_tri, str) or isinstance(nguong, str):
        return None
    return {">=": gia_tri >= nguong, "<=": gia_tri <= nguong, "==": gia_tri == nguong}[phep]


def bang_chi_so(ban: BanCham) -> list[str]:
    cs = doc_yaml(THU_MUC / "chi-so-chuyen-doi.yaml")
    do = ban.meta.get("chi_so") or {}
    dong = ["| Chỉ số | Cơ sở | Hiện tại | Mốc sau đợt 0 | Mục tiêu nền tảng | Đạt mốc đợt 0 | Nguồn đo |",
            "|---|---|---|---|---|---|---|"]
    for c in cs["chi_so"]:
        v = do.get(c["ma"], {}) or {}
        gt = round(ban.tong, 1) if c["ma"] == "diem-tong-hop" else v.get("gia_tri")
        hien = "chưa đo" if gt is None else (lam_tron(gt) if isinstance(gt, float) else str(gt))
        dat = _so_sanh(gt, c["phep_so_sanh"], c["muc_tieu_dot_0"])
        dat_s = {True: "Đạt", False: "Chưa", None: "–"}[dat]
        nguon = "tự động (Prometheus)" if c["nguon"] == "tu-dong" else "thủ công"
        if v.get("nguoi_do"):
            nguon += f", {v['nguoi_do']}"
        if v.get("ghi_chu"):
            nguon += f"; {v['ghi_chu']}"
        dong.append(f"| {c['ten']} | {c['co_so']} | {hien} | {c['muc_tieu_dot_0_hien_thi']} | "
                    f"{c['muc_tieu_nen_tang']} | {dat_s} | {nguon} |")
    return dong


def bao_cao(ban: BanCham, dm: dict, truoc: BanCham | None) -> str:
    m = ban.meta
    L: list[str] = []
    L.append(f"# Kết quả chấm 110 tiêu chí: {m.get('ky', ban.duong_dan.stem)}")
    L.append("")
    L.append(f"> Sinh bằng `docs/danh-gia-quy/cham.py` từ `{ban.duong_dan.name}`. Không sửa tay; sửa bản chấm rồi chạy lại.")
    L.append("")
    L.append(f"- Hệ thống được chấm: {m.get('he_thong', 'chưa ghi')}")
    L.append(f"- Ngày chấm: {m.get('ngay_cham', 'chưa ghi')}; người chấm: {m.get('nguoi_cham', 'chưa ghi')}"
             f"; độc lập với người làm: {'có' if m.get('doc_lap') else 'không'}")
    L.append(f"- Giai đoạn: {'phát triển' if m.get('giai_doan') == 'phat-trien' else 'vận hành'}")
    L.append("")
    L.append("## Điểm tổng hợp")
    L.append("")
    dat = sum(1 for k in ban.ket_qua.values() if k.nhan == "Đạt")
    mot_phan = sum(1 for k in ban.ket_qua.values() if k.nhan == "Một phần")
    L.append(f"Điểm đáp ứng tổng hợp có trọng số: **{lam_tron(ban.tong)}/100**. "
             f"Trục A–L ngoài H: {dat} tiêu chí Đạt, {mot_phan} Một phần; trục H theo mức 0–4.")
    if truoc:
        d = ban.tong - truoc.tong
        L.append(f"So với `{truoc.duong_dan.name}` ({lam_tron(truoc.tong)}): {'+' if d >= 0 else ''}{lam_tron(d)} điểm.")
    moc = [f"đợt {x['dot']} từ {x['diem']}: {'đạt' if ban.tong >= x['diem'] else 'chưa đạt'}" for x in dm["moc"]]
    L.append("Đối chiếu mốc TL-01: " + "; ".join(moc) + ".")
    if m.get("giai_doan") == "phat-trien" or not m.get("doc_lap"):
        L.append("Kết quả đối chiếu mốc chỉ để tham khảo, chưa dùng để công bố đạt mốc: "
                 + ("bản chấm thuộc giai đoạn phát triển, tiêu chí cần môi trường vận hành chưa có bằng chứng nghiệm thu"
                    if m.get("giai_doan") == "phat-trien" else "")
                 + ("; " if m.get("giai_doan") == "phat-trien" and not m.get("doc_lap") else "")
                 + ("người chấm chưa độc lập với người làm" if not m.get("doc_lap") else "")
                 + " (quy-trinh.md mục 2 và 4).")
    if m.get("giai_doan") == "phat-trien":
        L.append(f"Điểm trần của giai đoạn phát triển (tiêu chí kiểm trên mã nguồn đạt hết, tiêu chí cần "
                 f"môi trường dừng ở Một phần, trục H dừng ở mức 3): {lam_tron(diem_tran_phat_trien(dm))}.")
    L.append("")
    L.append("| Trục | Tên | Trọng số | Điểm | /4 |" + (" Trước | Chênh |" if truoc else ""))
    L.append("|---|---|---|---|---|" + ("---|---|" if truoc else ""))
    for t in dm["truc"]:
        a = t["ma"]
        s = ban.diem_truc[a]
        row = f"| {a} | {t['ten']} | {t['trong_so']} | {round(s * 100):d}% | {lam_tron(s * 4)} |"
        if truoc:
            p = truoc.diem_truc[a]
            row += f" {round(p * 100):d}% | {'+' if s >= p else ''}{round((s - p) * 100):d} |"
        L.append(row)
    L.append("")
    if truoc:
        tut = [x for x in ban.ket_qua if ban.ket_qua[x].diem < truoc.ket_qua[x].diem]
        len_ = [x for x in ban.ket_qua if ban.ket_qua[x].diem > truoc.ket_qua[x].diem]
        L.append("## Thay đổi so với lần chấm trước")
        L.append("")
        L.append(f"{len(len_)} tiêu chí tăng, {len(tut)} tiêu chí tụt.")
        if tut:
            L.append("")
            L.append("Tiêu chí tụt điểm (xử lý theo quy-trinh.md mục 5):")
            L.append("")
            for x in tut:
                L.append(f"- {x} {dm['_theo_ma'][x]['ten']}: {truoc.ket_qua[x].nhan} → {ban.ket_qua[x].nhan}")
        L.append("")
    L.append("## Tiêu chí chưa đo được trong giai đoạn này")
    L.append("")
    nhom: dict = {}
    for x, k in ban.ket_qua.items():
        if k.chua_do_duoc:
            nhom.setdefault(dm["_theo_ma"][x]["loai_bang_chung"], []).append(x)
    if not nhom:
        L.append("Không có.")
    for loai, ds in nhom.items():
        mo_ta = TEN_LOAI[loai]
        L.append(f"- {mo_ta[0].upper()}{mo_ta[1:]} ({len(ds)}): {', '.join(ds)}")
    L.append("")
    L.append("## Bảng chỉ số chuyển đổi")
    L.append("")
    L.extend(bang_chi_so(ban))
    L.append("")
    L.append("## Chi tiết từng tiêu chí")
    for t in dm["truc"]:
        a = t["ma"]
        L.append("")
        L.append(f"### Trục {a}: {t['ten']}")
        L.append("")
        L.append("| Mã | Tiêu chí | Cơ sở | Kết quả | Bằng chứng | Ghi chú |")
        L.append("|---|---|---|---|---|---|")
        for x, k in ban.ket_qua.items():
            if x[0] != a:
                continue
            tc = dm["_theo_ma"][x]
            bc = "; ".join(f"`{b}`" if _la_duong_dan(b) else b for b in k.bang_chung) or "–"
            bc = bc.replace("|", "\\|")
            gc = k.ghi_chu.replace("|", "\\|")
            if k.chua_do_duoc:
                gc = (gc + " " if gc else "") + f"(Chưa đo: {k.chua_do_duoc}.)"
            L.append(f"| {x} | {tc['ten']} | {tc['co_so']} | {k.nhan} | {bc} | {gc or '–'} |")
    if ban.canh_bao:
        L.append("")
        L.append("## Cảnh báo của bộ chấm")
        L.append("")
        for c in ban.canh_bao:
            L.append(f"- {c}")
    L.append("")
    return "\n".join(L)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Chấm 110 tiêu chí theo quý (VH-09)")
    ap.add_argument("ban_cham", type=Path, help="tệp <năm>-Q<n>.yaml hoặc bản chấm cơ sở")
    ap.add_argument("--ra", type=Path, help="ghi báo cáo Markdown ra tệp thay vì in")
    ap.add_argument("--nghiem", action="store_true", help="coi cảnh báo là lỗi (dùng khi chốt bản chấm)")
    a = ap.parse_args(argv)
    try:
        dm = nap_danh_muc()
        ban = cham(a.ban_cham, dm)
        p_truoc = tim_ban_truoc(ban)
        truoc = cham(p_truoc, dm) if p_truoc else None
    except (LoiBanCham, OSError, yaml.YAMLError) as e:
        print(f"Lỗi: {e}", file=sys.stderr)
        return 1
    md = bao_cao(ban, dm, truoc)
    if a.ra:
        a.ra.parent.mkdir(parents=True, exist_ok=True)
        a.ra.write_text(md, encoding="utf-8")
    else:
        sys.stdout.write(md)
    print(f"Điểm tổng hợp {ban.meta.get('ky', a.ban_cham.stem)}: {lam_tron(ban.tong)}/100"
          + (f" (trước: {lam_tron(truoc.tong)})" if truoc else "")
          + f"; {len(ban.canh_bao)} cảnh báo", file=sys.stderr)
    for c in ban.canh_bao:
        print(f"  cảnh báo: {c}", file=sys.stderr)
    return 2 if a.nghiem and ban.canh_bao else 0


if __name__ == "__main__":
    sys.exit(main())
