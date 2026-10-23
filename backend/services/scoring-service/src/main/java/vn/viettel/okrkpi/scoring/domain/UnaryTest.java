package vn.viettel.okrkpi.scoring.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kiểm một ô đầu vào FEEL dạng đơn giản mà Policy Pack dùng (DMN 1.4 "unary tests"): {@code -} (bất
 * kỳ), giá trị chuỗi, số, boolean, so sánh {@code < <= > >=} với số, khoảng {@code [a..b)} và danh
 * sách các dạng trên cách nhau dấu phẩy. Chỉ dùng để tìm điều kiện còn thiếu của một dòng
 * (BR-09-10); dòng được chọn do Flowable quyết định.
 */
public final class UnaryTest {

  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
  private static final Pattern COMPARISON = Pattern.compile("(<=|>=|<|>)\\s*(-?\\d+(?:\\.\\d+)?)");
  private static final Pattern INTERVAL =
      Pattern.compile(
          "([\\[(\\]])\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:\\.\\d+)?)\\s*([\\])\\[])");

  private UnaryTest() {}

  /**
   * Giá trị thoả ô.
   *
   * @throws IllegalArgumentException ô không thuộc các dạng được hỗ trợ
   */
  public static boolean matches(String cell, Object value) {
    String c = cell == null ? "" : cell.strip();
    if (c.isEmpty() || "-".equals(c)) {
      return true;
    }
    for (String part : split(c)) {
      if (single(part, value)) {
        return true;
      }
    }
    return false;
  }

  /** Số ngưỡng của ô so sánh hay khoảng ({@code >= 90} cho 90); {@code null} khi không có. */
  public static BigDecimal threshold(String cell) {
    String c = cell == null ? "" : cell.strip();
    Matcher m = COMPARISON.matcher(c);
    if (m.matches()) {
      return new BigDecimal(m.group(2));
    }
    Matcher i = INTERVAL.matcher(c);
    if (i.matches()) {
      return new BigDecimal(i.group(2));
    }
    return NUMBER.matcher(c).matches() ? new BigDecimal(c) : null;
  }

  private static boolean single(String part, Object value) {
    if (part.startsWith("\"") && part.endsWith("\"") && part.length() >= 2) {
      return part.substring(1, part.length() - 1).equals(value);
    }
    if ("true".equals(part) || "false".equals(part)) {
      return value instanceof Boolean b && b == Boolean.parseBoolean(part);
    }
    BigDecimal v = value instanceof Number n ? new BigDecimal(n.toString()) : null;
    if (NUMBER.matcher(part).matches()) {
      return v != null && v.compareTo(new BigDecimal(part)) == 0;
    }
    Matcher m = COMPARISON.matcher(part);
    if (m.matches()) {
      if (v == null) {
        return false;
      }
      int cmp = v.compareTo(new BigDecimal(m.group(2)));
      return switch (m.group(1)) {
        case "<" -> cmp < 0;
        case "<=" -> cmp <= 0;
        case ">" -> cmp > 0;
        default -> cmp >= 0;
      };
    }
    Matcher i = INTERVAL.matcher(part);
    if (i.matches()) {
      if (v == null) {
        return false;
      }
      int low = v.compareTo(new BigDecimal(i.group(2)));
      int high = v.compareTo(new BigDecimal(i.group(3)));
      boolean lowOk = "[".equals(i.group(1)) ? low >= 0 : low > 0;
      boolean highOk = "]".equals(i.group(4)) ? high <= 0 : high < 0;
      return lowOk && highOk;
    }
    throw new IllegalArgumentException("Ô FEEL không được hỗ trợ: " + part);
  }

  /** Tách theo dấu phẩy nằm ngoài chuỗi trích dẫn và ngoài khoảng. */
  static List<String> split(String cell) {
    List<String> parts = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    int depth = 0;
    for (char ch : cell.toCharArray()) {
      if (ch == '"') {
        quoted = !quoted;
      } else if (!quoted && (ch == '[' || ch == '(')) {
        depth++;
      } else if (!quoted && (ch == ']' || ch == ')') && depth > 0) {
        depth--;
      }
      if (ch == ',' && !quoted && depth == 0) {
        parts.add(cur.toString().strip());
        cur.setLength(0);
      } else {
        cur.append(ch);
      }
    }
    parts.add(cur.toString().strip());
    return parts;
  }
}
