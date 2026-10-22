package vn.viettel.okrkpi.policy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * So kết quả tính với kết quả mong đợi của ca vàng (CORE-05-07): số so sau khi làm tròn {@code
 * scale: 2, HALF_UP} (khử sai số nhị phân ở 10 chữ số trước, như CORE-05-04), danh sách so từng
 * phần tử, giá trị khác so bằng nhau. Mức xếp loại so thứ hạng cho ca của gói cha (TL-01 X-PP-11).
 */
public final class ResultComparison {

  /** Khoá mong đợi không so: mã dòng DMN trong tệp gói, hệ thống sinh lại khi lưu bảng. */
  public static final Set<String> IGNORED = Set.of("dong_dmn");

  public static final int SCALE = 2;

  /** Thứ tự mức từ cao xuống thấp (baseline mục 9.1). */
  public static final List<String> LEVELS = List.of("HTXSNV", "HTTNV", "HTNV", "KHTNV");

  private ResultComparison() {}

  /**
   * Khác biệt theo khoá mong đợi: mỗi khoá lệch có {@code {"expected": .., "actual": ..}}; rỗng khi
   * khớp.
   */
  public static Map<String, Object> diff(Map<String, Object> expected, Map<String, Object> actual) {
    Map<String, Object> out = new LinkedHashMap<>();
    expected.forEach(
        (k, want) -> {
          if (IGNORED.contains(k)) {
            return;
          }
          Object got = actual.get(k);
          if (!same(want, got, SCALE)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("expected", want);
            d.put("actual", got);
            out.put(k, d);
          }
        });
    return out;
  }

  /** Kết quả mong đợi đã lọc khoá không so, giữ thứ tự. */
  public static Map<String, Object> actualFor(
      Map<String, Object> expected, Map<String, Object> actual) {
    Map<String, Object> out = new LinkedHashMap<>();
    expected.keySet().stream()
        .filter(k -> !IGNORED.contains(k))
        .forEach(k -> out.put(k, actual.get(k)));
    return out;
  }

  /** Hai giá trị bằng nhau; số so sau khi làm tròn tới {@code scale} chữ số. */
  public static boolean same(Object a, Object b, int scale) {
    if (a instanceof Number x && b instanceof Number y) {
      return round(x, scale).compareTo(round(y, scale)) == 0;
    }
    if (a instanceof Collection<?> x && b instanceof Collection<?> y) {
      if (x.size() != y.size()) {
        return false;
      }
      var i = x.iterator();
      var j = y.iterator();
      while (i.hasNext()) {
        if (!same(i.next(), j.next(), scale)) {
          return false;
        }
      }
      return true;
    }
    if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
      if (!x.keySet().equals(y.keySet())) {
        return false;
      }
      return x.entrySet().stream().allMatch(e -> same(e.getValue(), y.get(e.getKey()), scale));
    }
    return Objects.equals(a, b);
  }

  /** Làm tròn hai bước: 10 chữ số rồi {@code scale} chữ số, cùng {@code HALF_UP}. */
  public static BigDecimal round(Number n, int scale) {
    BigDecimal v =
        n instanceof BigDecimal d
            ? d
            : n instanceof Double || n instanceof Float
                ? BigDecimal.valueOf(n.doubleValue())
                : new BigDecimal(n.toString());
    return v.setScale(10, RoundingMode.HALF_UP).setScale(scale, RoundingMode.HALF_UP);
  }

  /**
   * Mức {@code level} cao hơn {@code reference} (ca vàng của gói cha chạy trên gói con không được
   * cho mức cao hơn, X-PP-11). Mức không thuộc thang bốn mức coi như không so được và không cao
   * hơn.
   */
  public static boolean higherThan(Object level, Object reference) {
    int a = LEVELS.indexOf(String.valueOf(level));
    int b = LEVELS.indexOf(String.valueOf(reference));
    return a >= 0 && b >= 0 && a < b;
  }
}
