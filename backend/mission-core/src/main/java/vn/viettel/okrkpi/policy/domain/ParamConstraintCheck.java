package vn.viettel.okrkpi.policy.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Kiểm phiên bản gói cấp dưới theo khung tham số của hồ sơ tập khách hàng (BS-03 DC-02). Giá trị
 * của khoá {@code tham_so.<tên>} (hoặc {@code <tên>} không tiền tố) là {@code mac_dinh} của tham số
 * trong khối {@code params} của manifest; khoá có tiền tố khác (ví dụ {@code catalog.*}) thuộc phần
 * kiểm của thành phần tương ứng nên bỏ qua ở đây. Không có khung nào thì không có vi phạm: hành vi
 * giữ đúng cờ {@code overridable} hiện có.
 */
public final class ParamConstraintCheck {

  static final String PARAM_PREFIX = "tham_so.";

  /** Một vi phạm: tham số, giá trị cấp dưới, khung (mô tả cho người dùng). */
  public record Violation(String paramKey, Object value, String frame, String message) {}

  private ParamConstraintCheck() {}

  /**
   * @param parentParams khối {@code params} của phiên bản cấp trên
   * @param childParams khối {@code params} của phiên bản cấp dưới
   * @param scope cấp của gói cấp dưới ({@code KHOI}, {@code TINH}, {@code DON_VI}), {@code null}
   *     nếu không rõ
   */
  public static List<Violation> check(
      Collection<ParamConstraint> constraints,
      Map<?, ?> parentParams,
      Map<?, ?> childParams,
      String scope) {
    List<Violation> out = new ArrayList<>();
    for (ParamConstraint c : constraints) {
      String name = paramName(c.paramKey());
      if (name == null) {
        continue;
      }
      boolean parentHas = parentParams != null && parentParams.containsKey(name);
      boolean childHas = childParams != null && childParams.containsKey(name);
      Object parent = parentHas ? value(parentParams.get(name)) : null;
      Object child = childHas ? value(childParams.get(name)) : null;
      if (!childHas && !parentHas) {
        continue;
      }
      boolean changed = !same(parent, child);
      if (changed
          && scope != null
          && !c.allowedScopes().isEmpty()
          && !c.allowedScopes().contains(scope)) {
        out.add(
            new Violation(
                c.paramKey(),
                child,
                "cấp được chỉnh " + c.allowedScopes(),
                "Cấp này không được chỉnh tham số " + c.paramKey()));
        continue;
      }
      if (!c.canToggle() && parentHas != childHas
          || !c.canToggle() && parent instanceof Boolean && changed) {
        out.add(
            new Violation(
                c.paramKey(),
                child,
                "không được bật tắt",
                "Không được bật tắt tham số " + c.paramKey()));
        continue;
      }
      if (!c.canChangeLevels()
          && parent instanceof Collection<?> pl
          && child instanceof Collection<?> cl
          && pl.size() != cl.size()) {
        out.add(
            new Violation(
                c.paramKey(),
                cl.size(),
                "giữ " + pl.size() + " mức",
                "Không được đổi số mức của tham số " + c.paramKey()));
        continue;
      }
      BigDecimal number = number(child);
      if (number == null || !changed) {
        continue;
      }
      if (c.minValue() != null && number.compareTo(c.minValue()) < 0
          || c.maxValue() != null && number.compareTo(c.maxValue()) > 0) {
        String frame = range(c.minValue(), c.maxValue());
        out.add(
            new Violation(
                c.paramKey(),
                number,
                frame,
                "Giá trị " + plain(number) + " của " + c.paramKey() + " ngoài khung " + frame));
        continue;
      }
      BigDecimal base = number(parent);
      if (base != null && "TANG".equals(c.tightenDirection()) && number.compareTo(base) < 0) {
        out.add(
            new Violation(
                c.paramKey(),
                number,
                "chỉ được tăng từ " + plain(base),
                "Tham số " + c.paramKey() + " chỉ được siết tăng, không thấp hơn " + plain(base)));
      } else if (base != null
          && "GIAM".equals(c.tightenDirection())
          && number.compareTo(base) > 0) {
        out.add(
            new Violation(
                c.paramKey(),
                number,
                "chỉ được giảm từ " + plain(base),
                "Tham số " + c.paramKey() + " chỉ được siết giảm, không cao hơn " + plain(base)));
      }
    }
    return out;
  }

  /** Tên tham số trong {@code params}; khoá thuộc thành phần khác trả {@code null}. */
  static String paramName(String key) {
    if (key.startsWith(PARAM_PREFIX)) {
      return key.substring(PARAM_PREFIX.length());
    }
    return key.contains(".") ? null : key;
  }

  private static Object value(Object declared) {
    return declared instanceof Map<?, ?> m && m.containsKey("mac_dinh")
        ? m.get("mac_dinh")
        : declared;
  }

  private static boolean same(Object a, Object b) {
    BigDecimal x = number(a);
    BigDecimal y = number(b);
    if (x != null && y != null) {
      return x.compareTo(y) == 0;
    }
    return a == null ? b == null : a.equals(b);
  }

  private static BigDecimal number(Object value) {
    if (value instanceof BigDecimal d) {
      return d;
    }
    if (value instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    return null;
  }

  private static String range(BigDecimal min, BigDecimal max) {
    return "[" + (min == null ? "−∞" : plain(min)) + "; " + (max == null ? "+∞" : plain(max)) + "]";
  }

  private static String plain(BigDecimal v) {
    return v.stripTrailingZeros().toPlainString().replace('.', ',');
  }
}
