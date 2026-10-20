package vn.viettel.okrkpi.shared.error;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Kiểm một JSON theo lược đồ trong {@code components.schemas} của {@code common.yaml}. Chỉ hiểu tập
 * con JSON Schema mà {@code ProblemDetails}/{@code FieldError} dùng ({@code $ref}, {@code type},
 * {@code required}, {@code properties}, {@code items}, {@code enum}); thêm một kiểm chặt hơn hợp
 * đồng: không có thuộc tính ngoài {@code properties} (để phản hồi không lộ trường lạ).
 */
public final class ProblemSchema {

  private final Map<String, Object> schemas;

  @SuppressWarnings("unchecked")
  private ProblemSchema(Map<String, Object> schemas) {
    this.schemas = schemas;
  }

  @SuppressWarnings("unchecked")
  public static ProblemSchema fromCommonYaml() {
    try (Reader r = Files.newBufferedReader(ContractFiles.commonYaml(), StandardCharsets.UTF_8)) {
      Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
      Map<String, Object> components = (Map<String, Object>) root.get("components");
      return new ProblemSchema((Map<String, Object>) components.get("schemas"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> schema(String name) {
    Map<String, Object> s = (Map<String, Object>) schemas.get(name);
    if (s == null) {
      throw new IllegalArgumentException("Không có lược đồ " + name);
    }
    return s;
  }

  /** Danh sách vi phạm (rỗng khi hợp lệ). */
  public List<String> validate(JsonNode node, String schemaName) {
    List<String> errors = new ArrayList<>();
    validate(node, schema(schemaName), "$", errors);
    return errors;
  }

  @SuppressWarnings("unchecked")
  private void validate(JsonNode node, Map<String, Object> schema, String path, List<String> errs) {
    Object ref = schema.get("$ref");
    if (ref instanceof String s) {
      String prefix = "#/components/schemas/";
      if (!s.startsWith(prefix)) {
        errs.add(path + ": $ref không hỗ trợ " + s);
        return;
      }
      validate(node, schema(s.substring(prefix.length())), path, errs);
      return;
    }
    Object type = schema.get("type");
    if (type != null && !typeMatches(node, type)) {
      errs.add(path + ": kiểu " + node.getNodeType() + " không khớp " + type);
      return;
    }
    Object en = schema.get("enum");
    if (en instanceof List<?> values && values.stream().noneMatch(v -> sameValue(node, v))) {
      errs.add(path + ": giá trị ngoài enum " + values);
    }
    if (node.isObject()) {
      Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
      for (Object req : (List<Object>) schema.getOrDefault("required", List.of())) {
        if (!node.has(String.valueOf(req))) {
          errs.add(path + ": thiếu trường bắt buộc " + req);
        }
      }
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> f = it.next();
        Object sub = props.get(f.getKey());
        if (sub == null) {
          errs.add(path + ": trường ngoài lược đồ " + f.getKey());
        } else {
          validate(f.getValue(), (Map<String, Object>) sub, path + "." + f.getKey(), errs);
        }
      }
    }
    if (node.isArray() && schema.get("items") instanceof Map<?, ?> items) {
      for (int i = 0; i < node.size(); i++) {
        validate(node.get(i), (Map<String, Object>) items, path + "[" + i + "]", errs);
      }
    }
  }

  private static boolean typeMatches(JsonNode node, Object type) {
    if (type instanceof List<?> types) {
      return types.stream().anyMatch(t -> typeMatches(node, t));
    }
    return switch (String.valueOf(type)) {
      case "string" -> node.isTextual();
      case "integer" -> node.isIntegralNumber();
      case "number" -> node.isNumber();
      case "boolean" -> node.isBoolean();
      case "object" -> node.isObject();
      case "array" -> node.isArray();
      case "null" -> node.isNull();
      default -> false;
    };
  }

  private static boolean sameValue(JsonNode node, Object value) {
    if (value instanceof Number n) {
      return node.isNumber()
          && node.decimalValue().compareTo(new java.math.BigDecimal(n.toString())) == 0;
    }
    return node.isTextual() && node.asText().equals(String.valueOf(value));
  }
}
