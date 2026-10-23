package vn.viettel.okrkpi.workflow.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * So khớp theo luật của Pact đặc tả v3 (DV-13-11, dùng lại cho QA-02), đủ cho hợp đồng của các bộ
 * kết nối: {@code type} (lan xuống nút con, mảng kèm {@code min}, {@code max}), {@code regex},
 * {@code integer}, {@code decimal}, {@code number}, {@code equality}, {@code include}, {@code
 * null}, {@code date}, {@code timestamp}, {@code time} theo {@code format}. Không có luật thì so
 * bằng giá trị. Thân phản hồi cho phép trường thừa (đối tác thêm trường không làm vỡ SVC-13), thân
 * yêu cầu thì không.
 *
 * <p>Khoá luật là đường dẫn kiểu JSONPath bắt đầu bằng {@code $}, nhận {@code [*]} và {@code .*}.
 * Lỗi chỉ ghi đường dẫn và kiểu, không ghi giá trị thực vì phản hồi đối tác có thể chứa dữ liệu C2.
 */
public final class PactMatcher {

  private final JsonNode rules;
  private final boolean allowUnexpectedKeys;
  private final List<String> errors = new ArrayList<>();

  private PactMatcher(JsonNode rules, boolean allowUnexpectedKeys) {
    this.rules = rules == null || rules.isMissingNode() ? TextNode.valueOf("") : rules;
    this.allowUnexpectedKeys = allowUnexpectedKeys;
  }

  /** So thân JSON; trả danh sách lỗi, rỗng là khớp. */
  public static List<String> body(
      JsonNode expected, JsonNode actual, JsonNode rules, boolean allowUnexpectedKeys) {
    PactMatcher m = new PactMatcher(rules, allowUnexpectedKeys);
    m.node(
        "$",
        expected,
        actual == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : actual,
        false);
    return List.copyOf(m.errors);
  }

  /**
   * So một giá trị chuỗi của tham số truy vấn hoặc header; {@code rules} là nhánh {@code query}
   * hoặc {@code header} của {@code matchingRules}, khoá là tên tham số.
   */
  public static boolean value(String name, String expected, String actual, JsonNode rules) {
    JsonNode r = rules == null ? null : rules.get(name);
    if (r == null) {
      return expected.equals(actual);
    }
    PactMatcher m = new PactMatcher(null, true);
    return m.apply(name, r, TextNode.valueOf(expected), TextNode.valueOf(actual), false);
  }

  private void node(String path, JsonNode expected, JsonNode actual, boolean typeOnly) {
    JsonNode rule = ruleFor(path);
    if (rule != null) {
      apply(path, rule, expected, actual, true);
      return;
    }
    compare(path, expected, actual, typeOnly);
  }

  private void compare(String path, JsonNode expected, JsonNode actual, boolean typeOnly) {
    if (actual.isMissingNode()) {
      errors.add(path + ": thiếu");
      return;
    }
    if (expected.isObject()) {
      if (!actual.isObject()) {
        errors.add(path + ": cần đối tượng, nhận " + kind(actual));
        return;
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        node(path + "." + e.getKey(), e.getValue(), actual.path(e.getKey()), typeOnly);
      }
      if (!allowUnexpectedKeys) {
        actual
            .fieldNames()
            .forEachRemaining(
                k -> {
                  if (!expected.has(k)) {
                    errors.add(path + "." + k + ": trường không có trong hợp đồng");
                  }
                });
      }
      return;
    }
    if (expected.isArray()) {
      if (!actual.isArray()) {
        errors.add(path + ": cần mảng, nhận " + kind(actual));
        return;
      }
      if (typeOnly) {
        if (!expected.isEmpty()) {
          for (int i = 0; i < actual.size(); i++) {
            node(path + "[" + i + "]", expected.get(0), actual.get(i), true);
          }
        }
        return;
      }
      if (expected.size() != actual.size()) {
        errors.add(path + ": cần " + expected.size() + " phần tử, nhận " + actual.size());
        return;
      }
      for (int i = 0; i < expected.size(); i++) {
        node(path + "[" + i + "]", expected.get(i), actual.get(i), false);
      }
      return;
    }
    if (typeOnly) {
      if (!sameType(expected, actual)) {
        errors.add(path + ": cần kiểu " + kind(expected) + ", nhận " + kind(actual));
      }
      return;
    }
    if (!equal(expected, actual)) {
      errors.add(path + ": giá trị khác hợp đồng (" + kind(actual) + ")");
    }
  }

  /** Áp danh sách {@code matchers} (mặc định cùng thoả, {@code combine: OR} là một trong số). */
  private boolean apply(
      String path, JsonNode rule, JsonNode expected, JsonNode actual, boolean record) {
    if (actual.isMissingNode()) {
      if (record) {
        errors.add(path + ": thiếu");
      }
      return false;
    }
    boolean or = "OR".equalsIgnoreCase(rule.path("combine").asText("AND"));
    boolean result = !or;
    int before = errors.size();
    for (JsonNode m : rule.path("matchers")) {
      boolean ok = one(path, m, expected, actual);
      result = or ? result || ok : result && ok;
    }
    if (or && result) {
      errors.subList(before, errors.size()).clear();
    }
    if (!record) {
      errors.subList(before, errors.size()).clear();
    }
    return result;
  }

  private boolean one(String path, JsonNode m, JsonNode expected, JsonNode actual) {
    String match = m.path("match").asText("type");
    switch (match) {
      case "type" -> {
        if (actual.isArray() && expected.isArray()) {
          int min = m.path("min").asInt(0);
          int max = m.path("max").asInt(Integer.MAX_VALUE);
          if (actual.size() < min || actual.size() > max) {
            return fail(
                path
                    + ": số phần tử "
                    + actual.size()
                    + " ngoài ["
                    + min
                    + ", "
                    + (max == Integer.MAX_VALUE ? "∞" : max)
                    + "]");
          }
        }
        int before = errors.size();
        compare(path, expected, actual, true);
        return errors.size() == before;
      }
      case "regex" -> {
        String regex = m.path("regex").asText();
        boolean ok =
            actual.isValueNode()
                && !actual.isNull()
                && Pattern.compile(regex).matcher(actual.asText()).matches();
        return ok || fail(path + ": không khớp mẫu " + regex);
      }
      case "integer" -> {
        return actual.isIntegralNumber() || fail(path + ": cần số nguyên, nhận " + kind(actual));
      }
      case "decimal", "number" -> {
        return actual.isNumber() || fail(path + ": cần số, nhận " + kind(actual));
      }
      case "equality" -> {
        return equal(expected, actual) || fail(path + ": giá trị khác hợp đồng");
      }
      case "include" -> {
        return actual.asText().contains(m.path("value").asText())
            || fail(path + ": không chứa chuỗi của hợp đồng");
      }
      case "null" -> {
        return actual.isNull() || fail(path + ": cần null");
      }
      case "date", "timestamp", "time" -> {
        String format = m.path("format").asText(m.path(match).asText(""));
        try {
          DateTimeFormatter.ofPattern(format).parse(actual.asText());
          return actual.isTextual() || fail(path + ": cần chuỗi thời gian");
        } catch (DateTimeParseException | IllegalArgumentException e) {
          return fail(path + ": không đúng định dạng " + format);
        }
      }
      default -> {
        return fail(path + ": luật " + match + " chưa hỗ trợ");
      }
    }
  }

  private boolean fail(String message) {
    errors.add(message);
    return false;
  }

  /** Luật của đúng đường dẫn; khoá cụ thể hơn (ít ký tự đại diện, dài hơn) thắng. */
  private JsonNode ruleFor(String path) {
    if (!rules.isObject()) {
      return null;
    }
    JsonNode best = null;
    int bestScore = -1;
    for (Iterator<Map.Entry<String, JsonNode>> it = rules.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      if (pattern(e.getKey()).matcher(path).matches()) {
        int score = e.getKey().length() * 10 - wildcards(e.getKey());
        if (score > bestScore) {
          best = e.getValue();
          bestScore = score;
        }
      }
    }
    return best;
  }

  static Pattern pattern(String key) {
    StringBuilder re = new StringBuilder();
    for (int i = 0; i < key.length(); i++) {
      if (key.startsWith("[*]", i)) {
        re.append("\\[\\d+\\]");
        i += 2;
      } else if (key.startsWith(".*", i)) {
        re.append("\\.[^.\\[]+");
        i += 1;
      } else {
        re.append(Pattern.quote(String.valueOf(key.charAt(i))));
      }
    }
    return Pattern.compile(re.toString());
  }

  private static int wildcards(String key) {
    return key.split("\\*", -1).length - 1;
  }

  /**
   * Mẫu {@code null} dưới luật {@code type} nhận mọi giá trị (trường có thể rỗng, kèm {@code OR}).
   */
  private static boolean sameType(JsonNode a, JsonNode b) {
    if (a.isNull() || (a.isNumber() && b.isNumber())) {
      return true;
    }
    return a.getNodeType() == b.getNodeType();
  }

  private static boolean equal(JsonNode a, JsonNode b) {
    if (a.isNumber() && b.isNumber()) {
      return a.decimalValue().compareTo(b.decimalValue()) == 0;
    }
    return a.equals(b);
  }

  private static String kind(JsonNode n) {
    return n.isMissingNode()
        ? "không có"
        : n.getNodeType().name().toLowerCase(java.util.Locale.ROOT);
  }
}
