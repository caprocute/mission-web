"""Tên người, tên đơn vị và số định danh giả có định dạng hợp lệ nhưng không trùng người thật."""

from __future__ import annotations

from datetime import date, timedelta

from .ngau_nhien import NguonNgauNhien

HO = ["Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Huỳnh", "Phan", "Vũ", "Võ", "Đặng", "Bùi", "Đỗ",
      "Hồ", "Ngô", "Dương", "Lý", "Đinh", "Trương", "Mai", "Tạ"]
DEM_NAM = ["Văn", "Hữu", "Đức", "Minh", "Quang", "Thành", "Công", "Xuân", "Ngọc", "Gia"]
DEM_NU = ["Thị", "Ngọc", "Thu", "Thanh", "Minh", "Phương", "Hồng", "Kim", "Bảo", "Diệu"]
TEN_NAM = ["An", "Bình", "Cường", "Dũng", "Đạt", "Hải", "Hiếu", "Hùng", "Khoa", "Long", "Nam",
           "Phong", "Quân", "Sơn", "Tâm", "Thắng", "Trung", "Tuấn", "Việt", "Vinh"]
TEN_NU = ["Anh", "Chi", "Dung", "Giang", "Hà", "Hạnh", "Hoa", "Hương", "Lan", "Linh", "Mai",
          "Nga", "Ngân", "Nhung", "Oanh", "Phương", "Quyên", "Thảo", "Trang", "Yến"]
LOAI_DON_VI_THEO_CAP = {
    1: ("TO_CHUC_DANG", "Đảng bộ"),
    2: ("CO_QUAN", "Sở"),
    3: ("CO_QUAN", "Ủy ban nhân dân"),
    4: ("PHONG_BAN", "Phòng"),
    5: ("CHI_BO", "Tổ công tác"),
}
LINH_VUC = ["Nội vụ", "Tài chính", "Kế hoạch", "Giáo dục", "Y tế", "Xây dựng", "Công thương",
            "Nông nghiệp", "Tư pháp", "Văn hoá", "Khoa học", "Giao thông", "Tài nguyên", "Lao động"]
# Mã tỉnh 999 không được cấp cho tỉnh, thành phố nào: số định danh giả không thể trùng người thật.
MA_TINH_GIA = "999"


def ho_ten(rng: NguonNgauNhien) -> tuple[str, str]:
    gioi = "NAM" if rng.random() < 0.52 else "NU"
    return ho_ten_theo_gioi(rng, gioi), gioi


def ho_ten_theo_gioi(rng, gioi: str) -> str:
    """Họ tên giả theo giới cho trước; dùng chung với tác vụ ẩn danh hoá DL-15 (giữ phân phối giới)."""
    dem, ten = (DEM_NAM, TEN_NAM) if gioi == "NAM" else (DEM_NU, TEN_NU)
    return f"{rng.choice(HO)} {rng.choice(dem)} {rng.choice(ten)}"


def so_dinh_danh_gia(rng: NguonNgauNhien, gioi: str, nam_sinh: int) -> str:
    the_ky = 0 if nam_sinh < 2000 else 2
    ma_gioi = the_ky + (1 if gioi == "NU" else 0)
    return f"{MA_TINH_GIA}{ma_gioi}{nam_sinh % 100:02d}{rng.randrange(10**6):06d}"


def ngay_sinh(rng: NguonNgauNhien, nam: int) -> date:
    return date(nam - 60, 1, 1) + timedelta(days=rng.randrange(365 * 38))


def ten_don_vi(rng: NguonNgauNhien, cap: int, stt: int) -> tuple[str, str]:
    loai, tien_to = LOAI_DON_VI_THEO_CAP[cap]
    return loai, f"{tien_to} {rng.choice(LINH_VUC)} số {stt}"
