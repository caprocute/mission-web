package vn.viettel.okrkpi.policy.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Chuẩn hoá JSON theo RFC 8785 (JSON Canonicalization Scheme) để băm và ký ca kiểm thử vàng
 * (CORE-05-06, TL-01 "Tính lại tái lập được"): khoá đối tượng sắp theo đơn vị mã UTF-16, chuỗi chỉ
 * thoát ký tự bắt buộc, số in theo quy tắc {@code Number.prototype.toString} của ECMAScript.
 */
public final class CanonicalJson {

  private CanonicalJson() {}

  /** Dạng chuẩn hoá của giá trị gồm {@link Map}, {@link Collection}, chuỗi, số, boolean, null. */
  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value);
    return out.toString();
  }

  private static void append(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof Boolean b) {
      out.append(b.booleanValue());
    } else if (value instanceof Number n) {
      out.append(number(n));
    } else if (value instanceof CharSequence s) {
      string(out, s.toString());
    } else if (value instanceof Map<?, ?> m) {
      List<String> keys = new ArrayList<>();
      for (Object k : m.keySet()) {
        keys.add(String.valueOf(k));
      }
      keys.sort(null);
      out.append('{');
      for (int i = 0; i < keys.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        string(out, keys.get(i));
        out.append(':');
        append(out, m.get(keys.get(i)));
      }
      out.append('}');
    } else if (value instanceof Collection<?> c) {
      out.append('[');
      boolean first = true;
      for (Object item : c) {
        if (!first) {
          out.append(',');
        }
        first = false;
        append(out, item);
      }
      out.append(']');
    } else {
      throw new IllegalArgumentException(
          "Kiểu không biểu diễn được bằng JSON: " + value.getClass());
    }
  }

  private static void string(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** Số IEEE 754 theo ECMAScript (RFC 8785 mục 3.2.2.3). */
  static String number(Number n) {
    double d = n.doubleValue();
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new IllegalArgumentException("RFC 8785 không cho phép NaN hoặc vô cực");
    }
    if (d == 0) {
      return "0";
    }
    String sign = d < 0 ? "-" : "";
    BigDecimal shortest = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
    String digits = shortest.unscaledValue().toString();
    int k = digits.length();
    int point = k - shortest.scale();
    StringBuilder out = new StringBuilder(sign);
    if (k <= point && point <= 21) {
      out.append(digits).append("0".repeat(point - k));
    } else if (0 < point && point <= 21) {
      out.append(digits, 0, point).append('.').append(digits, point, k);
    } else if (-6 < point && point <= 0) {
      out.append("0.").append("0".repeat(-point)).append(digits);
    } else {
      int e = point - 1;
      out.append(digits.charAt(0));
      if (k > 1) {
        out.append('.').append(digits, 1, k);
      }
      out.append('e').append(e > 0 ? "+" : "-").append(Math.abs(e));
    }
    return out.toString();
  }
}
