package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/**
 * Lược đồ UI Pack 1.0 ({@code config/ui-pack/ui-pack-1.0.schema.json}) và bộ kiểm đồng bộ cho phần
 * con của JSON Schema 2020-12 mà lược đồ dùng: {@code type}, {@code enum}, {@code pattern}, {@code
 * minLength}/{@code maxLength}, {@code minimum}/{@code maximum}, {@code minItems}/{@code maxItems},
 * {@code maxProperties}, {@code required}, {@code properties}, {@code additionalProperties}, {@code
 * propertyNames}, {@code items}, {@code $ref} nội bộ và {@code if/then/else}. Viết tay thay vì thêm
 * thư viện kiểm lược đồ (quy tắc 1 của AGENTS.md); từ khoá ngoài danh sách này làm lược đồ không
 * nạp được, nên không có từ khoá bị bỏ qua lặng lẽ.
 */
public final class UiPackSchema {

  public static final String RESOURCE = "/config/ui-pack/ui-pack-1.0.schema.json";

  /** Giới hạn số lỗi trả về một lần. */
  public static final int MAX_ERRORS = 50;

  private static final List<String> KNOWN =
      List.of(
          "$schema",
          "$id",
          "title",
          "description",
          "$defs",
          "type",
          "enum",
          "pattern",
          "minLength",
          "maxLength",
          "minimum",
          "maximum",
          "minItems",
          "maxItems",
          "maxProperties",
          "required",
          "properties",
          "additionalProperties",
          "propertyNames",
          "items",
          "$ref",
          "if",
          "then",
          "else");

  private final JsonNode root;
  private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

  private UiPackSchema(JsonNode root) {
    this.root = root;
    checkKeywords(root, "#");
  }

  /** Lược đồ UI Pack 1.0 trên classpath. */
  public static UiPackSchema load(ObjectMapper json) {
    try (InputStream in = UiPackSchema.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu lược đồ UI Pack " + RESOURCE);
      }
      return new UiPackSchema(json.readTree(in));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Lược đồ dựng sẵn, cho kiểm thử. */
  static UiPackSchema of(JsonNode schema) {
    return new UiPackSchema(schema);
  }

  /** Lỗi của tài liệu theo trường (đường dẫn chấm), rỗng khi hợp lệ. */
  public List<ProblemFieldError> validate(JsonNode document) {
    List<ProblemFieldError> errors = new ArrayList<>();
    check(root, document, "", errors);
    return errors.size() > MAX_ERRORS ? List.copyOf(errors.subList(0, MAX_ERRORS)) : errors;
  }

  private void check(JsonNode schema, JsonNode value, String path, List<ProblemFieldError> errors) {
    if (errors.size() > MAX_ERRORS) {
      return;
    }
    if (schema.has("$ref")) {
      check(resolve(schema.get("$ref").asText()), value, path, errors);
    }
    if (schema.has("if")) {
      boolean matches = valid(schema.get("if"), value, path);
      JsonNode branch = schema.get(matches ? "then" : "else");
      if (branch != null) {
        check(branch, value, path, errors);
      }
    }
    if (schema.has("type") && !typeMatches(schema.get("type"), value)) {
      errors.add(error(path, "Kiểu dữ liệu không đúng", "type"));
      return;
    }
    if (schema.has("enum") && !contains(schema.get("enum"), value)) {
      errors.add(error(path, "Giá trị không nằm trong danh sách cho phép", "enum"));
    }
    if (value.isTextual()) {
      checkString(schema, value.asText(), path, errors);
    } else if (value.isNumber()) {
      checkNumber(schema, value.decimalValue(), path, errors);
    } else if (value.isArray()) {
      checkArray(schema, value, path, errors);
    } else if (value.isObject()) {
      checkObject(schema, value, path, errors);
    }
  }

  private boolean valid(JsonNode schema, JsonNode value, String path) {
    List<ProblemFieldError> probe = new ArrayList<>();
    check(schema, value, path, probe);
    return probe.isEmpty();
  }

  private void checkString(JsonNode schema, String s, String path, List<ProblemFieldError> errors) {
    int length = s.codePointCount(0, s.length());
    if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) {
      errors.add(error(path, "Dài quá " + schema.get("maxLength").asInt() + " ký tự", "maxLength"));
    }
    if (schema.has("minLength") && length < schema.get("minLength").asInt()) {
      errors.add(
          error(path, "Cần ít nhất " + schema.get("minLength").asInt() + " ký tự", "minLength"));
    }
    if (schema.has("pattern") && !pattern(schema.get("pattern").asText()).matcher(s).find()) {
      boolean markup = s.indexOf('<') >= 0 || s.indexOf('>') >= 0;
      errors.add(
          error(
              path,
              markup
                  ? "Không được chứa thẻ HTML hoặc ký tự < >"
                  : "Giá trị không đúng định dạng cho phép",
              "pattern"));
    }
  }

  private static void checkNumber(
      JsonNode schema, BigDecimal n, String path, List<ProblemFieldError> errors) {
    if (schema.has("minimum") && n.compareTo(schema.get("minimum").decimalValue()) < 0) {
      errors.add(error(path, "Nhỏ hơn mức tối thiểu " + schema.get("minimum").asText(), "minimum"));
    }
    if (schema.has("maximum") && n.compareTo(schema.get("maximum").decimalValue()) > 0) {
      errors.add(error(path, "Vượt mức tối đa " + schema.get("maximum").asText(), "maximum"));
    }
  }

  private void checkArray(
      JsonNode schema, JsonNode array, String path, List<ProblemFieldError> errors) {
    if (schema.has("maxItems") && array.size() > schema.get("maxItems").asInt()) {
      errors.add(error(path, "Quá " + schema.get("maxItems").asInt() + " phần tử", "maxItems"));
    }
    if (schema.has("minItems") && array.size() < schema.get("minItems").asInt()) {
      errors.add(
          error(path, "Cần ít nhất " + schema.get("minItems").asInt() + " phần tử", "minItems"));
    }
    if (schema.has("items")) {
      for (int i = 0; i < array.size(); i++) {
        check(schema.get("items"), array.get(i), path + "[" + i + "]", errors);
      }
    }
  }

  private void checkObject(
      JsonNode schema, JsonNode object, String path, List<ProblemFieldError> errors) {
    if (schema.has("maxProperties") && object.size() > schema.get("maxProperties").asInt()) {
      errors.add(
          error(path, "Quá " + schema.get("maxProperties").asInt() + " mục", "maxProperties"));
    }
    if (schema.has("required")) {
      for (JsonNode r : schema.get("required")) {
        if (!object.has(r.asText())) {
          errors.add(error(child(path, r.asText()), "Thiếu trường bắt buộc", "required"));
        }
      }
    }
    JsonNode properties = schema.get("properties");
    JsonNode additional = schema.get("additionalProperties");
    JsonNode names = schema.get("propertyNames");
    for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      String p = child(path, e.getKey());
      if (names != null
          && names.has("pattern")
          && !pattern(names.get("pattern").asText()).matcher(e.getKey()).find()) {
        errors.add(error(p, "Tên mục không hợp lệ", "propertyNames"));
        continue;
      }
      if (properties != null && properties.has(e.getKey())) {
        check(properties.get(e.getKey()), e.getValue(), p, errors);
      } else if (additional != null) {
        if (additional.isBoolean()) {
          if (!additional.asBoolean()) {
            errors.add(error(p, "Trường không được hỗ trợ", "additionalProperties"));
          }
        } else {
          check(additional, e.getValue(), p, errors);
        }
      }
    }
  }

  private JsonNode resolve(String ref) {
    if (!ref.startsWith("#/")) {
      throw new IllegalStateException("Chỉ hỗ trợ $ref nội bộ: " + ref);
    }
    JsonNode node = root.at(ref.substring(1));
    if (node.isMissingNode()) {
      throw new IllegalStateException("Không tìm thấy " + ref);
    }
    return node;
  }

  private Pattern pattern(String regex) {
    return patterns.computeIfAbsent(regex, Pattern::compile);
  }

  private static boolean typeMatches(JsonNode type, JsonNode value) {
    if (type.isArray()) {
      for (JsonNode t : type) {
        if (typeMatches(t, value)) {
          return true;
        }
      }
      return false;
    }
    return switch (type.asText()) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isTextual();
      case "boolean" -> value.isBoolean();
      case "null" -> value.isNull();
      case "number" -> value.isNumber();
      case "integer" ->
          value.isIntegralNumber()
              || (value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0);
      default -> throw new IllegalStateException("Kiểu lạ trong lược đồ: " + type.asText());
    };
  }

  private static boolean contains(JsonNode values, JsonNode value) {
    for (JsonNode v : values) {
      if (v.equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static String child(String path, String key) {
    return path.isEmpty() ? key : path + "." + key;
  }

  private static ProblemFieldError error(String path, String message, String keyword) {
    return new ProblemFieldError(path.isEmpty() ? "$" : path, message, null);
  }

  /**
   * Lược đồ chỉ dùng từ khoá bộ kiểm hiểu; tên trong {@code properties}, {@code $defs} được bỏ qua.
   */
  private static void checkKeywords(JsonNode schema, String at) {
    if (!schema.isObject()) {
      return;
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = schema.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      String k = e.getKey();
      if (!KNOWN.contains(k)) {
        throw new IllegalStateException("Từ khoá lược đồ chưa hỗ trợ " + k + " tại " + at);
      }
      switch (k) {
        case "properties", "$defs" -> {
          for (Iterator<Map.Entry<String, JsonNode>> p = e.getValue().fields(); p.hasNext(); ) {
            Map.Entry<String, JsonNode> sub = p.next();
            checkKeywords(sub.getValue(), at + "/" + k + "/" + sub.getKey());
          }
        }
        case "additionalProperties", "items", "propertyNames", "if", "then", "else" ->
            checkKeywords(e.getValue(), at + "/" + k);
        default -> {
          // Từ khoá lá.
        }
      }
    }
  }
}
