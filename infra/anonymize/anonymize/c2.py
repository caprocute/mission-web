"""Mã hoá phong bì C2 và chỉ mục mù, khớp từng byte với NEN-015 (Java FieldEncryptor, BlindIndexer).

Phong bì: 0x01 || dek_id(16) || nonce(12) || bản mã || tag(16), AES-256-GCM,
AAD = "<tenant>|<schema.bảng.cột>|<rowId>" (UTF-8). Chỉ mục mù: khoá cột
HMAC-SHA256(rootKey, "bidx|<tenant>|<cột viết thường>"), rồi HMAC(khoá cột, chuẩn hoá(giá trị))
cắt 16 byte. Được kiểm bằng c2-test-vectors.json dùng chung với Java và Go.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import re
import unicodedata
import uuid

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PHIEN_BAN = 0x01
DO_DAI_NONCE = 12
DO_DAI_TAG = 16
DO_DAI_BIDX = 16
_KHOANG_TRANG = re.compile(r"\s+")


def aad(tenant: str, cot: str, row_id: str) -> bytes:
    return f"{tenant}|{cot}|{row_id}".encode("utf-8")


def ma_hoa(khoa: bytes, dek_id: uuid.UUID, ban_ro: bytes, aad_: bytes, nonce: bytes | None = None) -> bytes:
    nonce = nonce if nonce is not None else os.urandom(DO_DAI_NONCE)
    return bytes([PHIEN_BAN]) + dek_id.bytes + nonce + AESGCM(khoa).encrypt(nonce, ban_ro, aad_)


def dek_cua(phong_bi: bytes) -> uuid.UUID:
    if len(phong_bi) < 1 + 16 + DO_DAI_NONCE + DO_DAI_TAG or phong_bi[0] != PHIEN_BAN:
        raise ValueError("Phong bì C2 không hợp lệ")
    return uuid.UUID(bytes=phong_bi[1:17])


def giai_ma(khoa: bytes, phong_bi: bytes, aad_: bytes) -> bytes:
    dek_cua(phong_bi)
    nonce = phong_bi[17 : 17 + DO_DAI_NONCE]
    return AESGCM(khoa).decrypt(nonce, phong_bi[17 + DO_DAI_NONCE :], aad_)


def chuan_hoa(gia_tri: str) -> str:
    return _KHOANG_TRANG.sub(" ", unicodedata.normalize("NFC", gia_tri).strip()).lower()


def chi_muc_mu(khoa_goc: bytes, tenant: str, cot: str, gia_tri: str) -> bytes:
    khoa_cot = hmac.new(khoa_goc, f"bidx|{tenant}|{cot.lower()}".encode("utf-8"), hashlib.sha256).digest()
    return hmac.new(khoa_cot, chuan_hoa(gia_tri).encode("utf-8"), hashlib.sha256).digest()[:DO_DAI_BIDX]
