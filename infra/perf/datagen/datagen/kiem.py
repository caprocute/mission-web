"""Kiểm sau sinh, sau nạp: phân phối so với hồ sơ, mẫu dữ liệu cá nhân thật, toàn vẹn cây tổ chức."""

from __future__ import annotations

import re
from pathlib import Path

import yaml

from .bang import COT, COT_VAN_BAN
from .ho_so import THU_MUC_HO_SO, HoSo


def lech(thuc: float, ky_vong: float) -> float:
    return abs(thuc - ky_vong) / ky_vong if ky_vong else (0.0 if thuc == 0 else 1.0)


def so_sanh_phan_phoi(hs: HoSo, thong_ke: dict) -> dict[str, tuple[float, float, float]]:
    """Trả {chỉ số: (thực tế, kỳ vọng, độ lệch tương đối)} cho các đại lượng của hồ sơ."""
    tong: dict[str, float] = {}
    tt_cu: dict[str, int] = {}
    tt_moi: dict[str, int] = {}
    so_pb: dict[str, int] = {}
    for t in thong_ke["tenant"].values():
        for k, v in t["dem"].items():
            tong[k] = tong.get(k, 0) + v
        for k, v in t["trang_thai_quy_cu"].items():
            tt_cu[k] = tt_cu.get(k, 0) + v
        for k, v in t["trang_thai_quy_hien_hanh"].items():
            tt_moi[k] = tt_moi.get(k, 0) + v
        for k, v in t["so_phien_ban_moi_minh_chung"].items():
            so_pb[k] = so_pb.get(k, 0) + v
    so_don_vi = sum(v for k, v in tong.items() if k.startswith("don_vi_cap_"))
    kq = {
        "tai_khoan": (tong.get("org.person", 0), hs.tai_khoan),
        "don_vi": (so_don_vi, max(5, hs.don_vi_moi_tenant) * hs.so_tenant),
        "nhiem_vu": (tong.get("task.task", 0), hs.nhiem_vu),
        "phien_ban_minh_chung": (tong.get("product.evidence_version", 0), hs.phien_ban_minh_chung),
    }
    for ten, dem, mau in (("quy_cu", tt_cu, hs.trang_thai_quy_cu),
                          ("quy_hien_hanh", tt_moi, hs.trang_thai_quy_hien_hanh)):
        n = sum(dem.values())
        for tt, p in mau.items():
            # So sánh tỷ lệ theo điểm phần trăm quy về độ lệch tương đối trên toàn phân phối.
            kq[f"ty_le_{ten}_{tt}"] = (dem.get(tt, 0) / n if n else 0, p)
    return {k: (thuc, kv, lech(thuc, kv)) for k, (thuc, kv) in kq.items()}


# Mã UUID (khoá đối tượng, đường dẫn tệp) không phải dữ liệu cá nhân: gỡ trước khi so mẫu số.
UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")


def doc_mau_kiem() -> list[tuple[str, re.Pattern]]:
    d = yaml.safe_load((THU_MUC_HO_SO / "mau-kiem.yaml").read_text(encoding="utf-8"))
    return [(m["ten"], re.compile(m["regex"])) for m in d["mau"]]


def quet_du_lieu_ca_nhan(goc: Path) -> list[str]:
    """Quét các cột văn bản tự do của tệp sinh ra theo danh sách mẫu kiểm."""
    mau = doc_mau_kiem()
    vi_pham = []
    for bang, cot_quet in COT_VAN_BAN.items():
        chi_so = [COT[bang].index(c) for c in cot_quet]
        for tep in goc.glob(f"tenant-*/{bang}/*.tsv"):
            with tep.open(encoding="utf-8") as f:
                for so_dong, dong in enumerate(f, start=1):
                    truong = dong.rstrip("\n").split("\t")
                    for i in chi_so:
                        gia_tri = UUID.sub("-", truong[i])
                        for ten, rx in mau:
                            if rx.search(gia_tri):
                                vi_pham.append(f"{tep}:{so_dong} cột {COT[bang][i]} khớp mẫu {ten}")
    return vi_pham
