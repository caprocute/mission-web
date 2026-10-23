package vn.viettel.okrkpi.integ.infrastructure.hrm;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import vn.viettel.okrkpi.integ.infrastructure.hrm.HrmProperties.Profile;

/**
 * Ánh xạ trường theo hồ sơ kết nối và kiểm từng dòng nhân sự (DV-13-08): mỗi lỗi ghi số dòng và
 * nhãn trường, không ghi giá trị vì số định danh, ngày sinh là C2.
 */
final class PersonRows {

  static final List<String> FIELDS =
      List.of(
          "externalId",
          "fullName",
          "nationalId",
          "birthDate",
          "employmentStatus",
          "partyMember",
          "orgUnit",
          "jobTitle",
          "kind",
          "head",
          "effectiveFrom");

  static final Map<String, String> LABELS =
      Map.ofEntries(
          Map.entry("externalId", "mã cán bộ"),
          Map.entry("fullName", "họ tên"),
          Map.entry("nationalId", "số định danh"),
          Map.entry("birthDate", "ngày sinh"),
          Map.entry("employmentStatus", "trạng thái công tác"),
          Map.entry("partyMember", "đảng viên"),
          Map.entry("orgUnit", "đơn vị"),
          Map.entry("jobTitle", "chức danh"),
          Map.entry("kind", "loại phân công"),
          Map.entry("head", "thủ trưởng"),
          Map.entry("effectiveFrom", "ngày hiệu lực"));

  static final Set<String> STATUSES = Set.of("DANG_CONG_TAC", "NGHI_HUU", "THOI_VIEC", "TAM_NGHI");
  static final Set<String> KINDS =
      Set.of("CHINH", "KIEM_NHIEM", "DIEU_DONG", "BIET_PHAI", "TAM_GIU");

  private static final DateTimeFormatter VN =
      DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);
  private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

  /** Một người ở nguồn, mã đơn vị và chức danh còn là mã ngoài. */
  record Row(
      int line,
      String externalId,
      String fullName,
      String nationalId,
      LocalDate birthDate,
      String employmentStatus,
      boolean partyMember,
      String orgUnit,
      String jobTitle,
      String kind,
      boolean head,
      LocalDate effectiveFrom) {

    @Override
    public String toString() {
      return "Row[dòng " + line + ", " + externalId + "]";
    }
  }

  /** Dòng đầu vào: số dòng, ô theo tên trường chuẩn, có ô thừa ngoài tiêu đề. */
  record Input(int line, Map<String, String> cells, boolean ragged) {}

  record Result(List<Row> rows, Map<Integer, List<String>> errors) {}

  private final Map<String, String> fields = new HashMap<>();
  private final Map<String, Map<String, String>> values = new HashMap<>();
  private final List<String> required = new ArrayList<>();

  PersonRows(Profile profile) {
    // Khoá cấu hình so khớp không phân biệt hoa thường và gạch nối (externalId, external-id).
    profile.fields().forEach((k, v) -> fields.put(canon(k), v));
    profile.values().forEach((k, v) -> values.put(canon(k), v));
    Set<String> wanted = new HashSet<>();
    profile.required().forEach(r -> wanted.add(canon(r)));
    for (String f : FIELDS) {
      if (wanted.contains(canon(f))) {
        required.add(f);
      }
    }
  }

  static String canon(String key) {
    return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
  }

  private String source(String field, String fallback) {
    return fields.getOrDefault(canon(field), fallback);
  }

  /** Tên cột của trường chuẩn trong tệp lô. */
  String columnOf(String field) {
    return BatchFiles.column(source(field, field));
  }

  /** Nhãn các trường bắt buộc mà tiêu đề tệp thiếu cột. */
  List<String> missingColumns(List<String> header) {
    List<String> missing = new ArrayList<>();
    for (String f : required) {
      if (!header.contains(columnOf(f))) {
        missing.add(source(f, f));
      }
    }
    return missing;
  }

  Input fromTable(BatchFiles.RawRow r) {
    Map<String, String> cells = new HashMap<>();
    for (String f : FIELDS) {
      String v = r.cells().get(columnOf(f));
      if (v != null) {
        cells.put(f, v);
      }
    }
    return new Input(r.line(), cells, r.ragged());
  }

  Input fromJson(int line, JsonNode item) {
    Map<String, String> cells = new HashMap<>();
    for (String f : FIELDS) {
      String src = source(f, "/" + f);
      JsonNode v = item.at(src.startsWith("/") ? src : "/" + src);
      if (!v.isMissingNode() && !v.isNull()) {
        cells.put(f, v.isValueNode() ? v.asText() : v.toString());
      }
    }
    return new Input(line, cells, false);
  }

  Result map(List<Input> inputs) {
    List<Row> rows = new ArrayList<>();
    Map<Integer, List<String>> errors = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    for (Input in : inputs) {
      List<String> bad = new ArrayList<>();
      if (in.ragged()) {
        bad.add("thừa cột");
      }
      for (String f : required) {
        if (blank(in.cells().get(f))) {
          bad.add("thiếu " + LABELS.getOrDefault(f, f));
        }
      }
      String externalId = text(in, "externalId", 128, bad);
      String fullName = text(in, "fullName", 255, bad);
      String nationalId = text(in, "nationalId", 32, bad);
      LocalDate birth = date(in, "birthDate", bad);
      String status = code(in, "employmentStatus", STATUSES, "DANG_CONG_TAC", bad);
      boolean party = bool(in, "partyMember", bad);
      String unit = text(in, "orgUnit", 128, bad);
      String title = text(in, "jobTitle", 128, bad);
      String kind = code(in, "kind", KINDS, "CHINH", bad);
      boolean head = bool(in, "head", bad);
      LocalDate from = date(in, "effectiveFrom", bad);
      if (unit != null && title == null) {
        bad.add("thiếu chức danh");
      }
      if (externalId != null && !seen.add(externalId)) {
        bad.add("trùng mã cán bộ");
      }
      if (bad.isEmpty()) {
        rows.add(
            new Row(
                in.line(),
                externalId,
                fullName,
                nationalId,
                birth,
                status,
                party,
                unit,
                title,
                kind,
                head,
                from));
      } else {
        errors.put(in.line(), bad);
      }
    }
    return new Result(rows, errors);
  }

  /** "dòng 3 (thiếu ngày sinh); dòng 7 (...)" cắt ở {@code max} ký tự. */
  static String describe(Map<Integer, List<String>> errors, int max) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<Integer, List<String>> e : errors.entrySet()) {
      String part = "dòng " + e.getKey() + " (" + String.join(", ", e.getValue()) + ")";
      if (sb.length() + part.length() + 2 > max) {
        sb.append("; …");
        break;
      }
      if (!sb.isEmpty()) {
        sb.append("; ");
      }
      sb.append(part);
    }
    return sb.toString();
  }

  private String mapped(String field, String raw) {
    Map<String, String> table = values.get(canon(field));
    if (table == null) {
      return null;
    }
    for (Map.Entry<String, String> e : table.entrySet()) {
      if (e.getKey().strip().equalsIgnoreCase(raw.strip())) {
        return e.getValue();
      }
    }
    return null;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String text(Input in, String field, int max, List<String> bad) {
    String v = in.cells().get(field);
    if (blank(v)) {
      return null;
    }
    v = v.strip();
    if (v.length() > max) {
      bad.add(LABELS.get(field) + " quá dài");
      return null;
    }
    return v;
  }

  private String code(
      Input in, String field, Set<String> allowed, String fallback, List<String> bad) {
    String v = in.cells().get(field);
    if (blank(v)) {
      return fallback;
    }
    String m = mapped(field, v);
    String c = m != null ? m : v.strip().toUpperCase(Locale.ROOT);
    if (!allowed.contains(c)) {
      bad.add(LABELS.get(field) + " không hợp lệ");
      return null;
    }
    return c;
  }

  private boolean bool(Input in, String field, List<String> bad) {
    String v = in.cells().get(field);
    if (blank(v)) {
      return false;
    }
    String m = mapped(field, v);
    String s = (m != null ? m : v).strip().toLowerCase(Locale.ROOT);
    return switch (s) {
      case "true", "1", "x", "có", "co", "yes" -> true;
      case "false", "0", "không", "khong", "no" -> false;
      default -> {
        bad.add(LABELS.get(field) + " không hợp lệ");
        yield false;
      }
    };
  }

  private static LocalDate date(Input in, String field, List<String> bad) {
    String v = in.cells().get(field);
    if (blank(v)) {
      return null;
    }
    v = v.strip();
    try {
      if (v.matches("\\d{1,5}")) {
        long serial = Long.parseLong(v);
        if (serial >= 1 && serial <= 80_000) {
          return EXCEL_EPOCH.plusDays(serial);
        }
      } else if (v.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
        return LocalDate.parse(v.substring(0, 10));
      } else if (v.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
        return LocalDate.parse(v, VN);
      }
    } catch (DateTimeParseException e) {
      // rơi xuống báo lỗi bên dưới
    }
    bad.add(LABELS.get(field) + " không hợp lệ");
    return null;
  }
}
