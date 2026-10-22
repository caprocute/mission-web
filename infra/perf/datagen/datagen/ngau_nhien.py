"""Nguồn ngẫu nhiên tất định: cùng hạt giống cho cùng dữ liệu, độc lập giữa các luồng sinh."""

from __future__ import annotations

import hashlib
import random
import uuid


class NguonNgauNhien(random.Random):
    """random.Random có hạt giống suy từ (hạt giống gốc, tên luồng) để mỗi bảng sinh độc lập."""

    def __init__(self, hat_giong: int, *luong: object) -> None:
        khoa = ":".join([str(hat_giong), *map(str, luong)]).encode()
        super().__init__(int.from_bytes(hashlib.sha256(khoa).digest()[:8], "big"))

    def ma(self) -> uuid.UUID:
        """UUID dạng v4 lấy từ luồng ngẫu nhiên tất định (không dùng uuid4() của hệ điều hành)."""
        return uuid.UUID(int=self.getrandbits(128), version=4)

    def byte(self, n: int) -> bytes:
        return self.getrandbits(8 * n).to_bytes(n, "big")

    def chon_theo_ty_le(self, phan_phoi: dict) -> object:
        return self.choices(list(phan_phoi), weights=list(phan_phoi.values()))[0]


# Phong bì mã hoá trường của vt-platform-starter (FieldEncryptor): 1 byte phiên bản, 16 byte mã
# DEK, 12 byte nonce, bản mã cùng độ dài bản rõ, 16 byte tag GCM.
PHONG_BI_PHU = 1 + 16 + 12 + 16


def ban_ma_gia(rng: NguonNgauNhien, dek: uuid.UUID, do_dai_ban_ro: int) -> bytes:
    """Bản mã giả cùng định dạng và độ dài với phong bì thật (giao diện mã hoá NEN-015 viết bằng
    Java nên bộ sinh không gọi được; khoá thử của môi trường perf không bao giờ giải được giá trị
    này, đúng tinh thần SEC-52)."""
    return b"\x01" + dek.bytes + rng.byte(12) + rng.byte(do_dai_ban_ro) + rng.byte(16)


class BoChiaTheoTyLe:
    """Chọn giá trị theo phân phối bằng hạn ngạch (luôn chọn giá trị đang thiếu nhiều nhất), nên tỷ
    lệ thực tế lệch phân phối không quá một phần tử ở mọi cỡ mẫu, kể cả trạng thái hiếm (HUY 1%)."""

    def __init__(self, phan_phoi: dict) -> None:
        self.phan_phoi = dict(phan_phoi)
        self.dem = dict.fromkeys(phan_phoi, 0)
        self.n = 0

    def chon(self) -> object:
        self.n += 1
        gt = max(self.phan_phoi, key=lambda k: self.phan_phoi[k] * self.n - self.dem[k])
        self.dem[gt] += 1
        return gt
