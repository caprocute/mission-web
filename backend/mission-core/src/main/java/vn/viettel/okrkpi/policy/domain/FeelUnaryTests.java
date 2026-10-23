package vn.viettel.okrkpi.policy.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import vn.viettel.okrkpi.policy.domain.FeelCondition.Interval;
import vn.viettel.okrkpi.policy.domain.FeelCondition.NumberSet;
import vn.viettel.okrkpi.policy.domain.FeelCondition.ValueSet;

/**
 * Bộ phân tích FEEL đơn giản (simple unary tests của DMN 1.4) cho ô đầu vào bảng quyết định
 * (SCR-1502 trường 2): {@code -}, so sánh ({@code < <= > >= =}) với hằng, khoảng {@code [a..b]}
 * (đầu mở viết bằng ngoặc tròn), hằng số, chuỗi trong nháy kép, {@code true}/{@code false}, danh
 * sách cách nhau bằng dấu phẩy và {@code not(...)}. Kiểu phải khớp khai báo cột ({@code number},
 * {@code string}, {@code boolean}).
 */
public final class FeelUnaryTests {

  /** Lỗi cú pháp hoặc kiểu; {@code position} tính từ 1 trên chuỗi ô. */
  public static final class FeelException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int position;

    public FeelException(int position, String message) {
      super(message);
      this.position = position;
    }

    public int position() {
      return position;
    }
  }

  private final String text;
  private final String type;
  private int pos;

  private FeelUnaryTests(String text, String type) {
    this.text = text;
    this.type = type;
  }

  /** Phân tích một ô theo kiểu cột. */
  public static FeelCondition parse(String text, String type) {
    if (!Set.of("number", "string", "boolean").contains(type)) {
      throw new FeelException(1, "Kiểu dữ liệu của cột không được hỗ trợ");
    }
    String t = text == null ? "" : text.strip();
    if (t.isEmpty() || t.equals("-")) {
      return FeelCondition.any();
    }
    FeelUnaryTests p = new FeelUnaryTests(t, type);
    FeelCondition c = p.unaryTests();
    p.skipSpace();
    if (p.pos < t.length()) {
      throw p.error("Có ký tự thừa sau biểu thức");
    }
    return c;
  }

  private FeelCondition unaryTests() {
    skipSpace();
    if (text.startsWith("not", pos) && peekAfter("not") == '(') {
      pos += 3;
      skipSpace();
      expect('(');
      FeelCondition inner = list();
      skipSpace();
      expect(')');
      return negate(inner);
    }
    return list();
  }

  private FeelCondition list() {
    List<FeelCondition> parts = new ArrayList<>();
    parts.add(test());
    skipSpace();
    while (pos < text.length() && text.charAt(pos) == ',') {
      pos++;
      parts.add(test());
      skipSpace();
    }
    return union(parts);
  }

  private FeelCondition test() {
    skipSpace();
    if (pos >= text.length()) {
      throw error("Thiếu giá trị");
    }
    return switch (type) {
      case "number" -> numberTest();
      case "string" -> new ValueSet(Set.of(string()), false);
      default -> ValueSet.booleans(List.of(bool()));
    };
  }

  private FeelCondition numberTest() {
    char c = text.charAt(pos);
    if (c == '[' || c == '(' || c == ']') {
      boolean loIncl = c == '[';
      pos++;
      BigDecimal lo = number();
      skipSpace();
      if (!text.startsWith("..", pos)) {
        throw error("Khoảng phải có dạng [a..b]");
      }
      pos += 2;
      BigDecimal hi = number();
      skipSpace();
      if (pos >= text.length() || "])[".indexOf(text.charAt(pos)) < 0) {
        throw error("Thiếu dấu đóng khoảng");
      }
      boolean hiIncl = text.charAt(pos) == ']';
      pos++;
      if (lo.compareTo(hi) > 0) {
        throw error("Đầu khoảng lớn hơn cuối khoảng");
      }
      return new NumberSet(List.of(new Interval(lo, loIncl, hi, hiIncl)));
    }
    String op = "=";
    for (String candidate : List.of("<=", ">=", "<", ">", "=")) {
      if (text.startsWith(candidate, pos)) {
        op = candidate;
        pos += candidate.length();
        break;
      }
    }
    BigDecimal v = number();
    Interval i =
        switch (op) {
          case "<" -> new Interval(null, false, v, false);
          case "<=" -> new Interval(null, false, v, true);
          case ">" -> new Interval(v, false, null, false);
          case ">=" -> new Interval(v, true, null, false);
          default -> new Interval(v, true, v, true);
        };
    return new NumberSet(List.of(i));
  }

  private BigDecimal number() {
    skipSpace();
    int start = pos;
    if (pos < text.length() && text.charAt(pos) == '-') {
      pos++;
    }
    while (pos < text.length()
        && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
      if (text.charAt(pos) == '.' && text.startsWith("..", pos)) {
        break;
      }
      pos++;
    }
    String raw = text.substring(start, pos);
    try {
      return new BigDecimal(raw);
    } catch (NumberFormatException e) {
      pos = start;
      throw error("Cần một số ở cột kiểu số");
    }
  }

  private String string() {
    if (text.charAt(pos) != '"') {
      throw error("Cần chuỗi trong dấu nháy kép ở cột kiểu chuỗi");
    }
    pos++;
    StringBuilder sb = new StringBuilder();
    while (pos < text.length() && text.charAt(pos) != '"') {
      char c = text.charAt(pos++);
      if (c == '\\' && pos < text.length()) {
        c = text.charAt(pos++);
      }
      sb.append(c);
    }
    if (pos >= text.length()) {
      throw error("Chuỗi chưa đóng dấu nháy kép");
    }
    pos++;
    return sb.toString();
  }

  private boolean bool() {
    if (text.startsWith("true", pos)) {
      pos += 4;
      return true;
    }
    if (text.startsWith("false", pos)) {
      pos += 5;
      return false;
    }
    throw error("Cần true hoặc false ở cột đúng/sai");
  }

  private FeelCondition union(List<FeelCondition> parts) {
    if (parts.size() == 1) {
      return parts.get(0);
    }
    if (parts.get(0) instanceof NumberSet) {
      List<Interval> all = new ArrayList<>();
      parts.forEach(p -> all.addAll(((NumberSet) p).intervals()));
      return new NumberSet(all);
    }
    Set<Object> values = new LinkedHashSet<>();
    for (FeelCondition p : parts) {
      ValueSet v = (ValueSet) p;
      if (v.negated()) {
        return v.values().isEmpty() ? v : unionNegated(parts);
      }
      values.addAll(v.values());
    }
    if (type.equals("boolean")) {
      List<Boolean> b = new ArrayList<>();
      values.forEach(o -> b.add((Boolean) o));
      return ValueSet.booleans(b);
    }
    return new ValueSet(values, false);
  }

  private static FeelCondition unionNegated(List<FeelCondition> parts) {
    return new ValueSet(Set.of(), true);
  }

  private FeelCondition negate(FeelCondition inner) {
    if (inner instanceof NumberSet n) {
      return n.complement();
    }
    ValueSet v = (ValueSet) inner;
    if (type.equals("boolean")) {
      List<Boolean> rest = new ArrayList<>();
      for (Boolean b : List.of(true, false)) {
        if (!v.matches(b)) {
          rest.add(b);
        }
      }
      if (rest.isEmpty()) {
        throw error("Điều kiện không bao giờ thoả mãn");
      }
      return ValueSet.booleans(rest);
    }
    return new ValueSet(v.values(), !v.negated());
  }

  private char peekAfter(String word) {
    int p = pos + word.length();
    while (p < text.length() && Character.isWhitespace(text.charAt(p))) {
      p++;
    }
    return p < text.length() ? text.charAt(p) : 0;
  }

  private void expect(char c) {
    if (pos >= text.length() || text.charAt(pos) != c) {
      throw error("Thiếu ký tự " + c);
    }
    pos++;
  }

  private void skipSpace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }

  private FeelException error(String message) {
    return new FeelException(pos + 1, message);
  }
}
