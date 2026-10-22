package vn.viettel.okrkpi.config.domain;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Phiên bản registry dạng số chấm ({@code 2026.3}, {@code 2.4.0}) và dải tương thích {@code
 * requires.registry} của UI Pack (CORE-08-09, TL-01 "Component registry").
 *
 * <p>Dải gồm các vế nối bằng {@code ||}; mỗi vế là các điều kiện cách nhau bằng dấu cách, mỗi điều
 * kiện là {@code =, >, >=, <, <=} kèm phiên bản, {@code ^x.y} (cùng số đầu, không nhỏ hơn), {@code
 * ~x.y} (cùng hai số đầu, không nhỏ hơn), hoặc phiên bản trần (đúng bằng) (suy luận theo quy ước
 * semver của npm, rút gọn).
 */
public final class RegistryVersion {

  /** Một đến bốn số không âm cách nhau bằng dấu chấm, dài tối đa 16 ký tự. */
  public static final Pattern VERSION = Pattern.compile("^(?=.{1,16}$)\\d{1,6}(\\.\\d{1,6}){0,3}$");

  /** So sánh theo từng số, số thiếu coi là 0. */
  public static final Comparator<String> ORDER = RegistryVersion::compare;

  private RegistryVersion() {}

  public static int compare(String a, String b) {
    String[] x = a.split("\\.");
    String[] y = b.split("\\.");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      long p = i < x.length ? Long.parseLong(x[i]) : 0;
      long q = i < y.length ? Long.parseLong(y[i]) : 0;
      if (p != q) {
        return Long.compare(p, q);
      }
    }
    return 0;
  }

  /**
   * @return {@code true} khi {@code version} thuộc dải; dải sai cú pháp coi là không khớp
   */
  public static boolean satisfies(String range, String version) {
    if (range == null || range.isBlank() || !VERSION.matcher(version).matches()) {
      return false;
    }
    for (String alternative : range.split("\\|\\|")) {
      String trimmed = alternative.trim();
      if (!trimmed.isEmpty() && all(trimmed.split("\\s+"), version)) {
        return true;
      }
    }
    return false;
  }

  private static boolean all(String[] conditions, String version) {
    for (String c : conditions) {
      if (!matches(c, version)) {
        return false;
      }
    }
    return true;
  }

  private static boolean matches(String condition, String version) {
    String op = condition.replaceFirst("^(>=|<=|>|<|=|\\^|~)?.*$", "$1");
    String bound = condition.substring(op.length());
    if (!VERSION.matcher(bound).matches()) {
      return false;
    }
    int cmp = compare(version, bound);
    String[] b = bound.split("\\.");
    String[] v = version.split("\\.");
    return switch (op) {
      case ">=" -> cmp >= 0;
      case "<=" -> cmp <= 0;
      case ">" -> cmp > 0;
      case "<" -> cmp < 0;
      case "^" -> cmp >= 0 && v[0].equals(b[0]);
      case "~" ->
          cmp >= 0
              && v[0].equals(b[0])
              && (b.length < 2 || (v.length > 1 ? v[1] : "0").equals(b[1]));
      default -> cmp == 0;
    };
  }
}
