"""Giải thích ngắn bằng tiếng Việt dựng từ chuỗi bằng chứng (chỉ có mã và số liệu). Hợp đồng EVT-20 không có trường
văn bản nên job không gửi câu này; MOD-07 dựng câu theo cùng mẫu khi hiển thị cảnh báo (suy luận, wiki/14), còn job
dùng để ghi nhật ký và kiểm thử rằng mỗi điều kiện đều có số liệu đi kèm."""

from __future__ import annotations

MAU: dict[str, str] = {
    "MUC_TU_DE_XUAT_CAO_HON": "Mức tự đề xuất cao hơn mức thẩm định nhiều quý liên tiếp",
    "DIEM_TU_TINH_CAO_HON": "Điểm tự tính cao hơn điểm sau thẩm định nhiều quý liên tiếp",
    "TY_LE_XUAT_SAC_VUOT_NGUONG": "Tỷ lệ hoàn thành xuất sắc của đơn vị vượt ngưỡng",
    "CUNG_MOT_MUC": "Phần lớn cán bộ của đơn vị cùng một mức xếp loại",
    "DO_LECH_DIEM_THAP": "Điểm của cán bộ trong đơn vị chênh lệch rất ít",
    "DAT_CHI_TIEU_SOM": "Kết quả then chốt đạt chỉ tiêu ngay ngày thứ {NGAY_DAT_TRONG_KY} của kỳ",
    "CHI_TIEU_THAP_HON_KY_TRUOC": "Chỉ tiêu thấp hơn kết quả kỳ trước {CHI_TIEU_THAP_HON_PHAN_TRAM}%",
    "DON_CUOI_KY": "{TY_LE_CUOI_KY_PHAN_TRAM}% trong {SO_LUOT_CAP_NHAT} lượt cập nhật tiến độ dồn vào cuối kỳ",
    "DON_CUOI_TUAN": "{TY_LE_CUOI_TUAN_PHAN_TRAM}% trong {SO_LUOT_CAP_NHAT} lượt cập nhật tiến độ dồn vào cuối tuần",
    "SO_NHIEM_VU_TANG_KHOI_LUONG_KHONG_DOI":
        "Số nhiệm vụ tăng từ {SO_NHIEM_VU_KY_TRUOC} lên {SO_NHIEM_VU} trong khi tổng khối lượng gần như không đổi",
    "NHIEU_NHIEM_VU_CUNG_KE_HOACH_CUNG_HAN": "{SO_NHIEM_VU_CUNG_HAN} nhiệm vụ cùng mục kế hoạch và cùng hạn",
    "NGHIEM_THU_SAU_TAM_TINH": "Nhiệm vụ được nghiệm thu sau khi đã tính điểm tạm, điểm thay đổi {CHENH_DIEM} điểm",
    "MUC_TIEU_DUYET_SAU_TAM_TINH": "Mục tiêu được duyệt sau khi đã tính điểm tạm, điểm thay đổi {CHENH_DIEM} điểm",
    "SUA_SAU_KHOA_KY": "Dữ liệu được sửa sau khi đã khoá kỳ, điểm thay đổi {CHENH_DIEM} điểm",
    "MIEN_TRU_KHACH_QUAN_LAP_LAI":
        "Người duyệt chấp nhận nguyên nhân khách quan {SO_LAN_MIEN_TRU} lần trong thời gian xét",
    "TY_LE_CHAP_NHAN_KHACH_QUAN_CAO":
        "Người duyệt chấp nhận {TY_LE_CHAP_NHAN_PHAN_TRAM}% trong {SO_DE_NGHI_KHACH_QUAN} đề nghị khai nguyên nhân"
        " khách quan, cao hơn mức chung {TY_LE_CHUNG_PHAN_TRAM}%",
}


def so_lieu(evidence: list[str]) -> dict[str, str]:
    out = {}
    for x in evidence:
        if x.startswith("so_lieu:") and "=" in x:
            k, v = x[len("so_lieu:"):].split("=", 1)
            out[k] = v.replace(".", ",")
    return out


def giai_thich(evidence: list[str]) -> str:
    """Một câu cho mỗi điều kiện thoả, nối bằng dấu chấm phẩy. Thiếu số liệu cho mẫu thì ném KeyError."""
    data = so_lieu(evidence)
    parts = [MAU[x[len("dieu_kien:"):]].format(**data) for x in evidence if x.startswith("dieu_kien:")]
    return "; ".join(parts) + "."
