"""Sinh giá trị giả và ánh xạ nhất quán trong một lần chạy.

Mỗi giá trị nguồn được ánh xạ qua HMAC-SHA256 với khoá dùng một lần (sinh khi bắt đầu chạy, huỷ khi
xong), nên cùng một giá trị thật cho cùng một giá trị giả ở mọi bảng, còn hai lần chạy khác nhau cho
kết quả khác nhau và không suy ngược được. Họ tên, số định danh, ngày sinh lấy từ bộ sinh của DL-08
(`infra/perf/datagen/datagen/ten_gia.py`) để giữ cùng định dạng và phân phối với dữ liệu thử tải.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import random
import sys
import unicodedata
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "infra" / "perf" / "datagen"))

from datagen import ten_gia  # noqa: E402

# Từ dùng ghép văn bản tổng hợp (nhận xét, giá trị đối soát): câu nghiệp vụ trung tính, không tên người.
TU_VAN_BAN = ("hoàn thành", "nhiệm vụ", "đúng tiến độ", "chất lượng", "đạt yêu cầu", "cần bổ sung",
              "minh chứng", "kết quả", "theo kế hoạch", "đơn vị", "báo cáo", "đánh giá", "thực hiện",
              "chỉ tiêu", "trong kỳ", "đề xuất", "ý kiến", "tổng hợp")
TU_VAN_BAN = tuple(unicodedata.normalize("NFC", t) for t in TU_VAN_BAN)  # đếm ký tự đúng như dữ liệu NFC
DUOI_THU = "mau.invalid"  # RFC 2606: miền .invalid không bao giờ phân giải được
DAU_SO_DIEN_THOAI = "0100"  # đầu số chưa cấp cho nhà mạng nào (suy luận theo quy hoạch kho số)


class BoAnhXa:
    def __init__(self, khoa: bytes | None = None) -> None:
        self._khoa = bytearray(khoa if khoa is not None else os.urandom(32))
        self._da_anh_xa: dict[tuple[str, str], str] = {}
        self._da_dung: dict[str, set[str]] = {}

    def huy(self) -> None:
        """Ghi đè khoá dùng một lần và xoá bảng ánh xạ (trong giới hạn Python cho phép)."""
        for i in range(len(self._khoa)):
            self._khoa[i] = 0
        self._khoa = bytearray()
        self._da_anh_xa.clear()
        self._da_dung.clear()

    @property
    def da_huy(self) -> bool:
        return len(self._khoa) == 0

    def rng(self, loai: str, goc: str, lan: int = 0) -> random.Random:
        if self.da_huy:
            raise RuntimeError("Khoá ánh xạ đã huỷ")
        mac = hmac.new(bytes(self._khoa), f"{loai}|{lan}|{goc}".encode("utf-8"), hashlib.sha256).digest()
        return random.Random(int.from_bytes(mac[:16], "big"))

    def anh_xa(self, loai: str, goc: str, sinh, duy_nhat: bool = True) -> str:
        """Trả giá trị giả cho (loại, giá trị gốc); `sinh(rng)` tạo ứng viên. Khi `duy_nhat`, hai giá
        trị gốc khác nhau không bao giờ nhận cùng giá trị giả (giữ tính duy nhất của cột). Giá trị giả
        không bao giờ trùng chính giá trị gốc (họ tên phổ biến có thể trùng kho tên giả)."""
        k = (loai, goc)
        if k in self._da_anh_xa:
            return self._da_anh_xa[k]
        dung = self._da_dung.setdefault(loai, set())
        for lan in range(1000):
            gt = sinh(self.rng(loai, goc, lan))
            if gt != goc and (not duy_nhat or gt not in dung):
                break
        else:
            raise RuntimeError(f"Không sinh được giá trị giả duy nhất cho loại {loai}")
        dung.add(gt)
        self._da_anh_xa[k] = gt
        return gt


def nhom_tuoi(ngay_sinh: date, moc: date, do_rong: int) -> int:
    tuoi = moc.year - ngay_sinh.year - ((moc.month, moc.day) < (ngay_sinh.month, ngay_sinh.day))
    return tuoi // do_rong


def _lui_nam(d: date, n: int) -> date:
    try:
        return d.replace(year=d.year - n)
    except ValueError:  # 29/02
        return d.replace(year=d.year - n, day=28)


def ngay_sinh_cung_nhom(rng: random.Random, goc: date, moc: date, do_rong: int) -> date:
    """Ngày sinh ngẫu nhiên khác, cùng nhóm tuổi `do_rong` năm tính tại ngày mốc."""
    g = nhom_tuoi(goc, moc, do_rong)
    som_nhat = _lui_nam(moc, do_rong * (g + 1)) + timedelta(days=1)
    muon_nhat = _lui_nam(moc, do_rong * g)
    return som_nhat + timedelta(days=rng.randrange((muon_nhat - som_nhat).days + 1))


def ho_ten(rng: random.Random, gioi: str | None) -> str:
    return ten_gia.ho_ten_theo_gioi(rng, gioi if gioi in ("NAM", "NU") else rng.choice(("NAM", "NU")))


def so_dinh_danh(rng: random.Random, gioi: str | None, nam_sinh: int) -> str:
    return ten_gia.so_dinh_danh_gia(rng, gioi if gioi in ("NAM", "NU") else "NAM", nam_sinh)


def so_the_dang(rng: random.Random) -> str:
    return f"{ten_gia.MA_TINH_GIA}{rng.randrange(10**9):09d}"


def dien_thoai(rng: random.Random) -> str:
    return f"{DAU_SO_DIEN_THOAI}{rng.randrange(10**6):06d}"


def thu_dien_tu(rng: random.Random) -> str:
    return f"canbo.{rng.randrange(10**8):08d}@{DUOI_THU}"


def ten_dang_nhap(rng: random.Random) -> str:
    return f"canbo{rng.randrange(10**8):08d}"


def van_ban_cung_do_dai(rng: random.Random, do_dai: int) -> str:
    """Văn bản tổng hợp đúng `do_dai` ký tự (tính theo ký tự Unicode, không theo byte)."""
    if do_dai <= 0:
        return ""
    phan: list[str] = []
    while len(" ".join(phan)) < do_dai:
        phan.append(rng.choice(TU_VAN_BAN))
    s = " ".join(phan)[:do_dai]
    return s[:-1] + "." if do_dai > 1 else s


def ten_tep(rng: random.Random, goc: str) -> str:
    """Tên tệp tổng hợp giữ phần mở rộng (giúp tái hiện lỗi theo loại tệp)."""
    mo_rong = goc.rsplit(".", 1)[1][:10] if "." in goc else ""
    than = f"tep-{rng.randrange(10**8):08d}"
    return f"{than}.{mo_rong}" if mo_rong else than


def thuoc_kho_ho_ten_gia(ho_ten_: str) -> bool:
    """Họ tên nằm trong không gian sinh của bộ giả: trùng như vậy là ngẫu nhiên, không phải sót."""
    t = ho_ten_.split()
    if len(t) != 3 or t[0] not in ten_gia.HO:
        return False
    return (t[1] in ten_gia.DEM_NAM and t[2] in ten_gia.TEN_NAM) or (t[1] in ten_gia.DEM_NU and t[2] in ten_gia.TEN_NU)
