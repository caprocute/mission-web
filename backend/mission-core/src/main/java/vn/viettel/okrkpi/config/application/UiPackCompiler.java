package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Biên dịch token UI Pack khi phát hành (CORE-08-11, BR-18-01, TL-01 "Pipeline token sang CSS
 * variables và preset PrimeNG"): CSS custom properties {@code --vt-<đường-dẫn>} dưới {@code
 * :root[data-tenant]} và phần ghi đè dưới {@code [data-mode="elderly"]}, JSON preset PrimeNG rút
 * gọn, và dạng JSON chuẩn tắc (khoá sắp xếp, không khoảng trắng) để băm và ký.
 *
 * <p>Biên dịch viết bằng Java trong MOD-08 thay cho job Node chạy Style Dictionary của TL-01
 * (WEB-004 chưa có, BACKEND.md không có Node); quy ước tên biến khớp {@code previewUiPackVersion}
 * (wiki 14).
 */
public class UiPackCompiler {

  private static final Pattern ALIAS = Pattern.compile("^\\{([A-Za-z0-9_.-]+)\\}$");
  private static final Pattern UNSAFE = Pattern.compile("[;{}<>\\\\]|url\\(|expression\\(|@import");

  private final ObjectMapper canonical;

  public UiPackCompiler(ObjectMapper json) {
    this.canonical =
        Objects.requireNonNull(json, "json")
            .copy()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.INDENT_OUTPUT, false);
  }

  /** Biến CSS của bộ token, bí danh {@code {a.b}} thành {@code var(--vt-a-b)}. */
  public static Map<String, String> variables(JsonNode tokens) {
    Map<String, String> raw = new LinkedHashMap<>();
    UiPackService.flatten(tokens, "--vt", raw);
    Map<String, String> out = new LinkedHashMap<>();
    raw.forEach(
        (name, value) -> {
          Matcher alias = ALIAS.matcher(value.trim());
          String v = alias.matches() ? "var(--vt-" + alias.group(1).replace('.', '-') + ")" : value;
          if (UNSAFE.matcher(v).find() || !name.matches("--vt[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("Token không biên dịch an toàn được: " + name);
          }
          out.put(name, v);
        });
    return out;
  }

  /**
   * Tệp CSS của gói.
   *
   * @param tenant giá trị thuộc tính {@code data-tenant} của shell
   * @param elderly ghi đè chế độ cao tuổi, {@code null} khi không bật
   */
  public String css(String tenant, JsonNode tokens, JsonNode elderly) {
    if (!tenant.matches("[A-Za-z0-9-]{1,64}")) {
      throw new IllegalArgumentException("Mã tenant không hợp lệ");
    }
    Map<String, String> base = variables(tokens);
    StringBuilder css = new StringBuilder();
    block(css, ":root[data-tenant=\"" + tenant + "\"]", base);
    if (elderly != null && elderly.isObject()) {
      Map<String, String> merged = variables(UiPackService.merge(tokens.deepCopy(), elderly));
      Map<String, String> changed = new LinkedHashMap<>();
      merged.forEach(
          (k, v) -> {
            if (!v.equals(base.get(k))) {
              changed.put(k, v);
            }
          });
      block(css, ":root[data-tenant=\"" + tenant + "\"][data-mode=\"elderly\"]", changed);
    }
    return css.toString();
  }

  private static void block(StringBuilder css, String selector, Map<String, String> vars) {
    css.append(selector).append(" {\n");
    vars.forEach((k, v) -> css.append("  ").append(k).append(": ").append(v).append(";\n"));
    css.append("}\n");
  }

  /**
   * Preset PrimeNG rút gọn ánh xạ token ngữ nghĩa sang {@code semantic} của preset Aura (suy luận;
   * WEB-004 chốt ánh xạ đầy đủ): {@code default} và {@code elderly}.
   */
  public JsonNode preset(JsonNode tokens, JsonNode elderly) {
    ObjectNode out = canonical.createObjectNode();
    out.set("default", presetOf(tokens));
    if (elderly != null && elderly.isObject()) {
      out.set("elderly", presetOf(UiPackService.merge(tokens.deepCopy(), elderly)));
    }
    return out;
  }

  private ObjectNode presetOf(JsonNode tokens) {
    Map<String, String> v = variables(tokens);
    ObjectNode preset = canonical.createObjectNode();
    ObjectNode semantic = preset.putObject("semantic");
    ObjectNode primary = semantic.putObject("primary");
    put(primary, "color", v, "--vt-color-primary");
    put(primary, "contrastColor", v, "--vt-color-on-primary", "--vt-color-onPrimary");
    ObjectNode light = semantic.putObject("colorScheme").putObject("light");
    put(light.putObject("surface"), "0", v, "--vt-color-surface");
    put(
        light.putObject("text"),
        "color",
        v,
        "--vt-color-text",
        "--vt-color-on-surface",
        "--vt-color-onSurface");
    ObjectNode vt = preset.putObject("extend").putObject("vt");
    put(vt, "fontSizeBody", v, "--vt-font-size-body");
    put(vt, "radiusCard", v, "--vt-radius-card");
    return preset;
  }

  private static void put(ObjectNode target, String field, Map<String, String> v, String... keys) {
    for (String k : keys) {
      if (v.containsKey(k)) {
        target.put(field, v.get(k));
        return;
      }
    }
  }

  /** JSON chuẩn tắc: khoá sắp xếp ở mọi mức, gọn, UTF-8. */
  public byte[] canonical(JsonNode node) {
    try {
      return canonical.writeValueAsString(sorted(node)).getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode sorted(JsonNode node) {
    if (node.isObject()) {
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      names.sort(null);
      ObjectNode out = canonical.createObjectNode();
      for (String n : names) {
        out.set(n, sorted(node.get(n)));
      }
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = canonical.createArrayNode();
      for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
        out.add(sorted(it.next()));
      }
      return out;
    }
    return node;
  }
}
