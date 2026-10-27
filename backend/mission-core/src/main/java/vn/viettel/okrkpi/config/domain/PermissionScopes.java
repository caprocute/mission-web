package vn.viettel.okrkpi.config.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * Phạm vi quyền của ma trận theo cách viết của hợp đồng (BR-01-23): thứ tự rộng hẹp N &gt; Tn &gt;
 * Cd &gt; Dv &gt; B; {@code Gn} (được giao, uỷ quyền) đứng riêng nên chỉ giữ nguyên được.
 */
public final class PermissionScopes {

  private static final Map<String, Integer> RANK =
      Map.of("B", 1, "Dv", 2, "Cd", 3, "Tn", 4, "N", 5);

  private PermissionScopes() {}

  public static boolean known(String scope) {
    return RANK.containsKey(scope) || "Gn".equals(scope);
  }

  /** {@code chosen} không rộng hơn {@code base}. */
  public static boolean within(String chosen, String base) {
    if (chosen == null || base == null) {
      return false;
    }
    if (chosen.equals(base)) {
      return true;
    }
    Integer c = RANK.get(chosen);
    Integer b = RANK.get(base);
    return c != null && b != null && c < b;
  }

  /**
   * Mã ngắn ASCII từ tên vai trò: bỏ dấu, chữ thường, ký tự khác chữ số thành gạch dưới, cắt còn
   * {@code max} ký tự.
   */
  public static String slug(String name, int max) {
    String folded =
        Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
    if (folded.length() > max) {
      folded = folded.substring(0, max).replaceAll("_+$", "");
    }
    return folded.isEmpty() ? "vai_tro" : folded;
  }
}
