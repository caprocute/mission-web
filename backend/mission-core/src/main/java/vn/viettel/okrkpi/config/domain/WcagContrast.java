package vn.viettel.okrkpi.config.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tỷ lệ tương phản WCAG 2.1 giữa hai màu (CORE-08-10, BR-18-01, BR-18-09), cùng công thức với
 * {@code contrastRatio} của {@code @vt/design-system} (WEB-004). Nhận {@code #RGB}, {@code
 * #RRGGBB}, {@code #RRGGBBFF} và {@code rgb(r, g, b)}; màu trong suốt một phần không kiểm được
 * tương phản nên bị coi là không hợp lệ.
 */
public final class WcagContrast {

  /** Ngưỡng AA cho chữ thường. */
  public static final double AA = 4.5;

  /** Ngưỡng của chế độ Đảng viên cao tuổi (BR-18-09). */
  public static final double ELDERLY = 7.0;

  private static final Pattern HEX =
      Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{6}[fF]{2})$");
  private static final Pattern RGB =
      Pattern.compile("^rgb\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)$");

  private WcagContrast() {}

  /** Màu đọc được để tính tương phản hay không. */
  public static boolean parsable(String color) {
    try {
      rgb(color);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * @throws IllegalArgumentException màu không đọc được
   */
  public static double ratio(String a, String b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  /** Tỷ lệ làm tròn xuống hai chữ số, để hiển thị không bao giờ đẹp hơn thực tế. */
  public static String format(double ratio) {
    return String.format(Locale.ROOT, "%.2f", Math.floor(ratio * 100) / 100).replace('.', ',');
  }

  private static double luminance(String color) {
    int[] c = rgb(color);
    double[] l = new double[3];
    for (int i = 0; i < 3; i++) {
      double v = c[i] / 255.0;
      l[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
    return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2];
  }

  private static int[] rgb(String color) {
    String s = color == null ? "" : color.trim();
    Matcher hex = HEX.matcher(s);
    if (hex.matches()) {
      String h = hex.group(1);
      if (h.length() == 3) {
        h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
      }
      return new int[] {
        Integer.parseInt(h.substring(0, 2), 16),
        Integer.parseInt(h.substring(2, 4), 16),
        Integer.parseInt(h.substring(4, 6), 16)
      };
    }
    Matcher rgb = RGB.matcher(s.toLowerCase(Locale.ROOT));
    if (rgb.matches()) {
      int[] c = new int[3];
      for (int i = 0; i < 3; i++) {
        c[i] = Integer.parseInt(rgb.group(i + 1));
        if (c[i] > 255) {
          throw new IllegalArgumentException("Màu không hợp lệ: " + color);
        }
      }
      return c;
    }
    throw new IllegalArgumentException("Màu không hợp lệ: " + color);
  }
}
