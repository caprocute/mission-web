package vn.viettel.okrkpi.workflow.domain.approval;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tên và ngân sách biến của ba mẫu luồng duyệt (TL-01 G2 quy tắc 5: biến chỉ mang id, mã và tham
 * số nhỏ, tổng dưới 4 KB; dữ liệu nghiệp vụ nằm ở lõi).
 */
public final class ApprovalVariables {

  public static final String TENANT_ID = "tenantId";
  public static final String KHOI = "khoi";
  public static final String LOAI_HO_SO = "loaiHoSo";
  public static final String LA_NGUOI_DUNG_DAU = "laNguoiDungDau";
  public static final String DON_VI_ID = "donViId";
  public static final String TIEU_DE = "tieuDe";
  public static final String PERIOD_ID = "periodId";
  public static final String REMIND_AFTER = "remindAfter";
  public static final String PHIEN_BAN_DOI_TUONG = "phienBanDoiTuong";
  public static final String PHIEN_BAN_POLICY_PACK = "phienBanPolicyPack";
  public static final String CHO_PHEP_UY_QUYEN = "choPhepUyQuyen";
  public static final String CAP_TUY_CHINH = "capTuyChinh";
  public static final String PHUONG_THUC_KY = "phuongThucKy";
  public static final String KE_HOACH_PHIEN_BAN = "keHoachPhienBan";
  public static final String NHIEM_VU_ID = "nhiemVuId";
  public static final String NGUYEN_NHAN = "nguyenNhan";
  public static final String APPROVAL_LEVELS = "approvalLevels";
  public static final String DECISION = "decision";

  /** Biến kết quả DMN mà engine đặt theo khoá bảng quyết định (đa dòng luôn là mảng). */
  public static final String DMN_RESULT = "chon-cap-duyet";

  /** Tổng ngân sách biến của một tiến trình (TL-01 G2 quy tắc 5). */
  public static final int MAX_TOTAL_BYTES = 4096;

  /** Ngân sách của biến khởi tạo (khớp khai báo {@code bien} của các tệp {@code .mau.json}). */
  public static final int MAX_START_BYTES = 1536;

  /** Ngân sách của {@code approvalLevels} (8 cấp khoảng 1,2 KB). */
  public static final int MAX_LEVELS_BYTES = 1600;

  private static final String LEVEL_SEPARATOR = ";";
  private static final String FIELD_SEPARATOR = ":";

  private ApprovalVariables() {}

  /** Ước lượng kích thước biến: độ dài UTF-8 của tên và dạng chuỗi của giá trị. */
  public static int sizeOf(Map<String, ?> variables) {
    int total = 0;
    for (Map.Entry<String, ?> e : variables.entrySet()) {
      total += e.getKey().getBytes(StandardCharsets.UTF_8).length;
      Object value = e.getValue();
      total += value == null ? 0 : String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
    }
    return total;
  }

  /** Mã hoá gọn danh sách cấp tuỳ chỉnh: {@code role:SCOPE:days;...}. */
  public static String encodeRules(List<ApprovalRule> rules) {
    List<String> parts = new ArrayList<>(rules.size());
    for (ApprovalRule rule : rules) {
      parts.add(
          rule.role() + FIELD_SEPARATOR + rule.scope().name() + FIELD_SEPARATOR + rule.workingDays());
    }
    return String.join(LEVEL_SEPARATOR, parts);
  }

  /** Giải mã danh sách cấp tuỳ chỉnh; thứ tự cấp theo vị trí, rỗng khi chuỗi rỗng. */
  public static List<ApprovalRule> decodeRules(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return List.of();
    }
    String[] levels = encoded.split(LEVEL_SEPARATOR, -1);
    List<ApprovalRule> rules = new ArrayList<>(levels.length);
    for (int i = 0; i < levels.length; i++) {
      String[] fields = levels[i].split(FIELD_SEPARATOR, -1);
      if (fields.length != 3) {
        throw new IllegalArgumentException("Cấp duyệt tuỳ chỉnh sai dạng");
      }
      Optional<ApprovalScope> scope = ApprovalScope.parse(fields[1]);
      if (scope.isEmpty()) {
        throw new IllegalArgumentException("Phạm vi cấp duyệt không thuộc danh mục");
      }
      int days;
      try {
        days = Integer.parseInt(fields[2]);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Hạn cấp duyệt phải là số ngày làm việc", e);
      }
      rules.add(new ApprovalRule(i + 1, fields[0], scope.get(), days));
    }
    return rules;
  }
}
