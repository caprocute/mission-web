package vn.viettel.okrkpi.workflow.domain.binding;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;

/**
 * Linter tham số luồng duyệt khi lưu và mô phỏng (DV-06-08, TL-02b SCR-1504 bảng trường dòng 3 đến
 * 8, BR-15-12). Khoá của {@code params} theo SCR-1504 ({@code so_cap}, {@code cap[]} với {@code
 * buoc}, {@code vai_tro}, {@code pham_vi}, {@code han_ngay_lam_viec}, {@code ky_so}, {@code ky_lo},
 * {@code bo_qua_khi}; {@code thoi_han_moi_buoc}, {@code nhac_truoc}, {@code khi_qua_han}) và TL-01
 * ({@code remindAfter}, {@code allowDelegation}, {@code signMethod}), đúng các khoá mà {@code
 * JdbcBindingReader} đọc khi khởi động luồng.
 */
public final class ParamsLinter {

  public static final String LEVEL_COUNT = "so-cap";
  public static final String ROLE = "vai-tro";
  public static final String SCOPE = "pham-vi";
  public static final String DURATION = "thoi-han";
  public static final String MANDATORY_SIGN = "ky-so-bat-buoc";
  public static final String SKIP = "dieu-kien-bo-qua";
  public static final String CEL_TYPE = "kieu-cel";
  public static final String PROGRESS_DEDUCTION = "tru-tien-do";
  public static final String OVERDUE = "khi-qua-han";
  public static final String REMIND = "nhac-sau";
  public static final String SIGN_METHOD = "phuong-thuc-ky";

  /**
   * Vai trò hệ thống được gán trong {@code config.role_binding} (ràng buộc {@code
   * ck_role_binding__role_code}, baseline mục 10). SVC-06 không đọc được schema {@code config} nên
   * dùng danh mục đóng của ràng buộc (suy luận, wiki trang 14).
   */
  public static final Set<String> ROLES =
      Set.of(
          "tenant_admin",
          "policy_author",
          "leader",
          "manager",
          "member",
          "appraiser",
          "decider",
          "second_approver",
          "inspector");

  /** Hành vi khi quá hạn (SCR-1504 bảng trường dòng 6). */
  public static final List<String> OVERDUE_ACTIONS =
      List.of("NHAC_LAI", "CHUYEN_CAP_PHO_UY_QUYEN", "CHUYEN_CAP_TREN");

  /** Thời hạn tính bằng ngày làm việc (SCR-1504 bảng trường dòng 5). */
  public static final int MIN_DAYS = 1;

  public static final int MAX_DAYS = 15;

  /** Mức trừ tiến độ (TL-02b SCR-1503 dòng 5). */
  public static final BigDecimal MAX_DEDUCTION = new BigDecimal("0.20");

  private static final Pattern WORKING_DAYS = Pattern.compile("P(\\d{1,3})D_LV");
  private static final String LEVELS = "cap";

  private final SkipConditionChecker cel;

  public ParamsLinter(SkipConditionChecker cel) {
    this.cel = Objects.requireNonNull(cel, "cel");
  }

  /** Kiểm tham số theo khai báo của mẫu. */
  public LintResult lint(Map<String, Object> params, TemplateParamRules rules) {
    Objects.requireNonNull(rules, "rules");
    List<LintIssue> issues = new ArrayList<>();
    Map<String, Object> p = params == null ? Map.of() : params;
    List<Map<String, Object>> levels = levels(p, issues);
    if (levels != null) {
      checkLevelCount(p, levels, rules, issues);
      for (int i = 0; i < levels.size(); i++) {
        checkLevel(levels.get(i), i + 1, levels.size(), rules, issues);
      }
    }
    checkDays(p.get("thoi_han_moi_buoc"), "params.thoi_han_moi_buoc", issues);
    checkDays(p.get("nhac_truoc"), "params.nhac_truoc", issues);
    Object overdue = p.get("khi_qua_han");
    if (overdue != null && !OVERDUE_ACTIONS.contains(overdue.toString())) {
      issues.add(
          LintIssue.error(
              OVERDUE,
              "params.khi_qua_han",
              "Hành vi khi quá hạn phải là nhắc lại, chuyển cấp phó hoặc chuyển cấp trên"));
    }
    checkRemind(p.get("remindAfter"), issues);
    Object sign = p.get("signMethod");
    if (sign != null && !ApprovalParams.SIGN_METHODS.contains(sign.toString())) {
      issues.add(
          LintIssue.error(
              SIGN_METHOD, "params.signMethod", "Phương thức ký không thuộc danh mục cho phép"));
    }
    checkDeduction(p.get("muc_tru_tien_do"), issues);
    return LintResult.of(issues);
  }

  /** Các bước sinh từ tham số; tham số chưa hợp lệ thì bỏ qua cấp sai dạng. */
  public List<StepPreview> steps(Map<String, Object> params, TemplateParamRules rules) {
    List<Map<String, Object>> levels = levels(params == null ? Map.of() : params, null);
    return steps(levels == null ? rules.defaultLevels() : levels, rules);
  }

  /** Các bước mặc định của mẫu. */
  public static List<StepPreview> defaultSteps(TemplateParamRules rules) {
    return steps(rules.defaultLevels(), rules);
  }

  private static List<StepPreview> steps(
      List<Map<String, Object>> levels, TemplateParamRules rules) {
    List<StepPreview> steps = new ArrayList<>(levels.size());
    for (int i = 0; i < levels.size(); i++) {
      Map<String, Object> level = levels.get(i);
      int order = i + 1;
      String key = stepKey(level, order);
      Object sign = level.get("ky_so");
      boolean signs = sign instanceof Boolean b ? b : rules.mustSign(order, levels.size(), key);
      steps.add(
          new StepPreview(
              key,
              stepName(key, order),
              text(level.get("vai_tro")),
              signs,
              Boolean.TRUE.equals(level.get("ky_lo")),
              level.get("bo_qua_khi") != null));
    }
    return steps;
  }

  /**
   * Thứ tự (từ 1) các cấp được bỏ qua với biến của hồ sơ mẫu; chỉ gọi sau khi {@link #lint} đạt.
   */
  public List<Integer> skippedOrders(Map<String, Object> params, Map<String, Object> variables) {
    List<Map<String, Object>> levels = levels(params, null);
    List<Integer> skipped = new ArrayList<>();
    if (levels == null) {
      return skipped;
    }
    for (int i = 0; i < levels.size(); i++) {
      Object expr = levels.get(i).get("bo_qua_khi");
      if (expr != null && cel.evaluate(expr.toString(), variables)) {
        skipped.add(i + 1);
      }
    }
    return skipped;
  }

  /** Mã bước của cấp: {@code buoc} nếu có, ngược lại {@code CAP_<thứ tự>}. */
  public static String stepKey(Map<String, Object> level, int order) {
    Object step = level.get("buoc");
    return step == null || step.toString().isBlank() ? "CAP_" + order : step.toString();
  }

  /** Khai báo tham số dạng JSON Schema cho biểu mẫu SCR-1504 ({@code paramSchema}). */
  public static Map<String, Object> paramSchema(TemplateParamRules rules) {
    Map<String, Object> level = new LinkedHashMap<>();
    level.put("type", "object");
    level.put("required", List.of("vai_tro"));
    Map<String, Object> levelProps = new LinkedHashMap<>();
    levelProps.put("buoc", Map.of("type", "string", "maxLength", 32));
    levelProps.put("vai_tro", Map.of("type", "string", "enum", ROLES.stream().sorted().toList()));
    levelProps.put(
        "pham_vi",
        Map.of(
            "type",
            "string",
            "enum",
            java.util.Arrays.stream(ApprovalScope.values()).map(Enum::name).toList()));
    levelProps.put(
        "han_ngay_lam_viec", Map.of("type", "integer", "minimum", MIN_DAYS, "maximum", MAX_DAYS));
    levelProps.put("ky_so", Map.of("type", "boolean"));
    levelProps.put("ky_lo", Map.of("type", "boolean"));
    levelProps.put("bo_qua_khi", Map.of("type", "string", "maxLength", 2000));
    level.put("properties", levelProps);

    String days = "^P([1-9]|1[0-5])D_LV$";
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "so_cap",
        Map.of(
            "type",
            "integer",
            "minimum",
            rules.minLevels(),
            "maximum",
            rules.maxLevels(),
            "default",
            Math.max(
                rules.minLevels(), Math.min(rules.maxLevels(), rules.defaultLevels().size()))));
    props.put(
        LEVELS,
        Map.of(
            "type",
            "array",
            "minItems",
            rules.minLevels(),
            "maxItems",
            rules.maxLevels(),
            "items",
            level));
    props.put("thoi_han_moi_buoc", Map.of("type", "string", "pattern", days, "default", "P3D_LV"));
    props.put("nhac_truoc", Map.of("type", "string", "pattern", days, "default", "P1D_LV"));
    props.put(
        "khi_qua_han",
        Map.of("type", "string", "enum", OVERDUE_ACTIONS, "default", "CHUYEN_CAP_PHO_UY_QUYEN"));
    props.put("allowDelegation", Map.of("type", "boolean", "default", true));
    props.put(
        "signMethod",
        Map.of("type", "string", "enum", ApprovalParams.SIGN_METHODS.stream().sorted().toList()));
    props.put("muc_tru_tien_do", Map.of("type", "number", "minimum", 0, "maximum", 0.2));
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("required", List.of(LEVELS));
    schema.put("properties", props);
    return schema;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> levels(Map<String, Object> p, List<LintIssue> issues) {
    Object raw = p.get(LEVELS);
    if (!(raw instanceof List<?> list)) {
      if (issues != null) {
        issues.add(LintIssue.error(LEVEL_COUNT, "params.cap", "Cần khai các cấp duyệt của luồng"));
      }
      return null;
    }
    List<Map<String, Object>> levels = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i) instanceof Map<?, ?> m) {
        levels.add((Map<String, Object>) m);
      } else if (issues != null) {
        issues.add(
            LintIssue.error(
                LEVEL_COUNT, "params.cap[" + i + "]", "Cấp duyệt phải là một đối tượng"));
      } else {
        return null;
      }
    }
    return levels;
  }

  private static void checkLevelCount(
      Map<String, Object> p,
      List<Map<String, Object>> levels,
      TemplateParamRules rules,
      List<LintIssue> issues) {
    int n = levels.size();
    if (n < rules.minLevels() || n > rules.maxLevels()) {
      issues.add(
          LintIssue.error(
              LEVEL_COUNT,
              "params.cap",
              "Mẫu cho phép từ " + rules.minLevels() + " đến " + rules.maxLevels() + " cấp duyệt"));
    }
    Object declared = p.get("so_cap");
    if (declared != null && !(declared instanceof Number num && num.intValue() == n)) {
      issues.add(
          LintIssue.error(
              LEVEL_COUNT, "params.so_cap", "Số cấp duyệt khác số cấp đã khai trong danh sách"));
    }
  }

  private void checkLevel(
      Map<String, Object> level,
      int order,
      int total,
      TemplateParamRules rules,
      List<LintIssue> issues) {
    String path = "params.cap[" + (order - 1) + "]";
    String key = stepKey(level, order);
    Object role = level.get("vai_tro");
    if (role == null || !ROLES.contains(role.toString())) {
      issues.add(
          LintIssue.error(ROLE, path + ".vai_tro", "Vai trò của cấp " + order + " không tồn tại"));
    }
    Object scope = level.get("pham_vi");
    if (scope != null && ApprovalScope.parse(scope.toString()).isEmpty()) {
      issues.add(
          LintIssue.error(
              SCOPE, path + ".pham_vi", "Phạm vi đơn vị của cấp " + order + " không hợp lệ"));
    }
    Object days = level.get("han_ngay_lam_viec");
    if (days != null
        && !(days instanceof Number num
            && num.doubleValue() == num.intValue()
            && num.intValue() >= MIN_DAYS
            && num.intValue() <= MAX_DAYS)) {
      issues.add(
          LintIssue.error(
              DURATION,
              path + ".han_ngay_lam_viec",
              "Thời hạn của cấp " + order + " phải từ 1 đến 15 ngày làm việc"));
    }
    if (rules.mustSign(order, total, key) && Boolean.FALSE.equals(level.get("ky_so"))) {
      issues.add(
          LintIssue.error(
              MANDATORY_SIGN,
              path + ".ky_so",
              "Không được bỏ bước ký số bắt buộc của mẫu ở cấp " + order));
    }
    Object skip = level.get("bo_qua_khi");
    if (skip != null) {
      if (!rules.canSkip(order, total, key)) {
        issues.add(
            LintIssue.error(SKIP, path + ".bo_qua_khi", "Mẫu không cho phép bỏ qua cấp " + order));
      } else {
        Optional<String> problem =
            skip.toString().isBlank()
                ? Optional.of("Điều kiện bỏ qua đang để trống")
                : cel.problem(skip.toString());
        problem.ifPresent(
            m ->
                issues.add(
                    LintIssue.error(
                        CEL_TYPE,
                        path + ".bo_qua_khi",
                        "Điều kiện bỏ qua cấp " + order + " không hợp lệ: " + m)));
      }
    }
  }

  private static void checkDays(Object value, String path, List<LintIssue> issues) {
    if (value == null) {
      return;
    }
    Matcher m = WORKING_DAYS.matcher(value.toString());
    int days = m.matches() ? Integer.parseInt(m.group(1)) : -1;
    if (days < MIN_DAYS || days > MAX_DAYS) {
      issues.add(LintIssue.error(DURATION, path, "Thời hạn phải từ 1 đến 15 ngày làm việc"));
    }
  }

  private static void checkRemind(Object value, List<LintIssue> issues) {
    if (value == null) {
      return;
    }
    try {
      Duration d = Duration.parse(value.toString());
      if (d.compareTo(Duration.ofHours(1)) >= 0 && d.compareTo(Duration.ofDays(30)) <= 0) {
        return;
      }
    } catch (DateTimeParseException e) {
      // Rơi xuống báo lỗi.
    }
    issues.add(
        LintIssue.error(REMIND, "params.remindAfter", "Thời gian nhắc phải từ 1 giờ đến 30 ngày"));
  }

  private static void checkDeduction(Object value, List<LintIssue> issues) {
    if (value == null) {
      return;
    }
    BigDecimal d = null;
    if (value instanceof Number n) {
      d = new BigDecimal(n.toString());
    }
    if (d == null
        || d.signum() < 0
        || d.compareTo(MAX_DEDUCTION) > 0
        || d.stripTrailingZeros().scale() > 2) {
      issues.add(
          LintIssue.error(
              PROGRESS_DEDUCTION,
              "params.muc_tru_tien_do",
              "Mức trừ tiến độ phải từ 0,00 đến 0,20, tối đa hai chữ số thập phân"));
    }
  }

  private static String stepName(String key, int order) {
    return switch (key) {
      case "TU_DANH_GIA" -> "Tự đánh giá";
      case "THAM_DINH" -> "Thẩm định";
      case "DE_XUAT" -> "Đề xuất";
      case "QUYET_DINH" -> "Quyết định";
      default -> "Phê duyệt cấp " + order;
    };
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
