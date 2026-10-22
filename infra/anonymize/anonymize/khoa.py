"""Bọc và mở bọc DEK: OpenBao Transit (giống OpenBaoTransitClient của NEN-015) hoặc KEK cục bộ cho kiểm thử.

Nguồn dùng KEK của môi trường nguồn (chỉ mở bọc), đích dùng KEK của staging (chỉ bọc). DEK rõ chỉ
tồn tại trong bộ nhớ tiến trình, không ghi ra đĩa hay nhật ký.
"""

from __future__ import annotations

import base64
import json
import os
import ssl
import urllib.request
import uuid
from typing import Protocol

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

DO_DAI_DEK = 32


class BoKhoa(Protocol):
    def boc(self, tenant: uuid.UUID, dek: bytes) -> tuple[bytes, str]:
        """Trả (wrapped_dek, kek_ref) để ghi vào keyring.dek."""

    def mo_boc(self, tenant: uuid.UUID, wrapped: bytes) -> bytes: ...


class KhoaCucBo:
    """KEK cục bộ AES-256-GCM, chỉ dùng cho kiểm thử và môi trường thử cục bộ; bản bọc có dạng
    `local:v1:<base64(nonce||ct)>` để không lẫn với `vault:v1:` của OpenBao."""

    TIEN_TO = b"local:v1:"

    def __init__(self, kek: bytes, ten: str = "local") -> None:
        if len(kek) != 32:
            raise ValueError("KEK cục bộ phải 32 byte")
        self._aes = AESGCM(kek)
        self.ten = ten

    def boc(self, tenant: uuid.UUID, dek: bytes) -> tuple[bytes, str]:
        nonce = os.urandom(12)
        ct = self._aes.encrypt(nonce, dek, str(tenant).encode())
        return self.TIEN_TO + base64.b64encode(nonce + ct), f"{self.ten}/keys/kek-{tenant}"

    def mo_boc(self, tenant: uuid.UUID, wrapped: bytes) -> bytes:
        if not wrapped.startswith(self.TIEN_TO):
            raise ValueError("Bản bọc không phải của KEK cục bộ")
        raw = base64.b64decode(wrapped[len(self.TIEN_TO):])
        return self._aes.decrypt(raw[:12], raw[12:], str(tenant).encode())


class OpenBaoTransit:
    """Gọi /v1/<mount>/encrypt|decrypt/kek-<tenant> như OpenBaoTransitClient (Java). Token đọc từ
    tệp (CSI/ESO gắn) hoặc biến môi trường, không bao giờ in ra."""

    def __init__(self, dia_chi: str, mount: str, token: str, ca_file: str | None = None) -> None:
        self.dia_chi = dia_chi.rstrip("/")
        self.mount = mount.strip("/")
        self._token = token
        self._ssl = ssl.create_default_context(cafile=ca_file) if dia_chi.startswith("https") else None

    @staticmethod
    def tu_moi_truong(tien_to: str) -> "OpenBaoTransit":
        """Đọc <TIỀN_TỐ>_ADDR, <TIỀN_TỐ>_MOUNT, <TIỀN_TỐ>_TOKEN_FILE hoặc <TIỀN_TỐ>_TOKEN, <TIỀN_TỐ>_CACERT."""
        env = os.environ
        tep = env.get(f"{tien_to}_TOKEN_FILE")
        token = open(tep, encoding="utf-8").read().strip() if tep else env[f"{tien_to}_TOKEN"]
        return OpenBaoTransit(env[f"{tien_to}_ADDR"], env.get(f"{tien_to}_MOUNT", "transit"), token,
                              env.get(f"{tien_to}_CACERT"))

    def _post(self, duong_dan: str, than: dict) -> dict:
        req = urllib.request.Request(
            f"{self.dia_chi}/v1/{self.mount}/{duong_dan}", data=json.dumps(than).encode(), method="POST",
            headers={"Content-Type": "application/json", "X-Vault-Request": "true", "X-Vault-Token": self._token})
        with urllib.request.urlopen(req, context=self._ssl, timeout=30) as r:  # noqa: S310 (địa chỉ do cấu hình)
            noi_dung = r.read()
        return json.loads(noi_dung) if noi_dung else {}

    def dam_bao_kek(self, tenant: uuid.UUID) -> None:
        self._post(f"keys/kek-{tenant}", {"type": "aes256-gcm96"})

    def boc(self, tenant: uuid.UUID, dek: bytes) -> tuple[bytes, str]:
        d = self._post(f"encrypt/kek-{tenant}", {"plaintext": base64.b64encode(dek).decode()})
        return d["data"]["ciphertext"].encode(), f"{self.mount}/keys/kek-{tenant}"

    def mo_boc(self, tenant: uuid.UUID, wrapped: bytes) -> bytes:
        d = self._post(f"decrypt/kek-{tenant}", {"ciphertext": wrapped.decode()})
        dek = base64.b64decode(d["data"]["plaintext"])
        if len(dek) != DO_DAI_DEK:
            raise ValueError("DEK mở bọc không đủ 32 byte")
        return dek
