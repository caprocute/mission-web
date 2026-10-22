"""Một lần chạy đầy đủ: kiểm danh mục, che, quét, biên bản, huỷ khoá dùng một lần."""

from __future__ import annotations

import hashlib
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path

import psycopg

from .che import BoChe
from .danh_muc import quet_migration
from .khoa import BoKhoa
from .luat import TEP_LUAT, BoLuat, cot_thieu_luat, doc_luat
from .quet import quet


class ThieuLuat(RuntimeError):
    pass


def chay(kn: psycopg.Connection, nguon: BoKhoa, dich: BoKhoa, luat: BoLuat | None = None,
         tep_luat: Path = TEP_LUAT, cho_phep_thieu_luat: bool = False, tep_bien_ban: Path | None = None) -> dict:
    """Trả biên bản (dict, không chứa giá trị dữ liệu). `ket_luan` = DAT khi quét sạch, CHAN khi
    còn trúng: khi đó bản này không được chuyển sang staging."""
    bat_dau = datetime.now(timezone.utc)
    danh_muc = quet_migration()
    luat = luat or doc_luat(tep_luat)
    thieu = cot_thieu_luat(luat, danh_muc)
    if thieu and not cho_phep_thieu_luat:
        raise ThieuLuat(f"Cột chưa có luật che: {', '.join(thieu)}")
    che = BoChe(kn, luat, nguon, dich, danh_muc)
    try:
        che.chay()
        trung = quet(che)
        mau = che.mau.dem()
    finally:
        che.anh_xa.huy()
        che.mau.xoa()
        che.khoa.clear()
    bb = {
        "ma_lan_chay": str(uuid.uuid4()),
        "bat_dau": bat_dau.isoformat(), "ket_thuc": datetime.now(timezone.utc).isoformat(),
        "luat": {"tep": str(tep_luat.name), "sha256": hashlib.sha256(tep_luat.read_bytes()).hexdigest()},
        "so_cot_danh_muc": len(danh_muc), "cot_thieu_luat": thieu, "cot_ngam_dinh_giu": che.cot_ngam_dinh,
        "bang_khong_co_trong_csdl": che.bang_vang, "bang_loai_bo": che.bang_xoa, "bang_che": che.thong_ke,
        "so_mau_nguon": mau, "quet": trung, "khoa_anh_xa_da_huy": che.anh_xa.da_huy,
        "ket_luan": "CHAN" if trung or thieu else "DAT",
    }
    if tep_bien_ban:
        tep_bien_ban.write_text(json.dumps(bb, ensure_ascii=False, indent=2), encoding="utf-8")
    return bb
