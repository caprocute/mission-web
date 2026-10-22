package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelSandboxOptions;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/**
 * Kiểm một phiên bản biểu mẫu động khi lưu (CORE-08-13, BR-18-04, BR-18-11, TL-02b QT-18): JSON
 * Schema có cấu trúc hợp lệ; khoá dữ liệu snake_case ≤ 64 ký tự, duy nhất trong cả biểu mẫu; mọi
 * trường lá khai báo {@code x-classification} C1 hoặc C2; {@code x-cel}, quy tắc CEL và {@code
 * hideExpression} biên dịch được bằng {@code vt-cel}; điều kiện hiển thị không tham chiếu vòng.
 */
public class FormSchemaValidator {

  /** Khoá dữ liệu của trường. */
  public static final Pattern DATA_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

  private static final Set<String> TYPES =
      Set.of("object", "array", "string", "number", "integer", "boolean", "null");
  private static final Set<String> CLASSES = Set.of("C1", "C2");
  private static final List<String> NUMBER_KEYWORDS =
      List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf");
  private static final List<String> COUNT_KEYWORDS =
      List.of("minLength", "maxLength", "minItems", "maxItems");
  private static final Pattern FORM_REF = Pattern.compile("\\bform\\.([a-z][a-z0-9_]*)");

  /** Giới hạn biểu thức của biểu mẫu: độ dài theo hợp đồng {@code celRules.expr} (suy luận). */
  static final CelSandboxOptions LIMITS =
      new CelSandboxOptions(1000, 16, 128, 100, Duration.ofSeconds(2), 512);

  private final CelEngine cel = new CelEngine(StaticWorkingCalendar.weekendsOnly(), LIMITS);

  /** Kết quả kiểm: lỗi cấu trúc và lỗi CEL tách riêng để trả đúng mã. */
  public record Result(
      List<ProblemFieldError> structure, List<ProblemFieldError> cel, Map<String, String> fields) {
    public boolean passed() {
      return structure.isEmpty() && cel.isEmpty();
    }
  }

  /**
   * @return lỗi và bảng trường lá (khoá → kiểu JSON Schema)
   */
  public Result validate(JsonNode schema, JsonNode ui, JsonNode celRules) {
    List<ProblemFieldError> structure = new ArrayList<>();
    List<ProblemFieldError> celErrors = new ArrayList<>();
    Map<String, String> fields = new LinkedHashMap<>();
    if (schema == null || !schema.isObject()) {
      structure.add(error("schemaJson", "Lược đồ biểu mẫu phải là một đối tượng JSON"));
      return new Result(structure, celErrors, fields);
    }
    if (!"object".equals(schema.path("type").asText())) {
      structure.add(error("schemaJson.type", "Lược đồ gốc của biểu mẫu phải có kiểu object"));
    }
    Set<String> seen = new HashSet<>();
    walk(schema, "schemaJson", true, seen, fields, structure, celErrors);
    rules(celRules, fields, celErrors, structure);
    presentation(ui, fields, structure, celErrors);
    return new Result(structure, celErrors, fields);
  }

  private void walk(
      JsonNode node,
      String path,
      boolean root,
      Set<String> seen,
      Map<String, String> fields,
      List<ProblemFieldError> structure,
      List<ProblemFieldError> celErrors) {
    String type = typeOf(node, path, structure);
    for (String k : NUMBER_KEYWORDS) {
      if (node.has(k) && !node.get(k).isNumber()) {
        structure.add(error(path + "." + k, "Giá trị phải là số"));
      }
    }
    for (String k : COUNT_KEYWORDS) {
      if (node.has(k) && !(node.get(k).canConvertToInt() && node.get(k).asInt() >= 0)) {
        structure.add(error(path + "." + k, "Giá trị phải là số nguyên không âm"));
      }
    }
    if (node.has("enum") && !(node.get("enum").isArray() && !node.get("enum").isEmpty())) {
      structure.add(error(path + ".enum", "Danh sách giá trị phải là mảng không rỗng"));
    }
    if (node.has("pattern")) {
      try {
        Pattern.compile(node.get("pattern").asText());
      } catch (PatternSyntaxException e) {
        structure.add(error(path + ".pattern", "Mẫu kiểm tra không hợp lệ"));
      }
    }
    JsonNode properties = node.path("properties");
    if (node.has("properties") && !properties.isObject()) {
      structure.add(error(path + ".properties", "Danh sách trường phải là đối tượng"));
    }
    if (node.has("required")) {
      JsonNode required = node.get("required");
      if (!required.isArray()) {
        structure.add(error(path + ".required", "Danh sách trường bắt buộc phải là mảng"));
      } else {
        for (JsonNode r : required) {
          if (!properties.has(r.asText())) {
            structure.add(
                error(path + ".required", "Trường bắt buộc " + r.asText() + " chưa được khai báo"));
          }
        }
      }
    }
    if ("array".equals(type) && node.has("items") && node.get("items").isObject()) {
      walk(node.get("items"), path + ".items", false, seen, fields, structure, celErrors);
    }
    if (properties.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> p = it.next();
        String key = p.getKey();
        String at = path + ".properties." + key;
        if (!DATA_KEY.matcher(key).matches()) {
          structure.add(
              error(at, "Khoá dữ liệu phải viết thường có dấu gạch dưới, tối đa 64 ký tự"));
        }
        if (!seen.add(key)) {
          structure.add(error(at, "Khoá dữ liệu " + key + " bị trùng trong biểu mẫu"));
        }
        JsonNode child = p.getValue();
        if (!child.isObject()) {
          structure.add(error(at, "Khai báo trường phải là đối tượng"));
          continue;
        }
        String childType = child.path("type").asText("");
        boolean leaf = !"object".equals(childType);
        if (leaf) {
          String cls = child.path("x-classification").asText("");
          if (!CLASSES.contains(cls)) {
            structure.add(
                error(at + ".x-classification", "Trường phải khai báo lớp dữ liệu C1 hoặc C2"));
          }
          fields.put(key, childType);
        }
        if (child.has("x-cel")) {
          compile(child.get("x-cel").asText(""), selfType(childType), at + ".x-cel", celErrors);
        }
        walk(child, at, false, seen, fields, structure, celErrors);
      }
    }
    if (root && node.has("x-cel")) {
      compile(
          node.get("x-cel").asText(""), CelValueType.MAP_STRING_DYN, path + ".x-cel", celErrors);
    }
  }

  private static String typeOf(JsonNode node, String path, List<ProblemFieldError> structure) {
    JsonNode t = node.get("type");
    if (t == null) {
      return "";
    }
    if (t.isTextual() && TYPES.contains(t.asText())) {
      return t.asText();
    }
    if (t.isArray()) {
      String first = "";
      for (JsonNode x : t) {
        if (!TYPES.contains(x.asText())) {
          structure.add(error(path + ".type", "Kiểu dữ liệu không hợp lệ"));
          return "";
        }
        if (first.isEmpty() && !"null".equals(x.asText())) {
          first = x.asText();
        }
      }
      return first;
    }
    structure.add(error(path + ".type", "Kiểu dữ liệu không hợp lệ"));
    return "";
  }

  private void rules(
      JsonNode celRules,
      Map<String, String> fields,
      List<ProblemFieldError> celErrors,
      List<ProblemFieldError> structure) {
    if (celRules == null || celRules.isNull()) {
      return;
    }
    if (!celRules.isArray()) {
      structure.add(error("celRules", "Danh sách quy tắc phải là mảng"));
      return;
    }
    for (int i = 0; i < celRules.size(); i++) {
      JsonNode r = celRules.get(i);
      String at = "celRules[" + i + "]";
      if (!fields.containsKey(r.path("field").asText())) {
        structure.add(error(at + ".field", "Quy tắc tham chiếu trường không có trong biểu mẫu"));
      }
      compile(r.path("expr").asText(""), null, at + ".expr", celErrors);
    }
  }

  /** {@code uiJson} theo khoá trường: {@code hideExpression} biên dịch được, không vòng. */
  private void presentation(
      JsonNode ui,
      Map<String, String> fields,
      List<ProblemFieldError> structure,
      List<ProblemFieldError> celErrors) {
    if (ui == null || !ui.isObject()) {
      structure.add(error("uiJson", "Cấu hình trình bày phải là một đối tượng JSON"));
      return;
    }
    Map<String, Set<String>> graph = new HashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = ui.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode hide = e.getValue().path("hideExpression");
      if (!hide.isTextual()) {
        continue;
      }
      String at = "uiJson." + e.getKey() + ".hideExpression";
      compile(hide.asText(), null, at, celErrors);
      Set<String> refs = new HashSet<>();
      Matcher m = FORM_REF.matcher(hide.asText());
      while (m.find()) {
        refs.add(m.group(1));
      }
      graph.put(e.getKey(), refs);
    }
    for (String start : graph.keySet()) {
      if (cycle(start, graph, new HashSet<>(), new HashSet<>())) {
        structure.add(
            error(
                "uiJson." + start + ".hideExpression",
                "Điều kiện hiển thị tham chiếu vòng giữa các trường"));
      }
    }
  }

  private static boolean cycle(
      String node, Map<String, Set<String>> graph, Set<String> path, Set<String> done) {
    if (path.contains(node)) {
      return true;
    }
    if (!done.add(node)) {
      return false;
    }
    path.add(node);
    for (String next : graph.getOrDefault(node, Set.of())) {
      if (cycle(next, graph, path, done)) {
        return true;
      }
    }
    path.remove(node);
    return false;
  }

  private void compile(
      String expression, CelValueType self, String at, List<ProblemFieldError> errors) {
    Map<String, CelValueType> context = new HashMap<>();
    context.put("form", CelValueType.MAP_STRING_DYN);
    if (self != null) {
      context.put("self", self);
    }
    try {
      cel.compile(expression, context, CelValueType.BOOL);
    } catch (CelCompilationException e) {
      errors.add(error(at, "Biểu thức CEL không hợp lệ: " + e.getMessage() + "."));
    }
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

  private static ProblemFieldError error(String field, String message) {
    return new ProblemFieldError(field, message, null);
  }
}
