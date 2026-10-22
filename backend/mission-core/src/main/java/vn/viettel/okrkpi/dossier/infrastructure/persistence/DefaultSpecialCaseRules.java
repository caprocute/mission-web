package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.specialcase.port.SpecialCaseRuleSource;
import vn.viettel.okrkpi.dossier.domain.specialcase.SpecialCaseRule;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bảng {@code truong_hop_dac_thu} mặc định, chép đúng gói khối Đảng "quy định mới" v2.0 ({@code
 * data/policy-pack/khoi-dang/quy-dinh-moi/policy.yaml}, DL-01). MOD-06 chưa đọc được gói đã ghim
 * cho kỳ vì TL-01 không cho {@code dossier} phụ thuộc {@code policy :: api}; khi có cổng công bố
 * bảng này qua {@code config :: api} (như {@code PolicySchemaPort}) thì bean đọc gói thay bean này.
 * Tham số {@code null} giữ nguyên như gói (chờ nghiệp vụ).
 */
public class DefaultSpecialCaseRules implements SpecialCaseRuleSource {

  static final List<SpecialCaseRule> PARTY_V2 =
      List.of(
          rule(
              "DIEU_DONG_DUOI_2_THANG",
              1,
              "PL 6 §1",
              "Đơn vị cũ đánh giá, đơn vị mới cho ý kiến.",
              "XEP_LOAI_TAI_DON_VI_CU",
              null,
              "nguong_thang",
              2),
          rule(
              "DIEU_DONG_TU_2_THANG",
              2,
              "PL 6 §2",
              "Đơn vị mới đánh giá; đơn vị cũ cung cấp kết quả phần thời gian công tác trước khi"
                  + " điều động.",
              "XEP_LOAI_BINH_THUONG",
              null,
              "nguong_thang",
              2),
          rule(
              "KIEM_NHIEM",
              3,
              "PL 6 §3",
              "Đánh giá tại nơi giữ chức vụ chính; nơi kiêm nhiệm cho ý kiến.",
              "XEP_LOAI_BINH_THUONG",
              null),
          rule(
              "NGHI_DAI_NGAY",
              4,
              "PL 6 §4; QT-10",
              "Không xếp loại kỳ quý và ghi chú trên Mẫu 2; quý này không tính vào điểm năm và"
                  + " không bị coi là không hoàn thành nhiệm vụ.",
              "KHONG_XEP_LOAI",
              null,
              "ty_le_thoi_gian_toi_thieu",
              0.5),
          rule(
              "DI_HOC_BIET_PHAI",
              5,
              "PL 6 §5",
              "Cơ sở đào tạo hoặc nơi biệt phái cung cấp nhận xét; đơn vị quản lý xếp loại.",
              "XEP_LOAI_BINH_THUONG",
              null),
          rule(
              "MOI_TUYEN_DUNG_BO_NHIEM",
              6,
              "PL 6 §6",
              "Đánh giá theo thời gian công tác thực tế; không xét mức Hoàn thành xuất sắc nhiệm"
                  + " vụ năm.",
              "XEP_LOAI_BINH_THUONG",
              null,
              "nguong_thang",
              6),
          rule(
              "DANG_XEM_XET_XAC_MINH",
              7,
              "PL 6 §7",
              "Tạm treo việc quyết định xếp loại cho đến khi có kết luận.",
              "CHO_KET_LUAN",
              null),
          rule(
              "BI_KY_LUAT",
              8,
              "PL 6 §8; khoản 11.2.4",
              "Áp dụng điều kiện loại trừ tương ứng với hình thức kỷ luật.",
              "XEP_LOAI_BINH_THUONG",
              null),
          rule(
              "DON_VI_TO_CHUC_LAI",
              9,
              "PL 6 §9",
              "Dùng cơ cấu tổ chức theo ngày hiệu lực; cấp trên trực tiếp đánh giá.",
              "XEP_LOAI_BINH_THUONG",
              null),
          rule(
              "NGHI_HUU_THOI_VIEC",
              10,
              "PL 6 §10",
              "Đánh giá đến thời điểm nghỉ nếu đủ thời gian công tác tối thiểu.",
              "XEP_LOAI_BINH_THUONG",
              "KHONG_XEP_LOAI",
              "thoi_gian_toi_thieu_thang",
              null),
          rule(
              "DANG_VIEN_NGOAI_HTCT",
              11,
              "PL 6 §11; PL 2E",
              "Áp dụng bộ tiêu chí riêng theo Phụ lục 2E.",
              "XEP_LOAI_BINH_THUONG",
              null),
          rule(
              "KHONG_DU_DIEU_KIEN_MUC_DE_XUAT",
              12,
              "PL 6 §12; BR-09-10",
              "Chuyển xuống mức liền kề và hệ thống sinh câu giải thích nêu điều kiện còn thiếu.",
              "XEP_LOAI_BINH_THUONG",
              null),
          rule(
              "NGUOI_DUNG_DAU_DON_VI_KHTNV",
              13,
              "PL 6 §13; QĐ khoản 14.3.3; BR-09-13",
              "Áp dụng quy định về trách nhiệm của người đứng đầu; hồ sơ chỉ được quyết định sau"
                  + " khi các đơn vị trực thuộc đã có mức.",
              "XEP_LOAI_BINH_THUONG",
              null,
              "nguong_ty_le",
              0.5));

  @Override
  public List<SpecialCaseRule> rules(TenantId tenant, UUID periodId) {
    return PARTY_V2;
  }

  private static SpecialCaseRule rule(
      String code,
      int section,
      String legalRef,
      String handling,
      String effect,
      String insufficient,
      Object... params) {
    Map<String, Object> p = new HashMap<>();
    for (int i = 0; i + 1 < params.length; i += 2) {
      p.put((String) params[i], params[i + 1]);
    }
    return new SpecialCaseRule(code, section, legalRef, handling, effect, insufficient, p);
  }
}
