"""Chạy bộ ca mẫu (ca vàng) của mọi Policy Pack và in ma trận phủ (DL-03, BR-15-08).

Cách chạy:
    python -I data/golden/chay_ca_mau.py data/policy-pack              # chạy, in kết quả và ma trận phủ
    python -I data/golden/chay_ca_mau.py data/policy-pack --ghi-bang   # ghi thêm bảng đối chiếu và tệp nạp ca vào data/golden/

Tập lệnh là bản hiện thực tham chiếu độc lập với scoring-service: công thức theo BR-09-01..BR-09-13,
BR-10-02..BR-10-05 viết lại bằng số hữu tỷ chính xác (fractions), tham số đọc từ `params` của
policy.yaml (ca được ghi đè bằng khoá `tham_so`), còn bảng quyết định được đọc thẳng từ tệp DMN
bằng bộ đánh giá FEEL rút gọn (chỉ các dạng ô có trong gói: -, so sánh, khoảng, danh sách chuỗi,
true/false). Vì vậy sửa ngưỡng trong DMN hay tham số trong policy.yaml là đủ để thấy ca nào đổi kết
quả, không cần sửa tập lệnh. Biểu thức CEL trong policy.yaml do scoring-service kiểm bằng chính bộ
ca này; tập lệnh không thông dịch CEL.

Ca đạt khi mọi khoá trong `mong_doi` khớp giá trị tính được. Số so sánh chính xác sau khi làm tròn
nửa lên: điểm theo `rounding.diem`, tỷ lệ vượt theo quy tắc làm tròn của biến đó, chỉ số nhiệm vụ
ở 4 chữ số. Cần Python 3.12 và PyYAML.

Từ DL-16, tập lệnh chạy thêm các loại ca của cách tính điểm theo điểm công việc (gói kế thừa như
twd/diem-cong-viec): VIEC (điểm một công việc), QUAN_LY (điểm quản lý của KPI, nhiệm vụ, KR sau phần
giảm), CA_NHAN (điểm quý của một người trên phạm vi phụ trách, theo tỷ lệ có hệ số hoặc gói điểm),
NAM_DIEM (điểm năm theo cách dùng kết quả các quý trước), XEP_LOAI_CHUOI (chuỗi bước xếp loại khai ở
classification.chuoi, mỗi bước là một bảng DMN) và XEP_LOAI_LO (đếm mức của một lô ca, có thể đổi tham
số hoặc dữ liệu của từng ca để thử tình huống). Công thức đọc tham số của gói, không có hằng số theo
khách hàng.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path

import yaml

DMN_NS = {"d": "https://www.omg.org/spec/DMN/20211108/MODEL/"}
THU_TU_MAC_DINH = ["HTXSNV", "HTTNV", "HTNV", "KHTNV"]
QUY_DOI_TCC = {"THUC_HIEN_TOT": "HTXSNV", "DAM_BAO": "HTTNV", "CO_BAN_DAM_BAO": "HTNV", "KHONG_DAM_BAO": "KHTNV"}


def F(x) -> Fraction:
    return Fraction(str(x)) if isinstance(x, float) else Fraction(x)


def lam_tron(x: Fraction, chu_so: int) -> Fraction:
    """Làm tròn nửa lên (NUA_LEN, HALF_UP) trên số không âm."""
    he = 10 ** chu_so
    return Fraction(int(x * he + Fraction(1, 2)), he)


def hien(x) -> str:
    if isinstance(x, Fraction):
        s = f"{float(x):.4f}".rstrip("0")
        return s + "0" if s.endswith(".") else s
    if isinstance(x, list):
        return "[" + ", ".join(hien(v) for v in x) + "]"
    if isinstance(x, dict):
        return "{" + ", ".join(f"{k}: {hien(v)}" for k, v in x.items()) + "}"
    return str(x).lower() if isinstance(x, bool) else str(x)


# ----------------------------------------------------------------------------- DMN, FEEL rút gọn
@dataclass
class Dong:
    id: str
    o: list[str]
    ket_qua: str


@dataclass
class Bang:
    tep: str
    dau_vao: list[str]
    dong: list[Dong]

    def tra(self, gia_tri: dict) -> Dong:
        for d in self.dong:  # hit policy FIRST
            if all(khop(o, gia_tri[ten]) for o, ten in zip(d.o, self.dau_vao)):
                return d
        raise ValueError(f"{self.tep}: không dòng nào khớp {gia_tri}")


def doc_dmn(tep: Path) -> Bang:
    goc = ET.parse(tep).getroot()
    bang = goc.find(".//d:decisionTable", DMN_NS)
    if bang.get("hitPolicy", "UNIQUE") != "FIRST":
        raise ValueError(f"{tep}: tập lệnh chỉ hỗ trợ hit policy FIRST")
    dau_vao = [i.find("d:inputExpression/d:text", DMN_NS).text.strip() for i in bang.findall("d:input", DMN_NS)]
    dong = []
    for r in bang.findall("d:rule", DMN_NS):
        o = [e.find("d:text", DMN_NS).text.strip() for e in r.findall("d:inputEntry", DMN_NS)]
        kq = r.find("d:outputEntry/d:text", DMN_NS).text.strip().strip('"')
        dong.append(Dong(r.get("id"), o, kq))
    return Bang(tep.name, dau_vao, dong)


SO = r"-?\d+(?:\.\d+)?"


def khop(o: str, v) -> bool:
    o = o.strip()
    if o == "-":
        return True
    if o in ("true", "false"):
        return v is (o == "true")
    if o.startswith('"'):
        return v in [s.strip().strip('"') for s in o.split(",")]
    m = re.fullmatch(rf"(<=|>=|<|>)\s*({SO})", o)
    if m:
        a, b = F(v), Fraction(m.group(2))
        return {"<": a < b, "<=": a <= b, ">": a > b, ">=": a >= b}[m.group(1)]
    m = re.fullmatch(rf"([\[\]\(])\s*({SO})\s*\.\.\s*({SO})\s*([\[\]\)])", o)
    if m:
        a, lo, hi = F(v), Fraction(m.group(2)), Fraction(m.group(3))
        trai = a >= lo if m.group(1) == "[" else a > lo
        phai = a <= hi if m.group(4) == "]" else a < hi
        return trai and phai
    if re.fullmatch(SO, o):
        return F(v) == Fraction(o)
    raise ValueError(f"Ô DMN chưa hỗ trợ: {o!r}")


# ----------------------------------------------------------------------------- Gói và tham số
@dataclass
class Goi:
    thu_muc: Path
    policy: dict
    bang: dict[str, Bang]

    @property
    def id(self) -> str:
        return self.policy["metadata"]["id"]

    @property
    def luong(self) -> str:
        return self.policy["metadata"]["luong"]

    def tham_so(self, ghi_de: dict | None) -> dict:
        ts = {k: v["mac_dinh"] for k, v in self.policy.get("params", {}).items() if isinstance(v, dict) and "mac_dinh" in v}
        for k, v in (ghi_de or {}).items():
            if k not in ts:
                raise KeyError(f"tham số {k} không có trong params của gói")
            ts[k] = v
        return ts

    def chu_so(self, pham_vi: str, ten: str, mac_dinh: int) -> int:
        dn = self.policy.get("definitions", {}).get(pham_vi, {}).get(ten, {})
        return (dn.get("rounding") or {}).get("digits", mac_dinh)


def nap_goi(thu_muc: Path) -> Goi:
    policy = yaml.safe_load((thu_muc / "policy.yaml").read_text(encoding="utf-8"))
    bang = {b["table_key"]: doc_dmn(thu_muc / b["dmn"]) for b in policy["classification"]["bang_quyet_dinh"]}
    return Goi(thu_muc, policy, bang)


# ----------------------------------------------------------------------------- Công thức tham chiếu
def tinh_nhiem_vu(nv: dict, ts: dict) -> dict:
    A = Fraction(1) if nv["ke_hoach"] == 0 else min(Fraction(nv["so_luong_duyet"], nv["ke_hoach"]), Fraction(1))
    if ts["cach_tinh_chat_luong"] == "TRU_25_MOI_LAN":
        B = max(Fraction(0), 1 - F(ts["tru_moi_lan_chinh_sua"]) * nv["so_lan_chinh_sua"])
    else:
        B = F(nv["muc_chat_luong"])
    ngay = nv["ngay_lich_cham_chu_quan"] if ts["loai_ngay"] == "NGAY_LICH" else nv["ngay_lam_viec_cham_chu_quan"]
    C = max(Fraction(0), 1 - F(ts["tru_tien_do"]) * ngay)
    return {"nhom": nv["nhom"], "diem_chuan": F(nv["diem_chuan"]), "kpi": (A + B + C) / 3,
            "vuot": nv["ke_hoach"] > 0 and nv["so_luong_duyet"] > nv["ke_hoach"]}


def tinh_ho_so(goi: Goi, dv: dict, ts: dict) -> dict:
    """Điểm, tỷ lệ vượt và các biến đầu vào của bảng xếp loại (phạm vi nhiem_vu, nhom, ho_so)."""
    kq = [tinh_nhiem_vu(nv, ts) for nv in dv["nhiem_vu"]]
    diem_kq = Fraction(0)
    for g in ts["nhom"]:
        ds = [k["kpi"] for k in kq if k["nhom"] in g["nhom_nhiem_vu"]]
        kpi_nhom = sum(ds, Fraction(0)) / len(ds) if ds else Fraction(0)
        diem_kq += kpi_nhom * g["diem_toi_da"]
    diem_tho = diem_kq
    if goi.luong == "NHA_NUOC":
        diem_tho += F(ts["diem_toi_da_tieu_chi_chung"]) * min(max(F(dv["ty_le_dap_ung_tieu_chi_chung"]), Fraction(0)), Fraction(1))
    if not kq:
        ty_le = Fraction(0)
    elif ts["htxs.cach_dem_vuot"] == "THEO_DIEM":
        tong = sum((k["diem_chuan"] for k in kq), Fraction(0))
        ty_le = Fraction(0) if tong == 0 else sum((k["diem_chuan"] for k in kq if k["vuot"]), Fraction(0)) / tong
    else:
        ty_le = Fraction(sum(1 for k in kq if k["vuot"]), len(kq))
    ty_le = lam_tron(ty_le, goi.chu_so("ho_so", "ty_le_vuot", 4))
    return {"diem": lam_tron(diem_tho, goi.policy["rounding"]["diem"]["digits"]), "ty_le_vuot": ty_le,
            "kpi_nhiem_vu": [lam_tron(k["kpi"], 4) for k in kq],
            "truot_then_chot_chu_quan": any(nv["nhom"] == "THEN_CHOT" and nv["khong_hoan_thanh_chu_quan"] for nv in dv["nhiem_vu"])}


def dat_nguong(ty_le: Fraction, ts: dict) -> bool:
    nguong = F(ts["htxs.nguong_ty_le_vuot"])
    return ty_le > nguong if ts["htxs.phep_so_sanh"] == "LON_HON" else ty_le >= nguong


def min_muc(thu_tu: list[str], *muc: str) -> str:
    return max(muc, key=thu_tu.index)


def dieu_kien_thieu(bang: Bang, dong_dat: Dong, gia_tri: dict) -> tuple[bool, list[str]]:
    """BR-09-10, PL 6 §12: đủ điểm mức xuất sắc nhưng không khớp dòng xuất sắc thì nêu điều kiện còn thiếu."""
    dong_htxs = next(d for d in bang.dong if d.ket_qua == "HTXSNV")
    o_diem = dong_htxs.o[0]
    if dong_dat is dong_htxs or dong_dat.ket_qua == "KHTNV" or not khop(o_diem, gia_tri[bang.dau_vao[0]]):
        return False, []
    return True, [ten for o, ten in zip(dong_htxs.o[1:], bang.dau_vao[1:]) if not khop(o, gia_tri[ten])]


def chay_quy(goi: Goi, dv: dict, ts: dict) -> dict:
    kq = tinh_ho_so(goi, dv, ts)
    thu_tu = goi.policy["classification"].get("thu_tu", THU_TU_MAC_DINH)
    if goi.luong == "NHA_NUOC":
        d = goi.bang["xep_loai"].tra({"diem": kq["diem"]})
        return kq | {"dong_dmn": d.id, "xep_loai": d.ket_qua}
    tcc = goi.bang["tieu_chi_chung"].tra({"ty_le_dap_ung_tieu_chi_chung": F(dv["ty_le_dap_ung_tieu_chi_chung"])})
    gia_tri = {"diem": kq["diem"], "muc_tieu_chi_chung": tcc.ket_qua, "loai_tru": dv["loai_tru"], "ty_le_O": F(dv["ty_le_O"]),
               "dat_ty_le_vuot": dat_nguong(kq["ty_le_vuot"], ts),
               "giai_phap_dot_pha_da_kiem_chung": dv["giai_phap_dot_pha_da_kiem_chung"],
               "truot_then_chot_chu_quan": kq["truot_then_chot_chu_quan"]}
    bang = goi.bang["xep_loai"]
    d = bang.tra(gia_tri)
    ha_muc, thieu = dieu_kien_thieu(bang, d, gia_tri)
    ndd = dv["la_nguoi_dung_dau"] and (not dv["tap_the_dat_05_chi_tieu"] or not dv["tap_the_dat_tat_ca_O"]
                                       or F(dv["ty_le_don_vi_khtnv"]) > F(ts["nguoi_dung_dau.nguong_ty_le_don_vi_khtnv"]))
    muc_tcc = QUY_DOI_TCC[tcc.ket_qua]
    if ndd:
        cuoi = "KHTNV"
    elif dv["giu_chuc_vu"] and dv["xep_loai_nha_nuoc"]:
        cuoi = min_muc(thu_tu, d.ket_qua, muc_tcc, dv["xep_loai_nha_nuoc"])
    else:
        cuoi = min_muc(thu_tu, d.ket_qua, muc_tcc)
    return kq | {"muc_tieu_chi_chung": tcc.ket_qua, "dong_tieu_chi_chung": tcc.id, "dat_ty_le_vuot": gia_tri["dat_ty_le_vuot"],
                 "dong_dmn": d.id, "xep_loai": d.ket_qua, "ha_muc": ha_muc, "dieu_kien_thieu": thieu,
                 "nguoi_dung_dau": ndd, "muc_cuoi": cuoi}


def chay_nam(goi: Goi, dv: dict, ts: dict) -> dict:
    quy = [q | {"co_xep_loai": q.get("co_xep_loai", True)} for q in dv["ket_qua_quy"]]
    co = [q for q in quy if q["co_xep_loai"]]
    if ts["nam.cach_tinh"] == "TOAN_BO_NHIEM_VU_NAM":
        tho = F(dv["diem_toan_bo_nhiem_vu_nam"])
    elif not co:
        tho = Fraction(0)
    elif ts["nam.cach_tinh"] == "TRUNG_BINH_CO_TRONG_SO":
        w = [F(x) for x in ts["nam.trong_so_quy"]]
        tho = sum((F(q["diem"]) * w[q["quy"] - 1] for q in co), Fraction(0)) / sum((w[q["quy"] - 1] for q in co), Fraction(0))
    else:
        tho = sum((F(q["diem"]) for q in co), Fraction(0)) / len(co)
    nam_def = goi.policy.get("definitions_nam", {}).get("nam", {}).get("diem_nam", {})
    diem_nam = lam_tron(tho, (nam_def.get("rounding") or {}).get("digits", 1))
    khtnv = {q["quy"] for q in quy if q["xep_loai"] == "KHTNV"}
    tcc = goi.bang["tieu_chi_chung"].tra({"ty_le_dap_ung_tieu_chi_chung": F(dv["ty_le_dap_ung_tieu_chi_chung"])})
    gia_tri = {"diem_nam": diem_nam, "muc_tieu_chi_chung": tcc.ket_qua, "loai_tru": dv["loai_tru"], "so_quy_khtnv": len(khtnv),
               "khong_xet_htxsnv_nam": dv["khong_xet_htxsnv_nam"], "ty_le_O_nam": F(dv["ty_le_O_nam"]),
               "dat_ty_le_vuot_nam": dat_nguong(F(dv["ty_le_vuot_nam"]), ts),
               "giai_phap_dot_pha_da_kiem_chung": dv["giai_phap_dot_pha_da_kiem_chung"],
               "truot_then_chot_chu_quan_nam": dv["truot_then_chot_chu_quan_nam"]}
    d = goi.bang["xep_loai_nam"].tra(gia_tri)
    return {"diem_nam": diem_nam, "so_quy_khtnv": len(khtnv), "muc_tieu_chi_chung": tcc.ket_qua, "dong_tieu_chi_chung": tcc.id,
            "dong_dmn": d.id, "xep_loai_nam": d.ket_qua, "co_bo_tri_lai": any(i in khtnv and i + 1 in khtnv for i in (1, 2, 3))}


def chay_dac_thu(goi: Goi, dv: dict) -> dict:
    th = next((t for t in goi.policy.get("truong_hop_dac_thu", []) if t["ma"] == dv["truong_hop"]), None)
    if th is None:
        raise KeyError(f"không có trường hợp đặc thù {dv['truong_hop']}")
    return {"muc": th["muc"], "hieu_ung": th["hieu_ung"], "hieu_ung_khi_khong_du": th.get("hieu_ung_khi_khong_du"),
            "truong_hop": th["ma"]}


# ----------------------------------------------------------------------------- Điểm công việc (DL-16)
NHOM_VIEC = ("THEN_CHOT", "THUONG_XUYEN")
TRANG_THAI_VIEC = ("HOAN_THANH", "CHUA_HOAN_THANH", "KHONG_DUOC_CHAP_NHAN", "CHUYEN_KY")


def lam_tron_dau(x: Fraction, chu_so: int) -> Fraction:
    """Làm tròn nửa lên theo trị tuyệt đối, dùng cho số có thể âm (điểm quản lý sau phần giảm)."""
    return -lam_tron(-x, chu_so) if x < 0 else lam_tron(x, chu_so)


def r4(x: Fraction | None) -> Fraction | None:
    return None if x is None else lam_tron_dau(x, 4)


def tinh_viec(v: dict, ts: dict) -> dict:
    """Điểm công việc = điểm chuẩn × hệ số mức độ khó × hệ số nhóm × các hệ số bổ sung đang bật; ba mặt
    số lượng, chất lượng, tiến độ theo trạng thái khi khóa sổ."""
    if v["trang_thai"] not in TRANG_THAI_VIEC:
        raise ValueError(f"trạng thái công việc {v['trang_thai']} không hỗ trợ")
    hs = F(ts["he_so.muc_do_kho"][v.get("muc_do_kho", 2)]) * F(ts["he_so.nhom_nhiem_vu"][v["nhom"]])
    for b in ts["he_so.bo_sung"]:
        if b["bat"]:
            hs *= F((v.get("he_so_bo_sung") or {}).get(b["ma"], 1))
    d = F(v["diem_chuan"]) * hs
    zero = Fraction(0)
    if v.get("ngoai_pham_vi") or v["trang_thai"] == "CHUYEN_KY":
        giao = sl = cl = td = zero
    elif v["trang_thai"] != "HOAN_THANH" or not v.get("cha_hoan_thanh", True):
        giao, sl, cl, td = d, zero, zero, zero
    else:
        m = F(v["muc_chat_luong"])
        if m not in [F(x) for x in ts["chat_luong.thang_cong_viec"]]:
            raise ValueError(f"mức chất lượng {v['muc_chat_luong']} ngoài thang")
        giao = sl = d
        cl = d * m
        td = max(zero, d * (1 - F(ts["tru_tien_do"]) * v.get("ngay_cham_chu_quan", 0)))
    return {"diem_cong_viec": d, "giao": giao, "so_luong": sl, "chat_luong": cl, "tien_do": td, "diem_dat": (sl + cl + td) / 3}


def chay_viec(dv: dict, ts: dict) -> dict:
    return {k: r4(v) for k, v in tinh_viec(dv, ts).items()}


def chay_quan_ly(dv: dict, ts: dict) -> dict:
    """Điểm quản lý của một KPI, nhiệm vụ, KR trên tổng ba mặt của các công việc bên dưới, sau phần giảm
    theo tỷ lệ đạt chỉ tiêu r, mức chất lượng của mục m và số ngày chậm chủ quan của mục n."""
    S, Q, D, G = (F(dv[k]) for k in ("tong_so_luong", "tong_chat_luong", "tong_tien_do", "tong_giao"))
    r, m, n = F(dv.get("ty_le_dat_chi_tieu", 1)), F(dv.get("muc_chat_luong", 1)), dv.get("ngay_cham_chu_quan", 0)
    if dv["loai"] not in ts["quan_ly.loai_ap_giam"]:
        r, m, n = Fraction(1), Fraction(1), 0
    if dv.get("can_khac_phuc"):
        m = min(m, F(ts["chat_luong.tran_khac_phuc"]))
    sl, cl, td = S * r, Q - (1 - m) * S, D - F(ts["tru_tien_do"]) * n * S
    return {k: r4(v) for k, v in {"giao": G, "so_luong": sl, "chat_luong": cl, "tien_do": td,
                                   "muc_chat_luong_ap_dung": m, "diem_dat": (sl + cl + td) / 3}.items()}


def goi_diem_cho(doi_tuong: str, ts: dict) -> dict:
    rieng = (ts.get("goi_diem.theo_doi_tuong") or {}).get(doi_tuong)
    goi = {g["ma"]: F(g["diem_toi_da"]) for g in ts["nhom"]} if rieng is None else {k: F(v) for k, v in rieng.items()}
    if sum(goi.values()) != 100:
        raise ValueError("Gói trọng tâm, then chốt và gói thường xuyên phải cộng lại bằng 100.")
    return goi


def chay_ca_nhan(dv: dict, ts: dict) -> dict:
    """Điểm quý của một người: cộng điểm được giao và ba mặt của các mục trong phạm vi phụ trách theo
    từng nhóm, rồi đưa về thang 100 theo tham số thang_100.che_do; tổng điểm lưu 2 chữ số, điểm dùng
    để xếp loại làm tròn tiếp 1 chữ số."""
    muc = [m for m in dv["muc"] if not m.get("truc_tiep") or dv.get("cap") in ts["truc_tiep_lam.cap_ap_dung"]]
    zero = Fraction(0)
    nhom = {}
    for g in NHOM_VIEC:
        L = [m for m in muc if m["nhom"] == g]
        tong = {k: sum((F(m[k]) for m in L), zero) for k in ("giao", "so_luong", "chat_luong", "tien_do")}
        tong["dat"] = (tong["so_luong"] + tong["chat_luong"] + tong["tien_do"]) / 3
        tong["kpi"] = tong["dat"] / tong["giao"] if tong["giao"] else None
        nhom[g] = tong
    tong_giao = sum(n["giao"] for n in nhom.values())
    kq = {f"{p}_{k}": r4(nhom[g][k]) for g, p in (("THEN_CHOT", "tt"), ("THUONG_XUYEN", "tx"))
          for k in ("giao", "so_luong", "chat_luong", "tien_do", "dat", "kpi")}
    if tong_giao == 0:
        return kq | {"co_diem": False, "tong_diem": None, "diem_xep_loai": None}
    if ts["thang_100.che_do"] == "TY_LE_CO_HE_SO":
        tho = 100 * sum(n["dat"] for n in nhom.values()) / tong_giao
        goi = None
    else:
        goi = goi_diem_cho(dv["doi_tuong"], ts)
        co = [g for g in NHOM_VIEC if nhom[g]["giao"]]
        if len(co) == 1:
            goi = {g: Fraction(100 if g == co[0] else 0) for g in NHOM_VIEC}
        truc = ts.get("ty_trong_truc") or {}
        if truc and nhom["THEN_CHOT"]["giao"]:
            w_tong, kpi_tong = zero, zero
            for t in sorted({m["truc"] for m in muc if m["nhom"] == "THEN_CHOT"}):
                L = [m for m in muc if m["nhom"] == "THEN_CHOT" and m["truc"] == t]
                giao = sum((F(m["giao"]) for m in L), zero)
                if not giao:
                    continue
                w = F(truc.get(t, 0))
                if not w:
                    raise ValueError(f"Còn trục {t} có việc trọng tâm trong kỳ nhưng chưa có tỷ trọng theo trục.")
                dat = sum((F(m["so_luong"]) + F(m["chat_luong"]) + F(m["tien_do"]) for m in L), zero) / 3
                w_tong, kpi_tong = w_tong + w, kpi_tong + w * dat / giao
            nhom["THEN_CHOT"]["kpi"] = kpi_tong / w_tong
            kq["tt_kpi"] = r4(nhom["THEN_CHOT"]["kpi"])
        tho = sum((nhom[g]["kpi"] or zero) * goi[g] for g in NHOM_VIEC)
    tong_diem = lam_tron(tho, 2)
    return kq | {"co_diem": True, "tong_diem": tong_diem, "diem_xep_loai": lam_tron(tong_diem, 1),
                 "diem_toi_da_tt": goi and goi["THEN_CHOT"], "diem_toi_da_tx": goi and goi["THUONG_XUYEN"]}


def chay_nam_diem(dv: dict, ts: dict) -> dict:
    """Điểm năm: (a) chỉ các quý có trên phần mềm; (b) cộng thêm điểm được giao và điểm đạt đã quy đổi
    (giao × tỷ lệ đạt) của các quý trước."""
    giao = sum((F(q["giao"]) for q in dv["quy_tren_phan_mem"]), Fraction(0))
    dat = sum((F(q["dat"]) for q in dv["quy_tren_phan_mem"]), Fraction(0))
    if ts["nam.cach_dung_quy_truoc"] == "CONG_QUY_TRUOC":
        giao += sum((F(q["giao"]) for q in dv.get("quy_truoc", [])), Fraction(0))
        dat += sum((F(q["giao"]) * F(q["ty_le_dat"]) for q in dv.get("quy_truoc", [])), Fraction(0))
    if not giao:
        return {"co_diem": False, "tong_diem_nam": None, "diem_xep_loai": None}
    tong = lam_tron(100 * dat / giao, 2)
    return {"co_diem": True, "tong_diem_nam": tong, "diem_xep_loai": lam_tron(tong, 1)}


def ly_do_thieu(bang: Bang, gia_tri: dict, muc_xet: str) -> list[str]:
    """Các điều kiện chưa đạt so với dòng giữ mức muc_xet khớp gần nhất (ít ô sai nhất, dòng trước khi hoà)."""
    tot = None
    for d in bang.dong:
        if d.ket_qua != muc_xet or not khop(d.o[0], gia_tri[bang.dau_vao[0]]):
            continue
        sai = [ten for o, ten in zip(d.o[1:], bang.dau_vao[1:]) if not khop(o, gia_tri[ten])]
        if tot is None or len(sai) < len(tot):
            tot = sai
    return tot or []


def chay_chuoi(goi: Goi, dv: dict, ts: dict) -> dict:
    """Chuỗi bước xếp loại khai ở classification.chuoi: mỗi bước tra một bảng DMN trên ngữ cảnh gồm dữ
    liệu của ca, kết quả các bước trước và tham số được ánh xạ; dat_ty_le_vuot so ngưỡng trên tỷ lệ chưa làm tròn."""
    ctx = dict(dv)
    ctx["dat_ty_le_vuot"] = dat_nguong(F(dv["ty_le_vuot"]), ts)
    kq, dong = {}, []
    for buoc in goi.policy["classification"]["chuoi"]:
        for ten, khoa in (buoc.get("tham_so") or {}).items():
            ctx[ten] = ts[khoa]
        bang = goi.bang[buoc["table_key"]]
        gia_tri = {t: ctx[t] for t in bang.dau_vao}
        d = bang.tra(gia_tri)
        ctx[buoc["ket_qua"]] = kq[buoc["ket_qua"]] = d.ket_qua
        dong.append(d.id)
        lt = buoc.get("ly_do_thieu")
        if lt:
            thieu = ly_do_thieu(bang, gia_tri, lt["muc_xet"]) if gia_tri[bang.dau_vao[0]] == lt["muc_xet"] != d.ket_qua else []
            kq[lt["ket_qua"]] = thieu
    return kq | {"cac_dong": dong, "dat_ty_le_vuot": ctx["dat_ty_le_vuot"]}


def tinh_ca(goi: Goi, loai: str, dv: dict, ts: dict) -> dict:
    if loai in ("QUY", "NHA_NUOC"):
        return chay_quy(goi, dv, ts)
    if loai == "NAM":
        return chay_nam(goi, dv, ts)
    if loai == "DAC_THU":
        return chay_dac_thu(goi, dv)
    if loai == "VIEC":
        return chay_viec(dv, ts)
    if loai == "QUAN_LY":
        return chay_quan_ly(dv, ts)
    if loai == "CA_NHAN":
        return chay_ca_nhan(dv, ts)
    if loai == "NAM_DIEM":
        return chay_nam_diem(dv, ts)
    if loai == "XEP_LOAI_CHUOI":
        return chay_chuoi(goi, dv, ts)
    raise ValueError(f"loại ca không hỗ trợ: {loai}")


def chay_lo(goi: Goi, dv: dict, ghi_de: dict, ca_goc: dict, ket_qua_goc: dict, md: dict) -> dict:
    """Đếm mức của một lô ca (khoá dv['khoa'], mặc định muc_goi_y); tham số của ca lô ghi đè lên tham số
    từng ca, dv['thay_doi'][ma_ca] ghi đè dữ liệu của ca đó. ca_doi_muc: các ca đổi mức so với lần chạy gốc."""
    khoa = dv.get("khoa", "muc_goi_y")
    thu_tu = goi.policy["classification"].get("thu_tu", THU_TU_MAC_DINH)
    dem, doi = {m: 0 for m in thu_tu}, []
    for ma in dv["ca"]:
        ca = ca_goc[ma]
        dvc = dict(md.get(ca["loai"], {})) | dict(ca.get("dau_vao", {})) | dict((dv.get("thay_doi") or {}).get(ma, {}))
        thuc = tinh_ca(goi, ca["loai"], dvc, goi.tham_so((ca.get("tham_so") or {}) | (ghi_de or {})))
        dem[thuc[khoa]] += 1
        if thuc[khoa] != ket_qua_goc[ma].get(khoa):
            doi.append(ma)
    return {"dem_muc": dem, "ca_doi_muc": doi}


# ----------------------------------------------------------------------------- Chạy bộ ca
@dataclass
class KetQuaCa:
    id: str
    loai: str
    mo_ta: str
    legal_ref: str
    mong_doi: dict
    thuc_te: dict
    sai: list[str] = field(default_factory=list)
    tham_so: dict = field(default_factory=dict)
    dau_vao: dict = field(default_factory=dict)
    source: str = "BA"

    @property
    def dat(self) -> bool:
        return not self.sai


def so_khop(mong: object, thuc: object) -> bool:
    if isinstance(thuc, Fraction):
        return not isinstance(mong, bool) and isinstance(mong, (int, float)) and F(mong) == thuc
    if isinstance(thuc, list):
        return isinstance(mong, list) and len(mong) == len(thuc) and all(so_khop(a, b) for a, b in zip(mong, thuc))
    if isinstance(thuc, dict):
        return isinstance(mong, dict) and mong.keys() == thuc.keys() and all(so_khop(mong[k], thuc[k]) for k in thuc)
    return mong == thuc


@dataclass
class BoCa:
    goi: Goi
    tep: Path
    noi_dung: dict
    ket_qua: list[KetQuaCa]


def chay_bo(goi: Goi, tep: Path) -> BoCa:
    bo = yaml.safe_load(tep.read_text(encoding="utf-8"))
    md = bo.get("mac_dinh", {})
    ket_qua = []
    ids = {c["id"] for c in bo["ca"]}
    for ca in bo["ca"]:
        dv = dict(md.get(ca["loai"], {})) | dict(ca.get("dau_vao", {}))
        if "nhiem_vu" in dv:
            dv["nhiem_vu"] = [dict(md.get("nhiem_vu", {})) | nv for nv in dv["nhiem_vu"]]
        if ca["loai"] == "XEP_LOAI_LO":
            ket_qua.append(None)
            continue
        try:
            ts = goi.tham_so(ca.get("tham_so"))
            thuc = tinh_ca(goi, ca["loai"], dv, ts)
            sai = [f"{k}: mong đợi {hien(v)}, thực tế {hien(thuc.get(k))}" for k, v in ca["mong_doi"].items()
                   if not so_khop(v, thuc.get(k))]
        except (KeyError, ValueError, TypeError) as e:
            thuc, sai = {}, [f"lỗi khi chạy: {e}"]
        sai += [f"ca liên quan {x} không có trong bộ" for x in ca.get("ca_lien_quan", []) if x not in ids]
        ket_qua.append(KetQuaCa(ca["id"], ca["loai"], ca["mo_ta"], ca.get("legal_ref", ""), ca["mong_doi"], thuc, sai,
                                ca.get("tham_so") or {}, dv, ca.get("source", "BA")))
    ca_goc = {c["id"]: c for c in bo["ca"]}
    ket_qua_goc = {k.id: k.thuc_te for k in ket_qua if k is not None}
    for i, ca in enumerate(bo["ca"]):
        if ca["loai"] != "XEP_LOAI_LO":
            continue
        dv = dict(ca.get("dau_vao", {}))
        try:
            thieu = [x for x in dv["ca"] if x not in ca_goc or ca_goc[x]["loai"] == "XEP_LOAI_LO"]
            if thieu:
                raise KeyError(f"ca trong lô không có hoặc là ca lô: {thieu}")
            thuc = chay_lo(goi, dv, ca.get("tham_so"), ca_goc, ket_qua_goc, md)
            sai = [f"{k}: mong đợi {hien(v)}, thực tế {hien(thuc.get(k))}" for k, v in ca["mong_doi"].items()
                   if not so_khop(v, thuc.get(k))]
        except (KeyError, ValueError, TypeError) as e:
            thuc, sai = {}, [f"lỗi khi chạy: {e}"]
        ket_qua[i] = KetQuaCa(ca["id"], ca["loai"], ca["mo_ta"], ca.get("legal_ref", ""), ca["mong_doi"], thuc, sai,
                              ca.get("tham_so") or {}, dv, ca.get("source", "BA"))
    return BoCa(goi, tep, bo, ket_qua)


def ma_tran_phu(bo: BoCa) -> tuple[list[str], list[str]]:
    """Trả về (các dòng in ra, các thiếu sót là lỗi): mỗi dòng DMN và mỗi trường hợp đặc thù phải có ít nhất một ca."""
    in_ra, thieu = [], []
    for key, bang in bo.goi.bang.items():
        in_ra.append(f"  Bảng {bang.tep}:")
        for d in bang.dong:
            ca = [k.id for k in bo.ket_qua if d.id in (k.thuc_te.get("dong_dmn"), k.thuc_te.get("dong_tieu_chi_chung"))
                  or d.id in k.thuc_te.get("cac_dong", [])]
            in_ra.append(f"    {d.id:<28} {d.ket_qua:<15} {', '.join(ca) if ca else 'CHƯA CÓ CA'}")
            if not ca:
                thieu.append(f"{bo.goi.id}: dòng {d.id} của {bang.tep} chưa có ca nào chạm tới")
    th = bo.goi.policy.get("truong_hop_dac_thu", [])
    if th:
        in_ra.append("  Trường hợp đặc thù:")
        for t in th:
            ca = [k.id for k in bo.ket_qua if k.thuc_te.get("truong_hop") == t["ma"]]
            in_ra.append(f"    mục {t['muc']:>2} {t['ma']:<34} {', '.join(ca) if ca else 'CHƯA CÓ CA'}")
            if not ca:
                thieu.append(f"{bo.goi.id}: trường hợp đặc thù mục {t['muc']} chưa có ca")
    in_ra.append("  Tham số được ca đổi giá trị (tham số còn lại chỉ được thử ở giá trị mặc định):")
    for k in sorted({k for c in bo.ket_qua for k in c.tham_so}):
        in_ra.append(f"    {k:<28} {', '.join(c.id + '=' + hien(c.tham_so[k]) for c in bo.ket_qua if k in c.tham_so)}")
    return in_ra, thieu


COT_BANG = ["ma_ca", "loai", "mo_ta", "can_cu", "mong_doi", "ket_qua_tinh", "dat", "nguoi_ky", "ngay_ky", "y_kien"]


def bang_doi_chieu(bo: BoCa) -> str:
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(COT_BANG)
    for k in bo.ket_qua:
        mong = "; ".join(f"{a}={hien(b)}" for a, b in k.mong_doi.items())
        thuc = "; ".join(f"{a}={hien(k.thuc_te.get(a))}" for a in k.mong_doi)
        w.writerow([k.id, k.loai, k.mo_ta, k.legal_ref, mong, thuc, "DAT" if k.dat else "KHONG_DAT", "", "", ""])
    return buf.getvalue()


def tep_nap(bo: BoCa) -> str:
    """Thân yêu cầu createGoldenTestCase (GoldenTestCaseCreate) cho từng ca; policyPackId điền khi nạp."""
    ds = [{"policyPackId": None, "caseCode": k.id, "description": k.mo_ta,
           "input": {"loai": k.loai, "tham_so": k.tham_so, "dau_vao": k.dau_vao}, "expected": k.mong_doi,
           "legalRef": k.legal_ref, "source": k.source,
           "note": "Ca giả lập, chờ nghiệp vụ rà soát và ký duyệt." if bo.noi_dung.get("gia_lap") else ""}
          for k in bo.ket_qua]
    return json.dumps({"goi": bo.goi.id, "phien_ban": bo.goi.policy["metadata"]["version"], "ca": ds},
                      ensure_ascii=False, indent=1) + "\n"


def chay(goc: Path, ghi_bang: bool = False, thu_muc_golden: Path | None = None, in_ra=print) -> int:
    so_loi = 0
    for tep_policy in sorted(goc.glob("*/*/policy.yaml")):
        goi = nap_goi(tep_policy.parent)
        for tep in sorted((goi.thu_muc / "tests").glob("bo-ca-mau*.yaml")):
            bo = chay_bo(goi, tep)
            dat = sum(k.dat for k in bo.ket_qua)
            gia_lap = " (GIẢ LẬP, chưa ký)" if bo.noi_dung.get("gia_lap") else ""
            in_ra(f"== {goi.id} @ {goi.policy['metadata']['version']}: {tep.name}{gia_lap}")
            for k in bo.ket_qua:
                if not k.dat:
                    in_ra(f"  KHÔNG ĐẠT {k.id} ({k.mo_ta})")
                    for s in k.sai:
                        in_ra(f"    - {s}")
            dong, thieu = ma_tran_phu(bo)
            for d in dong:
                in_ra(d)
            for t in thieu:
                in_ra(f"  THIẾU PHỦ: {t}")
            ti_le = Fraction(dat, len(bo.ket_qua)) if bo.ket_qua else Fraction(0)
            yeu_cau = F(bo.noi_dung.get("yeu_cau_dat", 1.0))
            in_ra(f"  Đạt {dat}/{len(bo.ket_qua)} ca, yêu cầu {hien(yeu_cau)}")
            if ti_le < yeu_cau:
                so_loi += 1
            so_loi += len(thieu)
            if ghi_bang:
                dich = (thu_muc_golden or Path(__file__).resolve().parent) / goi.id / "bang-doi-chieu.csv"
                dich.parent.mkdir(parents=True, exist_ok=True)
                dich.write_text(bang_doi_chieu(bo), encoding="utf-8")
                (dich.parent / "nap-ca.json").write_text(tep_nap(bo), encoding="utf-8")
                in_ra(f"  Đã ghi {dich} và nap-ca.json")
    in_ra("KẾT QUẢ: " + ("ĐẠT" if so_loi == 0 else f"KHÔNG ĐẠT, {so_loi} lỗi"))
    return so_loi


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Chạy bộ ca mẫu của Policy Pack")
    p.add_argument("goc", type=Path, help="thư mục data/policy-pack")
    p.add_argument("--ghi-bang", action="store_true", help="ghi bảng đối chiếu vào data/golden/<gói>/")
    a = p.parse_args(argv)
    return 1 if chay(a.goc, a.ghi_bang) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
