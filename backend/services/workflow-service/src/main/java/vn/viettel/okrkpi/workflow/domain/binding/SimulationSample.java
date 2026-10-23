package vn.viettel.okrkpi.workflow.domain.binding;

import java.util.Map;
import java.util.Optional;

/**
 * Bộ hồ sơ mẫu mặc định chạy trên engine thử (suy luận: TL-02b SCR-1504 nêu "test của mẫu với tham
 * số này" mà chưa liệt kê ca). Mỗi ca chọn luồng, người trình có là người đứng đầu đơn vị không,
 * cấp cuối duyệt hay trả lại, và biến của điều kiện bỏ qua cấp.
 */
public enum SimulationSample {
  DANG_DUYET_DU_CAP(
      "Khối Đảng, các cấp đều duyệt", "DANG", false, false, "LANH_DAO", "TOT", "XUAT_SAC"),
  DANG_TRA_LAI_CAP_CUOI(
      "Khối Đảng, cấp cuối trả lại", "DANG", false, true, "LANH_DAO", "TOT", "XUAT_SAC"),
  DANG_NGUOI_DUNG_DAU(
      "Khối Đảng, người trình là người đứng đầu đơn vị",
      "DANG",
      true,
      false,
      "LANH_DAO",
      "TOT",
      "TOT"),
  NHA_NUOC_DUYET_DU_CAP(
      "Khối Nhà nước, các cấp đều duyệt", "NHA_NUOC", false, false, "LANH_DAO", "TOT", "XUAT_SAC"),
  NHA_NUOC_TRA_LAI_CAP_CUOI(
      "Khối Nhà nước, cấp cuối trả lại", "NHA_NUOC", false, true, "LANH_DAO", "TOT", "XUAT_SAC"),
  NHA_NUOC_NGUOI_DUNG_DAU(
      "Khối Nhà nước, người trình là người đứng đầu đơn vị",
      "NHA_NUOC",
      true,
      false,
      "LANH_DAO",
      "TOT",
      "TOT"),
  CHUYEN_VIEN_MUC_KHOP(
      "Chuyên viên, mức thẩm định bằng mức tự đề xuất",
      "DANG",
      false,
      false,
      "CHUYEN_VIEN",
      "TOT",
      "TOT");

  /** Kết thúc khi mọi cấp duyệt. */
  public static final String DA_DUYET = "DA_DUYET";

  /** Kết thúc khi một cấp trả lại. */
  public static final String TRA_LAI = "TRA_LAI";

  private final String label;
  private final String flow;
  private final boolean headOfUnit;
  private final boolean returnAtLast;
  private final String jobTitle;
  private final String appraisedLevel;
  private final String proposedLevel;

  SimulationSample(
      String label,
      String flow,
      boolean headOfUnit,
      boolean returnAtLast,
      String jobTitle,
      String appraisedLevel,
      String proposedLevel) {
    this.label = label;
    this.flow = flow;
    this.headOfUnit = headOfUnit;
    this.returnAtLast = returnAtLast;
    this.jobTitle = jobTitle;
    this.appraisedLevel = appraisedLevel;
    this.proposedLevel = proposedLevel;
  }

  public String label() {
    return label;
  }

  public String flow() {
    return flow;
  }

  public boolean headOfUnit() {
    return headOfUnit;
  }

  public boolean returnAtLast() {
    return returnAtLast;
  }

  /** Trạng thái kết thúc mong đợi. */
  public String expectedEnd() {
    return returnAtLast ? TRA_LAI : DA_DUYET;
  }

  /** Biến của điều kiện bỏ qua cấp (tên theo ví dụ SCR-1504). */
  public Map<String, Object> variables() {
    return Map.of(
        "nguoi_duoc_danh_gia",
        Map.of("chuc_danh", jobTitle),
        "muc_tham_dinh",
        appraisedLevel,
        "muc_tu_de_xuat",
        proposedLevel,
        "la_nguoi_dung_dau",
        headOfUnit,
        "khoi",
        flow);
  }

  public static Optional<SimulationSample> parse(String code) {
    for (SimulationSample s : values()) {
      if (s.name().equals(code)) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }
}
