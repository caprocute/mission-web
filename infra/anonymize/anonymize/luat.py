"""Đọc và kiểm bộ luật che data/anonymize/rules.yaml."""

from __future__ import annotations

import fnmatch
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import yaml

from .danh_muc import GOC_REPO, CotNhayCam

TEP_LUAT = GOC_REPO / "data" / "anonymize" / "rules.yaml"
LUAT_HOP_LE = {"giu", "xoa", "ho_ten", "ngay_sinh_nhom_tuoi", "so_dinh_danh", "so_the_dang", "dien_thoai",
               "thu_dien_tu", "ten_dang_nhap", "ten_tep", "van_ban_cung_do_dai", "chi_muc_mu"}


@dataclass
class LuatBang:
    ten: str
    cot: dict[str, dict]
    aad_dong: str = "id"
    aad_cot: str | None = None
    cot_dek: str | None = None

    def toa_do(self, cot: str) -> str:
        return self.aad_cot or f"{self.ten}.{cot}"


@dataclass
class BoLuat:
    ngay_moc: date
    nhom_tuoi_nam: int
    bang: dict[str, LuatBang]
    xoa_bang: list[str] = field(default_factory=list)

    def la_bang_xoa(self, ten: str) -> bool:
        return any(fnmatch.fnmatch(ten, m) for m in self.xoa_bang)

    def co_luat(self, ten_cot: str) -> bool:
        bang, cot = ten_cot.rsplit(".", 1)
        return (bang in self.bang and cot in self.bang[bang].cot) or self.la_bang_xoa(bang)


def doc_luat(tep: Path = TEP_LUAT, bo_cot: set[str] | None = None) -> BoLuat:
    """Đọc bộ luật; `bo_cot` (dạng schema.bảng.cột) cho phép kiểm thử cố ý bỏ luật một cột."""
    d = yaml.safe_load(tep.read_text(encoding="utf-8"))
    bang: dict[str, LuatBang] = {}
    for ten, b in d["bang"].items():
        cot = {c: dict(l) for c, l in b["cot"].items() if f"{ten}.{c}" not in (bo_cot or set())}
        for c, l in cot.items():
            if l.get("luat") not in LUAT_HOP_LE:
                raise ValueError(f"{ten}.{c}: luật '{l.get('luat')}' không hợp lệ")
            if c.endswith("_enc") and not b.get("cot_dek"):
                raise ValueError(f"{ten}.{c}: cột _enc cần khai cot_dek")
            if l["luat"] == "chi_muc_mu" and l.get("tu") not in b["cot"]:
                raise ValueError(f"{ten}.{c}: chi_muc_mu cần cột nguồn 'tu' có luật")
        bang[ten] = LuatBang(ten, cot, b.get("aad_dong", "id"), b.get("aad_cot"), b.get("cot_dek"))
    return BoLuat(date.fromisoformat(str(d["ngay_moc"])), int(d["nhom_tuoi_nam"]), bang, list(d.get("xoa_bang", [])))


def cot_thieu_luat(luat: BoLuat, danh_muc: dict[str, CotNhayCam]) -> list[str]:
    return sorted(k for k in danh_muc if not luat.co_luat(k))
