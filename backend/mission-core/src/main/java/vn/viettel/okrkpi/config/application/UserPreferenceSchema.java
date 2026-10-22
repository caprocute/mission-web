package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/**
 * Kiểm giá trị tuỳ chọn cá nhân và tiêu chí bộ lọc đã lưu theo {@code
 * contracts/schemas/user-preference.schema.json} (CNH-02). Viết tay như {@link UiPackSchema} vì quy
 * tắc 5 cấm thư viện sinh mã lúc chạy; mọi trường lạ bị từ chối, nên văn bản tự do (nguy cơ chứa dữ
 * liệu C2) không lọt vào cột C1. Lỗi trả {@code COMMON-422-VALIDATION} kèm danh sách theo trường.
 */
final class UserPreferenceSchema {

  static final Pattern WIDGET_ID = Pattern.compile("^[a-z][a-zA-Z0-9._-]{1,63}$");
  private static final Pattern COLUMN_KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");
  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
  private static final Pattern KPI_CODE = Pattern.compile("^[A-Z0-9_-]{2,64}$");
  private static final Pattern STATUS_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
  private static final Pattern HH_MM = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
  private static final Set<String> RATINGS =
      Set.of(
          "KHTNV",
          "HTNV",
          "HTTNV",
          "HTXSNV",
          "KHONG_HOAN_THANH",
          "HOAN_THANH",
          "HOAN_THANH_TOT",
          "HOAN_THANH_XUAT_SAC");
  private static final BigDecimal FONT_STEP = new BigDecimal("0.05");

  private final List<ProblemFieldError> errors = new ArrayList<>();

  private UserPreferenceSchema() {}

  /** Kiểm giá trị của khoá {@code prefKey} (khoá đã khớp mẫu). */
  static void requireValidPreference(String prefKey, JsonNode value) {
    UserPreferenceSchema s = new UserPreferenceSchema();
    String root = "value";
    if (prefKey.equals("ui.display")) {
      s.display(root, value);
    } else if (prefKey.equals("ui.home.layout")) {
      s.homeLayout(root, value);
    } else if (prefKey.equals("ui.dashboard.default")) {
      s.dashboard(root, value);
    } else if (prefKey.startsWith("ui.table.")) {
      s.table(root, value);
    } else {
      s.notify(root, value);
    }
    s.raise("Giá trị tuỳ chọn không đúng định dạng cho phép.");
  }

  /** Kiểm tiêu chí bộ lọc đã lưu ({@code $defs/SavedFilterCriteria}). */
  static void requireValidCriteria(JsonNode criteria) {
    UserPreferenceSchema s = new UserPreferenceSchema();
    s.criteria("criteria", criteria);
    s.raise("Điều kiện lọc chỉ được gồm mã, mã định danh và khoảng giá trị.");
  }

  private void raise(String detail) {
    if (!errors.isEmpty()) {
      throw new BusinessException("COMMON-422-VALIDATION", detail, errors);
    }
  }

  // ------------------------------------------------------------------ $defs

  private void display(String p, JsonNode n) {
    if (!object(
        p,
        n,
        Set.of("mode", "reducedSteps", "density", "fontScale", "highContrastStatus"),
        Set.of("mode", "reducedSteps"))) {
      return;
    }
    oneOf(p + ".mode", n.get("mode"), Set.of("default", "elderly"));
    bool(p + ".reducedSteps", n.get("reducedSteps"));
    oneOf(p + ".density", n.get("density"), Set.of("comfortable", "compact"));
    JsonNode scale = n.get("fontScale");
    if (scale != null) {
      if (!scale.isNumber()) {
        fail(p + ".fontScale", "Phải là số.");
      } else {
        BigDecimal v = new BigDecimal(scale.asText());
        if (v.compareTo(BigDecimal.ONE) < 0
            || v.compareTo(new BigDecimal("1.5")) > 0
            || v.remainder(FONT_STEP).signum() != 0) {
          fail(p + ".fontScale", "Hệ số phóng chữ từ 1,0 đến 1,5, bước 0,05.");
        }
      }
    }
    bool(p + ".highContrastStatus", n.get("highContrastStatus"));
  }

  private void homeLayout(String p, JsonNode n) {
    if (!object(
        p,
        n,
        Set.of(
            "baseUiPackVersion",
            "pinnedWidgets",
            "hiddenWidgets",
            "widgetOrder",
            "collapsedSections"),
        Set.of())) {
      return;
    }
    JsonNode base = n.get("baseUiPackVersion");
    if (base != null && (!base.isTextual() || base.asText().length() > 64)) {
      fail(p + ".baseUiPackVersion", "Phiên bản tối đa 64 ký tự.");
    }
    array(p + ".pinnedWidgets", n.get("pinnedWidgets"), 0, 6, true, this::widgetId);
    array(p + ".hiddenWidgets", n.get("hiddenWidgets"), 0, 20, true, this::widgetId);
    array(p + ".collapsedSections", n.get("collapsedSections"), 0, 10, true, this::widgetId);
    array(
        p + ".widgetOrder",
        n.get("widgetOrder"),
        0,
        30,
        false,
        (ip, item) -> {
          if (object(ip, item, Set.of("id", "order", "colSpan"), Set.of("id", "order"))) {
            widgetId(ip + ".id", item.get("id"));
            intRange(ip + ".order", item.get("order"), 0, 50);
            intIn(ip + ".colSpan", item.get("colSpan"), Set.of(4, 6, 8, 12));
          }
        });
  }

  private void table(String p, JsonNode n) {
    if (!object(
        p,
        n,
        Set.of(
            "visibleColumns",
            "columnOrder",
            "columnWidths",
            "pageSize",
            "defaultSort",
            "defaultSavedFilterId"),
        Set.of())) {
      return;
    }
    array(p + ".visibleColumns", n.get("visibleColumns"), 1, 40, true, this::columnKey);
    array(p + ".columnOrder", n.get("columnOrder"), 0, 40, true, this::columnKey);
    map(p + ".columnWidths", n.get("columnWidths"), 40, (ip, v) -> intRange(ip, v, 60, 800));
    intIn(p + ".pageSize", n.get("pageSize"), Set.of(10, 20, 50, 100, 200));
    sort(p + ".defaultSort", n.get("defaultSort"));
    uuid(p + ".defaultSavedFilterId", n.get("defaultSavedFilterId"));
  }

  private void dashboard(String p, JsonNode n) {
    if (!object(
        p, n, Set.of("defaultDashboardId", "pinnedKpiCodes", "defaultPeriodType"), Set.of())) {
      return;
    }
    uuid(p + ".defaultDashboardId", n.get("defaultDashboardId"));
    array(
        p + ".pinnedKpiCodes",
        n.get("pinnedKpiCodes"),
        0,
        10,
        true,
        (ip, v) -> pattern(ip, v, KPI_CODE));
    oneOf(p + ".defaultPeriodType", n.get("defaultPeriodType"), Set.of("THANG", "QUY", "NAM"));
  }

  private void notify(String p, JsonNode n) {
    if (!object(p, n, Set.of("quietHours", "digestMode"), Set.of())) {
      return;
    }
    JsonNode quiet = n.get("quietHours");
    if (quiet != null
        && object(
            p + ".quietHours",
            quiet,
            Set.of("enabled", "start", "end"),
            Set.of("enabled", "start", "end"))) {
      bool(p + ".quietHours.enabled", quiet.get("enabled"));
      pattern(p + ".quietHours.start", quiet.get("start"), HH_MM);
      pattern(p + ".quietHours.end", quiet.get("end"), HH_MM);
    }
    oneOf(p + ".digestMode", n.get("digestMode"), Set.of("IMMEDIATE", "DAILY_SUMMARY"));
  }

  private void criteria(String p, JsonNode n) {
    if (!object(
        p,
        n,
        Set.of(
            "statusCodes",
            "ratingCodes",
            "orgUnitIds",
            "periodIds",
            "assigneeScope",
            "dateRanges",
            "numberRanges",
            "flags",
            "sort"),
        Set.of())) {
      return;
    }
    array(
        p + ".statusCodes",
        n.get("statusCodes"),
        0,
        20,
        true,
        (ip, v) -> pattern(ip, v, STATUS_CODE));
    array(p + ".ratingCodes", n.get("ratingCodes"), 0, 8, true, (ip, v) -> oneOf(ip, v, RATINGS));
    array(p + ".orgUnitIds", n.get("orgUnitIds"), 0, 50, true, this::uuid);
    array(p + ".periodIds", n.get("periodIds"), 0, 12, true, this::uuid);
    oneOf(
        p + ".assigneeScope",
        n.get("assigneeScope"),
        Set.of("ME", "MY_UNIT", "SUBORDINATE_UNITS", "ALL_ALLOWED"));
    map(
        p + ".dateRanges",
        n.get("dateRanges"),
        10,
        (ip, v) -> {
          if (object(ip, v, Set.of("from", "to", "relativePreset"), Set.of())) {
            date(ip + ".from", v.get("from"));
            date(ip + ".to", v.get("to"));
            oneOf(
                ip + ".relativePreset",
                v.get("relativePreset"),
                Set.of("TODAY", "THIS_WEEK", "THIS_MONTH", "THIS_QUARTER", "THIS_YEAR", "OVERDUE"));
          }
        });
    map(
        p + ".numberRanges",
        n.get("numberRanges"),
        10,
        (ip, v) -> {
          if (object(ip, v, Set.of("min", "max"), Set.of())) {
            number(ip + ".min", v.get("min"));
            number(ip + ".max", v.get("max"));
          }
        });
    map(p + ".flags", n.get("flags"), 15, this::bool);
    sort(p + ".sort", n.get("sort"));
  }

  // ------------------------------------------------------------------ Kiểu cơ bản

  private void sort(String p, JsonNode n) {
    if (n != null && object(p, n, Set.of("field", "direction"), Set.of("field", "direction"))) {
      columnKey(p + ".field", n.get("field"));
      oneOf(p + ".direction", n.get("direction"), Set.of("ASC", "DESC"));
    }
  }

  private boolean object(String p, JsonNode n, Set<String> allowed, Set<String> required) {
    if (n == null || !n.isObject()) {
      fail(p, "Phải là đối tượng.");
      return false;
    }
    for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
      String f = it.next();
      if (!allowed.contains(f)) {
        fail(p + "." + f, "Trường không được phép.");
      }
    }
    for (String r : required) {
      if (!n.hasNonNull(r)) {
        fail(p + "." + r, "Thiếu trường bắt buộc.");
      }
    }
    return true;
  }

  private void array(
      String p,
      JsonNode n,
      int minItems,
      int maxItems,
      boolean unique,
      BiConsumer<String, JsonNode> item) {
    if (n == null) {
      return;
    }
    if (!n.isArray()) {
      fail(p, "Phải là danh sách.");
      return;
    }
    if (n.size() < minItems || n.size() > maxItems) {
      fail(p, "Số phần tử từ " + minItems + " đến " + maxItems + ".");
    }
    Set<JsonNode> seen = new HashSet<>();
    for (int i = 0; i < n.size(); i++) {
      if (unique && !seen.add(n.get(i))) {
        fail(p + "[" + i + "]", "Phần tử trùng.");
      }
      item.accept(p + "[" + i + "]", n.get(i));
    }
  }

  private void map(String p, JsonNode n, int maxProps, BiConsumer<String, JsonNode> value) {
    if (n == null) {
      return;
    }
    if (!n.isObject()) {
      fail(p, "Phải là đối tượng.");
      return;
    }
    if (n.size() > maxProps) {
      fail(p, "Tối đa " + maxProps + " mục.");
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      if (!COLUMN_KEY.matcher(e.getKey()).matches()) {
        fail(p + "." + e.getKey(), "Mã cột không hợp lệ.");
      }
      value.accept(p + "." + e.getKey(), e.getValue());
    }
  }

  private void widgetId(String p, JsonNode n) {
    pattern(p, n, WIDGET_ID);
  }

  private void columnKey(String p, JsonNode n) {
    pattern(p, n, COLUMN_KEY);
  }

  private void uuid(String p, JsonNode n) {
    if (n != null) {
      pattern(p, n, UUID_TEXT);
    }
  }

  private void pattern(String p, JsonNode n, Pattern pattern) {
    if (n == null || !n.isTextual() || !pattern.matcher(n.asText()).matches()) {
      fail(p, "Giá trị không hợp lệ.");
    }
  }

  private void oneOf(String p, JsonNode n, Set<String> values) {
    if (n != null && (!n.isTextual() || !values.contains(n.asText()))) {
      fail(p, "Giá trị không nằm trong danh sách cho phép.");
    }
  }

  private void bool(String p, JsonNode n) {
    if (n != null && !n.isBoolean()) {
      fail(p, "Phải là đúng hoặc sai.");
    }
  }

  private void number(String p, JsonNode n) {
    if (n != null && !n.isNumber()) {
      fail(p, "Phải là số.");
    }
  }

  private void intRange(String p, JsonNode n, int min, int max) {
    if (n != null && (!n.isIntegralNumber() || n.asLong() < min || n.asLong() > max)) {
      fail(p, "Phải là số nguyên từ " + min + " đến " + max + ".");
    }
  }

  private void intIn(String p, JsonNode n, Set<Integer> values) {
    if (n != null && (!n.isIntegralNumber() || !values.contains(n.asInt()))) {
      fail(p, "Giá trị không nằm trong danh sách cho phép.");
    }
  }

  private void date(String p, JsonNode n) {
    if (n == null) {
      return;
    }
    try {
      if (!n.isTextual()) {
        throw new DateTimeParseException("", "", 0);
      }
      LocalDate.parse(n.asText());
    } catch (DateTimeParseException e) {
      fail(p, "Ngày phải theo dạng YYYY-MM-DD.");
    }
  }

  private void fail(String field, String message) {
    errors.add(new ProblemFieldError(field, message, null));
  }
}
