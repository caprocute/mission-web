package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import vn.viettel.okrkpi.cel.application.CelCompiledProgram;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.config.api.FormValidationApi.Violation;

/**
 * Lược đồ biểu mẫu đã biên dịch một lần để kiểm dữ liệu phiếu (CORE-08-14, ADR-05 lớp 3): cây nút
 * giữ sẵn mẫu {@link Pattern} và chương trình CEL, không sinh mã lúc chạy. Hiểu bộ từ khoá JSON
 * Schema mà trình dựng SCR-1802 sinh ra: {@code type}, {@code enum}, {@code const}, {@code
 * minimum}, {@code maximum}, {@code exclusiveMinimum}, {@code exclusiveMaximum}, {@code
 * multipleOf}, {@code minLength}, {@code maxLength}, {@code pattern}, {@code format} (date,
 * date-time, email), {@code properties}, {@code required}, {@code additionalProperties: false},
 * {@code items}, {@code minItems}, {@code maxItems}; cùng {@code x-cel} (biến {@code self}, {@code
 * form}) và {@code celRules}.
 *
 * <p>Biến {@code form} là bảng phẳng khoá dữ liệu → giá trị (khoá duy nhất trong biểu mẫu theo
 * CORE-08-13); số theo kiểu khai báo: {@code number} thành double, {@code integer} thành int, khớp
 * cel-js. {@code x-cel} và quy tắc chỉ chạy khi trường có giá trị.
 */
public final class CompiledForm {

  static final String DEFAULT_MESSAGE = "Giá trị không hợp lệ";
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  private final CelEngine cel;
  private final Node root;
  private final List<Rule> rules = new ArrayList<>();
  private final Map<String, Node> byKey = new LinkedHashMap<>();
  private final Set<String> c2 = new LinkedHashSet<>();

  private record Rule(String field, CelCompiledProgram program, String message) {}

  private static final class Node {
    final String key;
    final String path;
    Set<String> types = Set.of();
    String primary = "";
    List<JsonNode> enums;
    JsonNode constant;
    BigDecimal minimum;
    BigDecimal maximum;
    BigDecimal exclusiveMinimum;
    BigDecimal exclusiveMaximum;
    BigDecimal multipleOf;
    Integer minLength;
    Integer maxLength;
    Integer minItems;
    Integer maxItems;
    Pattern pattern;
    String format;
    final Map<String, Node> properties = new LinkedHashMap<>();
    List<String> required = List.of();
    boolean closed;
    Node items;
    CelCompiledProgram xcel;
    String celMessage = DEFAULT_MESSAGE;

    Node(String key, String path) {
      this.key = key;
      this.path = path;
    }
  }

  /**
   * @throws IllegalArgumentException khi lược đồ hay biểu thức không biên dịch được (phiên bản đã
   *     qua kiểm của CORE-08-13 thì không xảy ra)
   */
  public CompiledForm(CelEngine cel, JsonNode schema, JsonNode celRules) {
    this.cel = cel;
    this.root = node(schema, null, "");
    if (celRules != null && celRules.isArray()) {
      for (JsonNode r : celRules) {
        String field = r.path("field").asText();
        rules.add(
            new Rule(
                field,
                compile(r.path("expr").asText(), null),
                r.path("message").asText(DEFAULT_MESSAGE)));
      }
    }
  }

  /** Khoá dữ liệu lớp C2 (BR-18-11) để module mã hoá khi lưu. */
  public Set<String> c2Fields() {
    return Collections.unmodifiableSet(c2);
  }

  /** Số quy tắc CEL (x-cel và celRules) đã biên dịch. */
  public int celCount() {
    int n = rules.size();
    for (Node f : byKey.values()) {
      n += f.xcel == null ? 0 : 1;
    }
    return n + (root.xcel == null ? 0 : 1);
  }

  /** Kiểm một phiếu; lỗi theo thứ tự trường trong lược đồ. */
  public List<Violation> check(JsonNode payload) {
    List<Violation> errors = new ArrayList<>();
    if (payload == null || !payload.isObject()) {
      errors.add(new Violation("", "Dữ liệu biểu mẫu phải là một đối tượng", null));
      return errors;
    }
    Map<String, Object> form = new HashMap<>();
    flatten(root, payload, form);
    check(root, payload, form, errors);
    for (Rule r : rules) {
      Node field = byKey.get(r.field());
      if (form.get(r.field()) == null || field == null) {
        continue;
      }
      if (!truthy(r.program(), Map.of("form", form))) {
        errors.add(new Violation(field.path, r.message(), null));
      }
    }
    return errors;
  }

  private Node node(JsonNode s, String key, String path) {
    Node n = new Node(key, path);
    JsonNode type = s.get("type");
    if (type != null) {
      Set<String> types = new LinkedHashSet<>();
      if (type.isArray()) {
        type.forEach(t -> types.add(t.asText()));
      } else {
        types.add(type.asText());
      }
      n.types = types;
      n.primary = types.stream().filter(t -> !"null".equals(t)).findFirst().orElse("");
    }
    if (s.has("enum")) {
      n.enums = new ArrayList<>();
      s.get("enum").forEach(n.enums::add);
    }
    n.constant = s.get("const");
    n.minimum = decimal(s, "minimum");
    n.maximum = decimal(s, "maximum");
    n.exclusiveMinimum = decimal(s, "exclusiveMinimum");
    n.exclusiveMaximum = decimal(s, "exclusiveMaximum");
    n.multipleOf = decimal(s, "multipleOf");
    n.minLength = count(s, "minLength");
    n.maxLength = count(s, "maxLength");
    n.minItems = count(s, "minItems");
    n.maxItems = count(s, "maxItems");
    if (s.has("pattern")) {
      try {
        n.pattern = Pattern.compile(s.get("pattern").asText());
      } catch (PatternSyntaxException e) {
        throw new IllegalArgumentException("Mẫu kiểm tra không hợp lệ ở " + path, e);
      }
    }
    n.format = s.path("format").asText(null);
    if (s.has("required") && s.get("required").isArray()) {
      List<String> req = new ArrayList<>();
      s.get("required").forEach(r -> req.add(r.asText()));
      n.required = req;
    }
    n.closed =
        s.path("additionalProperties").isBoolean() && !s.get("additionalProperties").asBoolean();
    if (s.has("x-cel-message")) {
      n.celMessage = s.get("x-cel-message").asText(DEFAULT_MESSAGE);
    }
    if (s.has("x-cel")) {
      n.xcel = compile(s.get("x-cel").asText(), selfType(n.primary));
    }
    if (key != null && "C2".equals(s.path("x-classification").asText())) {
      c2.add(key);
    }
    if (key != null) {
      byKey.put(key, n);
    }
    JsonNode props = s.path("properties");
    for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> p = it.next();
      String at = path.isEmpty() ? p.getKey() : path + "." + p.getKey();
      n.properties.put(p.getKey(), node(p.getValue(), p.getKey(), at));
    }
    if (s.path("items").isObject()) {
      n.items = itemNode(s.get("items"), path);
    }
    return n;
  }

  /** Phần tử mảng: không có khoá dữ liệu riêng, lỗi mang chỉ số. */
  private Node itemNode(JsonNode s, String path) {
    Map<String, Node> before = new LinkedHashMap<>(byKey);
    Node n = node(s, null, path);
    // Khoá con trong phần tử mảng không vào bảng phẳng: giá trị nằm theo từng phần tử.
    byKey.keySet().retainAll(before.keySet());
    byKey.putAll(before);
    return n;
  }

  private CelCompiledProgram compile(String expr, CelValueType self) {
    Map<String, CelValueType> vars = new HashMap<>();
    vars.put("form", CelValueType.MAP_STRING_DYN);
    if (self != null) {
      vars.put("self", self);
    }
    try {
      return cel.compile(expr, vars, CelValueType.BOOL);
    } catch (CelCompilationException e) {
      throw new IllegalArgumentException("Biểu thức CEL không hợp lệ: " + expr, e);
    }
  }

  private void flatten(Node n, JsonNode value, Map<String, Object> form) {
    for (Map.Entry<String, Node> p : n.properties.entrySet()) {
      JsonNode v = value.get(p.getKey());
      if (v == null || v.isNull()) {
        continue;
      }
      form.put(p.getKey(), toJava(v, p.getValue()));
      if (v.isObject()) {
        flatten(p.getValue(), v, form);
      }
    }
  }

  private void check(Node n, JsonNode v, Map<String, Object> form, List<Violation> errors) {
    if (!n.types.isEmpty() && n.types.stream().noneMatch(t -> matches(t, v))) {
      errors.add(new Violation(n.path, typeMessage(n.primary), null));
      return;
    }
    if (n.enums != null && n.enums.stream().noneMatch(e -> same(e, v))) {
      errors.add(new Violation(n.path, "Giá trị không nằm trong danh sách cho phép", null));
    }
    if (n.constant != null && !same(n.constant, v)) {
      errors.add(new Violation(n.path, DEFAULT_MESSAGE, null));
    }
    if (v.isNumber()) {
      numeric(n, v.decimalValue(), errors);
    }
    if (v.isTextual()) {
      text(n, v.asText(), errors);
    }
    if (v.isArray()) {
      if (n.minItems != null && v.size() < n.minItems) {
        errors.add(new Violation(n.path, "Cần ít nhất " + n.minItems + " mục", null));
      }
      if (n.maxItems != null && v.size() > n.maxItems) {
        errors.add(new Violation(n.path, "Tối đa " + n.maxItems + " mục", null));
      }
      if (n.items != null) {
        for (int i = 0; i < v.size(); i++) {
          JsonNode item = v.get(i);
          if (!item.isNull()) {
            check(indexed(n.items, n.path + "." + i), item, form, errors);
          }
        }
      }
    }
    if (v.isObject()) {
      for (String r : n.required) {
        JsonNode child = v.get(r);
        if (child == null || child.isNull() || (child.isTextual() && child.asText().isBlank())) {
          Node c = n.properties.get(r);
          errors.add(new Violation(c == null ? join(n.path, r) : c.path, "Trường bắt buộc", null));
        }
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        Node c = n.properties.get(e.getKey());
        if (c == null) {
          if (n.closed) {
            errors.add(
                new Violation(join(n.path, e.getKey()), "Trường không có trong biểu mẫu", null));
          }
        } else if (!e.getValue().isNull()) {
          check(c, e.getValue(), form, errors);
        }
      }
    }
    if (n.xcel != null) {
      Map<String, Object> vars = new HashMap<>();
      vars.put("form", form);
      vars.put("self", toJava(v, n));
      if (!truthy(n.xcel, vars)) {
        errors.add(new Violation(n.path, n.celMessage, null));
      }
    }
  }

  /** Nút phần tử mảng với đường dẫn có chỉ số; dùng lại cấu hình, đổi đường dẫn của cây con. */
  private Node indexed(Node item, String path) {
    if (path.equals(item.path)) {
      return item;
    }
    Node copy = new Node(item.key, path);
    copy.types = item.types;
    copy.primary = item.primary;
    copy.enums = item.enums;
    copy.constant = item.constant;
    copy.minimum = item.minimum;
    copy.maximum = item.maximum;
    copy.exclusiveMinimum = item.exclusiveMinimum;
    copy.exclusiveMaximum = item.exclusiveMaximum;
    copy.multipleOf = item.multipleOf;
    copy.minLength = item.minLength;
    copy.maxLength = item.maxLength;
    copy.minItems = item.minItems;
    copy.maxItems = item.maxItems;
    copy.pattern = item.pattern;
    copy.format = item.format;
    copy.required = item.required;
    copy.closed = item.closed;
    copy.items = item.items;
    copy.xcel = item.xcel;
    copy.celMessage = item.celMessage;
    for (Map.Entry<String, Node> p : item.properties.entrySet()) {
      copy.properties.put(p.getKey(), indexed(p.getValue(), join(path, p.getKey())));
    }
    return copy;
  }

  private boolean truthy(CelCompiledProgram program, Map<String, Object> vars) {
    try {
      return Boolean.TRUE.equals(cel.evaluate(program, vars));
    } catch (RuntimeException e) {
      // Biểu thức lỗi khi chạy (thiếu khoá, chia cho 0...) coi như không đạt: máy chủ là kết luận.
      return false;
    }
  }

  private static void numeric(Node n, BigDecimal d, List<Violation> errors) {
    boolean low =
        (n.minimum != null && d.compareTo(n.minimum) < 0)
            || (n.exclusiveMinimum != null && d.compareTo(n.exclusiveMinimum) <= 0);
    boolean high =
        (n.maximum != null && d.compareTo(n.maximum) > 0)
            || (n.exclusiveMaximum != null && d.compareTo(n.exclusiveMaximum) >= 0);
    if (low || high) {
      errors.add(new Violation(n.path, rangeMessage(n), null));
    }
    if (n.multipleOf != null
        && n.multipleOf.signum() > 0
        && d.remainder(n.multipleOf).signum() != 0) {
      errors.add(new Violation(n.path, "Giá trị phải là bội số của " + plain(n.multipleOf), null));
    }
  }

  private static void text(Node n, String s, List<Violation> errors) {
    int len = s.codePointCount(0, s.length());
    if (n.minLength != null && len < n.minLength) {
      errors.add(new Violation(n.path, "Cần ít nhất " + n.minLength + " ký tự", null));
    }
    if (n.maxLength != null && len > n.maxLength) {
      errors.add(new Violation(n.path, "Tối đa " + n.maxLength + " ký tự", null));
    }
    if (n.pattern != null && !n.pattern.matcher(s).find()) {
      errors.add(new Violation(n.path, "Giá trị chưa đúng định dạng", null));
    }
    if (n.format != null && !format(n.format, s)) {
      errors.add(new Violation(n.path, "Giá trị chưa đúng định dạng", null));
    }
  }

  private static boolean format(String format, String s) {
    try {
      return switch (format) {
        case "date" -> LocalDate.parse(s) != null;
        case "date-time" -> OffsetDateTime.parse(s) != null;
        case "email" -> EMAIL.matcher(s).matches();
        default -> true;
      };
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static String rangeMessage(Node n) {
    BigDecimal lo = n.minimum != null ? n.minimum : n.exclusiveMinimum;
    BigDecimal hi = n.maximum != null ? n.maximum : n.exclusiveMaximum;
    if (lo != null && hi != null) {
      return "Giá trị phải từ " + plain(lo) + " đến " + plain(hi);
    }
    if (lo != null) {
      return (n.minimum != null ? "Giá trị phải từ " : "Giá trị phải lớn hơn ") + plain(lo);
    }
    return (n.maximum != null ? "Giá trị tối đa là " : "Giá trị phải nhỏ hơn ") + plain(hi);
  }

  private static String typeMessage(String type) {
    return switch (type) {
      case "number" -> "Giá trị phải là số";
      case "integer" -> "Giá trị phải là số nguyên";
      case "string" -> "Giá trị phải là văn bản";
      case "boolean" -> "Giá trị phải là có hoặc không";
      case "array" -> "Giá trị phải là danh sách";
      case "object" -> "Giá trị phải là một nhóm trường";
      default -> DEFAULT_MESSAGE;
    };
  }

  private static boolean matches(String type, JsonNode v) {
    return switch (type) {
      case "object" -> v.isObject();
      case "array" -> v.isArray();
      case "string" -> v.isTextual();
      case "number" -> v.isNumber();
      case "integer" ->
          v.isIntegralNumber()
              || (v.isNumber() && v.decimalValue().stripTrailingZeros().scale() <= 0);
      case "boolean" -> v.isBoolean();
      case "null" -> v.isNull();
      default -> true;
    };
  }

  private static boolean same(JsonNode a, JsonNode b) {
    if (a.isNumber() && b.isNumber()) {
      return a.decimalValue().compareTo(b.decimalValue()) == 0;
    }
    return a.equals(b);
  }

  /** Giá trị Java cho CEL theo kiểu khai báo. */
  private static Object toJava(JsonNode v, Node n) {
    String type = n == null ? "" : n.primary;
    if (v.isNumber()) {
      return switch (type) {
        case "number" -> v.doubleValue();
        case "integer" -> v.longValue();
        default -> v.isIntegralNumber() ? (Object) v.longValue() : (Object) v.doubleValue();
      };
    }
    if (v.isTextual()) {
      return v.asText();
    }
    if (v.isBoolean()) {
      return v.asBoolean();
    }
    if (v.isArray()) {
      List<Object> out = new ArrayList<>();
      for (JsonNode x : v) {
        if (!x.isNull()) {
          out.add(toJava(x, n.items));
        }
      }
      return out;
    }
    if (v.isObject()) {
      Map<String, Object> out = new HashMap<>();
      for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        if (!e.getValue().isNull()) {
          out.put(
              e.getKey(), toJava(e.getValue(), n == null ? null : n.properties.get(e.getKey())));
        }
      }
      return out;
    }
    return null;
  }

  private static CelValueType selfType(String type) {
    return switch (type) {
      case "number" -> CelValueType.DOUBLE;
      case "integer" -> CelValueType.INT;
      case "string" -> CelValueType.STRING;
      case "boolean" -> CelValueType.BOOL;
      case "array" -> CelValueType.LIST_DYN;
      case "object" -> CelValueType.MAP_STRING_DYN;
      default -> CelValueType.DYN;
    };
  }

  private static BigDecimal decimal(JsonNode s, String k) {
    return s.path(k).isNumber() ? s.get(k).decimalValue() : null;
  }

  private static Integer count(JsonNode s, String k) {
    return s.path(k).canConvertToInt() ? s.get(k).asInt() : null;
  }

  private static String plain(BigDecimal d) {
    return d.stripTrailingZeros().toPlainString();
  }

  private static String join(String path, String key) {
    return path.isEmpty() ? key : path + "." + key;
  }
}
