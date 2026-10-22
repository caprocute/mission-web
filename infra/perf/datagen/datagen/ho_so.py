"""Đọc hồ sơ phân phối data/seed/perf/<hồ-sơ>.yaml và nhân hệ số quy mô."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml

GOC_REPO = Path(__file__).resolve().parents[4]
THU_MUC_HO_SO = GOC_REPO / "data" / "seed" / "perf"
THU_MUC_DL07 = GOC_REPO / "data" / "seed" / "org"


@dataclass(frozen=True)
class HoSo:
    ten: str
    hat_giong: int
    he_so: float
    so_tenant: int
    nam: int
    cac_quy: tuple[int, ...]
    quy_hien_hanh: int
    tai_khoan: int
    don_vi: int
    ty_le_cap: tuple[float, ...]
    trong_so_nguoi_theo_cap: tuple[float, ...]
    ty_le_dang_vien: float
    ty_le_doi_phien_ban: float
    nhiem_vu: int
    ty_le_nhiem_vu_then_chot: float
    trang_thai_quy_cu: dict[str, float]
    trang_thai_quy_hien_hanh: dict[str, float]
    phien_ban_minh_chung: int
    so_phien_ban_moi_minh_chung: dict[int, float]
    kich_thuoc_trung_vi: int
    kich_thuoc_sigma: float
    kich_thuoc_tran: int
    ho_so_danh_gia: int
    tien_trinh_flowable: int
    goc: dict = field(repr=False, default_factory=dict)

    @property
    def tai_khoan_moi_tenant(self) -> int:
        return max(1, self.tai_khoan // self.so_tenant)

    @property
    def don_vi_moi_tenant(self) -> int:
        return max(5, self.don_vi // self.so_tenant)

    @property
    def nhiem_vu_moi_nguoi_moi_quy(self) -> float:
        return self.nhiem_vu / self.tai_khoan / len(self.cac_quy)

    @property
    def phien_ban_moi_minh_chung_tb(self) -> float:
        return sum(k * v for k, v in self.so_phien_ban_moi_minh_chung.items())

    @property
    def xac_suat_co_minh_chung(self) -> float:
        """Xác suất một nhiệm vụ có sản phẩm kèm minh chứng để đạt tổng phiên bản của hồ sơ."""
        return min(1.0, self.phien_ban_minh_chung / self.nhiem_vu / self.phien_ban_moi_minh_chung_tb)


def _phan_phoi(d: dict, ten: str) -> dict:
    tong = sum(d.values())
    if abs(tong - 1.0) > 1e-6:
        raise ValueError(f"Phân phối {ten} phải có tổng 1 (đang {tong})")
    return dict(d)


def doc_ho_so(ten_hoac_duong_dan: str, he_so: float = 1.0, hat_giong: int | None = None) -> HoSo:
    duong_dan = Path(ten_hoac_duong_dan)
    if not duong_dan.suffix:
        duong_dan = THU_MUC_HO_SO / f"{ten_hoac_duong_dan}.yaml"
    d = yaml.safe_load(duong_dan.read_text(encoding="utf-8"))
    if not 0 < he_so <= 1:
        raise ValueError("Hệ số quy mô phải trong (0, 1]")

    def nhan(x: int, toi_thieu: int = 1) -> int:
        return max(toi_thieu, round(x * he_so))

    ty_le_cap = tuple(float(x) for x in d["ty_le_cap"])
    if len(ty_le_cap) != 5:
        raise ValueError("Cây tổ chức phải có đúng 5 cấp")
    return HoSo(
        ten=str(d["ho_so"]),
        hat_giong=int(hat_giong if hat_giong is not None else d["hat_giong"]),
        he_so=he_so,
        so_tenant=int(d.get("tenant", 1)),
        nam=int(d["nam"]),
        cac_quy=tuple(int(q) for q in d["quy"]),
        quy_hien_hanh=int(d["quy_hien_hanh"]),
        tai_khoan=nhan(d["tai_khoan"], 20),
        don_vi=nhan(d["don_vi"], 5),
        ty_le_cap=ty_le_cap,
        trong_so_nguoi_theo_cap=tuple(float(x) for x in d["trong_so_nguoi_theo_cap"]),
        ty_le_dang_vien=float(d["ty_le_dang_vien"]),
        ty_le_doi_phien_ban=float(d["ty_le_doi_phien_ban"]),
        nhiem_vu=nhan(d["nhiem_vu"], 20),
        ty_le_nhiem_vu_then_chot=float(d["ty_le_nhiem_vu_then_chot"]),
        trang_thai_quy_cu=_phan_phoi(d["trang_thai_quy_cu"], "trang_thai_quy_cu"),
        trang_thai_quy_hien_hanh=_phan_phoi(d["trang_thai_quy_hien_hanh"], "trang_thai_quy_hien_hanh"),
        phien_ban_minh_chung=nhan(d["phien_ban_minh_chung"], 1),
        so_phien_ban_moi_minh_chung=_phan_phoi(
            {int(k): float(v) for k, v in d["so_phien_ban_moi_minh_chung"].items()},
            "so_phien_ban_moi_minh_chung",
        ),
        kich_thuoc_trung_vi=int(d["kich_thuoc_tep"]["trung_vi_byte"]),
        kich_thuoc_sigma=float(d["kich_thuoc_tep"]["sigma"]),
        kich_thuoc_tran=int(d["kich_thuoc_tep"]["tran_byte"]),
        ho_so_danh_gia=nhan(d["ho_so_danh_gia"], 1),
        tien_trinh_flowable=nhan(d["tien_trinh_flowable"], 1),
        goc=d,
    )
