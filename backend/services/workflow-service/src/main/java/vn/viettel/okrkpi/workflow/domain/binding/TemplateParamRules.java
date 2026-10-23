package vn.viettel.okrkpi.workflow.domain.binding;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Khai báo tham số của một mẫu luồng (khối {@code thamSo} của {@code <mã>.mau.json}): khoảng số cấp
 * mẫu cho phép, các cấp bắt buộc ký số, các cấp được đặt điều kiện bỏ qua và các cấp mặc định để
 * xem trước. Cấp được chỉ bằng {@code CAP_DAU}, {@code CAP_GIUA}, {@code CAP_CUOI} hoặc mã bước
 * ({@code buoc}).
 *
 * @param minLevels số cấp tối thiểu
 * @param maxLevels số cấp tối đa
 * @param signRequired cấp bắt buộc ký số
 * @param skippable cấp được đặt điều kiện bỏ qua
 * @param defaultLevels các cấp mặc định (khoá như {@code params_json.cap})
 */
public record TemplateParamRules(
    int minLevels,
    int maxLevels,
    Set<String> signRequired,
    Set<String> skippable,
    List<Map<String, Object>> defaultLevels) {

  /** Khoảng số cấp của SCR-1504 (bảng trường dòng 3). */
  public static final int MIN_LEVELS = 2;

  public static final int MAX_LEVELS = 4;

  public static final String FIRST = "CAP_DAU";
  public static final String MIDDLE = "CAP_GIUA";
  public static final String LAST = "CAP_CUOI";

  public TemplateParamRules {
    if (minLevels < MIN_LEVELS || maxLevels > MAX_LEVELS || minLevels > maxLevels) {
      throw new IllegalArgumentException("Khoảng số cấp của mẫu phải nằm trong 2 đến 4");
    }
    signRequired = signRequired == null ? Set.of() : Set.copyOf(signRequired);
    skippable = skippable == null ? Set.of() : Set.copyOf(skippable);
    defaultLevels = defaultLevels == null ? List.of() : List.copyOf(defaultLevels);
  }

  /** Mặc định khi mẫu không khai {@code thamSo}. */
  public static TemplateParamRules defaults() {
    return new TemplateParamRules(MIN_LEVELS, MAX_LEVELS, Set.of(), Set.of(), List.of());
  }

  /** Cấp thứ {@code order} (từ 1) trên {@code total} cấp có khớp một trong các chỉ dẫn không. */
  static boolean matches(Set<String> markers, int order, int total, String stepKey) {
    Objects.requireNonNull(markers, "markers");
    for (String marker : markers) {
      boolean hit =
          switch (marker) {
            case FIRST -> order == 1;
            case LAST -> order == total;
            case MIDDLE -> order > 1 && order < total;
            default -> marker.equals(stepKey);
          };
      if (hit) {
        return true;
      }
    }
    return false;
  }

  public boolean mustSign(int order, int total, String stepKey) {
    return matches(signRequired, order, total, stepKey);
  }

  public boolean canSkip(int order, int total, String stepKey) {
    return matches(skippable, order, total, stepKey);
  }
}
