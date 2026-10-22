package vn.viettel.okrkpi.policy.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Kiểm tham số của quy tắc CEL theo TL-02b SCR-1501 và khai báo {@code params} của manifest
 * (BR-15-02, BR-15-03, BR-15-04), và tổng điểm tối đa các nhóm (BR-15-05, MSG-15-02).
 */
public final class RuleParamRules {

  /** Tổng điểm tối đa phải đạt của các nhóm cộng tiêu chí chung (PL A3, A5). */
  public static final int TONG_DIEM = 100;

  /** Điểm tiêu chí chung luồng Nhà nước khi gói không khai báo (PL A5). */
  public static final int TIEU_CHI_CHUNG_MAC_DINH = 30;

  static final String DIEM_TOI_DA = "diem_toi_da";
  static final String TIEU_CHI_CHUNG = "diem_toi_da_tieu_chi_chung";

  private static final Map<String, Set<String>> ENUMS =
      Map.of(
          "cach_tinh_chat_luong", Set.of("NAM_MUC_PL3", "TRU_25_MOI_LAN"),
          "loai_ngay", Set.of("NGAY_LAM_VIEC", "NGAY_LICH"),
          "ly_do_tra_lai", Set.of("CHI_COT_LOI", "TAT_CA", "KHONG_TINH"));
  private static final Set<String> ROUNDING_MODES = Set.of("NUA_LEN", "CAT_BO");

  private RuleParamRules() {}

  /**
   * Lỗi tham số của một quy tắc; {@code declared} là khối {@code params} của manifest, mỗi khoá có
   * {@code type} và {@code mien} ({@code min}, {@code max}, {@code enum}).
   */
  public static List<PolicyManifestRules.Problem> check(
      Map<String, Object> params, Map<String, Object> declared) {
    List<PolicyManifestRules.Problem> problems = new ArrayList<>();
    if (params == null) {
      return problems;
    }
    params.forEach(
        (key, value) -> {
          String field = "params." + key;
          Set<String> allowed = ENUMS.get(key);
          if (allowed != null && !allowed.contains(value)) {
            problems.add(
                new PolicyManifestRules.Problem(field, "Giá trị không nằm trong danh sách chọn"));
          }
          if ("tru_tien_do".equals(key) && !within(value, 0.0, 0.20)) {
            problems.add(
                new PolicyManifestRules.Problem(field, "Mức trừ tiến độ phải từ 0,00 đến 0,20"));
          }
          if ("lam_tron".equals(key) && !rounding(value)) {
            problems.add(new PolicyManifestRules.Problem(field, "Quy tắc làm tròn chưa hợp lệ"));
          }
          if (DIEM_TOI_DA.equals(key) && !wholeAtLeastZero(value)) {
            problems.add(
                new PolicyManifestRules.Problem(field, "Điểm tối đa phải là số nguyên không âm"));
          }
          if (declared != null && declared.get(key) instanceof Map<?, ?> decl) {
            String message = declaredProblem(value, decl);
            if (message != null) {
              problems.add(new PolicyManifestRules.Problem(field, message));
            }
          }
        });
    return problems;
  }

  /**
   * Tổng điểm tối đa của các nhóm đã khai báo trong tham số các quy tắc ({@code diem_toi_da} hoặc
   * danh sách {@code nhom} có {@code diem_toi_da}); rỗng khi phiên bản chưa khai báo nhóm nào.
   */
  public static OptionalInt groupTotal(Collection<Map<String, Object>> paramsOfRules) {
    boolean any = false;
    int total = 0;
    for (Map<String, Object> params : paramsOfRules) {
      if (params == null) {
        continue;
      }
      if (params.get(DIEM_TOI_DA) instanceof Number n) {
        any = true;
        total += n.intValue();
      }
      if (params.get("nhom") instanceof Collection<?> groups) {
        for (Object g : groups) {
          if (g instanceof Map<?, ?> m && m.get(DIEM_TOI_DA) instanceof Number n) {
            any = true;
            total += n.intValue();
          }
        }
      }
    }
    return any ? OptionalInt.of(total) : OptionalInt.empty();
  }

  /**
   * Tổng để so với {@link #TONG_DIEM}: luồng Đảng chỉ cộng các nhóm, luồng Nhà nước cộng thêm điểm
   * tiêu chí chung (30 và 70, BR-15-05); luồng khác không kiểm.
   */
  public static OptionalInt totalFor(String flow, int groups, Integer generalCriteria) {
    return switch (flow) {
      case "DANG" -> OptionalInt.of(groups);
      case "NHA_NUOC" ->
          OptionalInt.of(
              groups + (generalCriteria == null ? TIEU_CHI_CHUNG_MAC_DINH : generalCriteria));
      default -> OptionalInt.empty();
    };
  }

  /** Thông báo MSG-15-02. */
  public static String groupSumMessage(int total) {
    return "Tổng điểm tối đa các nhóm là " + total + ", phải bằng " + TONG_DIEM + ".";
  }

  private static String declaredProblem(Object value, Map<?, ?> decl) {
    String type = decl.get("type") instanceof String s ? s : "";
    switch (type) {
      case "int" -> {
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
          return "Giá trị phải là số nguyên";
        }
      }
      case "double" -> {
        if (!(value instanceof Number)) {
          return "Giá trị phải là số";
        }
      }
      case "string" -> {
        if (!(value instanceof String)) {
          return "Giá trị phải là chuỗi";
        }
      }
      case "bool" -> {
        if (!(value instanceof Boolean)) {
          return "Giá trị phải là đúng hoặc sai";
        }
      }
      default -> {
        // kiểu cấu trúc (map, list) do quy tắc riêng kiểm
      }
    }
    if (!(decl.get("mien") instanceof Map<?, ?> mien)) {
      return null;
    }
    if (mien.get("enum") instanceof Collection<?> values && !values.contains(value)) {
      return "Giá trị không nằm trong danh sách chọn";
    }
    if (value instanceof Number n) {
      if (mien.get("min") instanceof Number min && n.doubleValue() < min.doubleValue()) {
        return "Giá trị phải không nhỏ hơn " + min;
      }
      if (mien.get("max") instanceof Number max && n.doubleValue() > max.doubleValue()) {
        return "Giá trị phải không lớn hơn " + max;
      }
    }
    return null;
  }

  private static boolean within(Object value, double min, double max) {
    return value instanceof Number n && n.doubleValue() >= min && n.doubleValue() <= max;
  }

  private static boolean wholeAtLeastZero(Object value) {
    return value instanceof Number n
        && n.doubleValue() >= 0
        && n.doubleValue() == Math.rint(n.doubleValue());
  }

  private static boolean rounding(Object value) {
    if (!(value instanceof Map<?, ?> m)) {
      return false;
    }
    Object digits = m.containsKey("chu_so") ? m.get("chu_so") : m.get("digits");
    Object mode = m.containsKey("kieu") ? m.get("kieu") : m.get("mode");
    return wholeAtLeastZero(digits)
        && ((Number) digits).intValue() <= 6
        && ROUNDING_MODES.contains(mode);
  }
}
