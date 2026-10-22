package vn.viettel.okrkpi.task.domain.similar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Chuẩn hoá và so khớp tên nhiệm vụ cho BR-05-06. {@link #fingerprint} băm kỳ, tên, sản phẩm và chủ
 * trì đã chuẩn hoá (chữ thường, bỏ dấu câu, gộp khoảng trắng) để chỉ mục {@code ix_task__dup} bắt
 * trùng gần như nguyên văn; {@link #similarity} là hệ số Dice trên bộ ba ký tự của tên đã bỏ dấu
 * tiếng Việt, ngưỡng gần giống {@link #THRESHOLD}.
 */
public final class TaskSimilarity {

  /** Ngưỡng tương đồng của TL-02a SCR-0501. */
  public static final BigDecimal THRESHOLD = new BigDecimal("0.70");

  private TaskSimilarity() {}

  /** Chữ thường, bỏ dấu câu và ký hiệu, gộp khoảng trắng; giữ dấu tiếng Việt. */
  public static String normalize(String text) {
    if (text == null) {
      return "";
    }
    String nfc = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder(nfc.length());
    boolean space = true;
    for (int i = 0; i < nfc.length(); ) {
      int cp = nfc.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.isLetterOrDigit(cp)) {
        out.appendCodePoint(cp);
        space = false;
      } else if (!space) {
        out.append(' ');
        space = true;
      }
    }
    int end = out.length();
    while (end > 0 && out.charAt(end - 1) == ' ') {
      end--;
    }
    return out.substring(0, end);
  }

  /** Bỏ dấu tiếng Việt sau khi chuẩn hoá, để so khớp không phân biệt dấu. */
  static String fold(String text) {
    String d = Normalizer.normalize(normalize(text), Normalizer.Form.NFD);
    return d.replaceAll("\\p{M}+", "").replace('đ', 'd');
  }

  /** Dấu vân tay chống trùng của nhiệm vụ. */
  public static byte[] fingerprint(UUID periodId, String title, String product, UUID lead) {
    String text = periodId + "|" + normalize(title) + "|" + normalize(product) + "|" + lead;
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Hệ số Dice trên bộ ba ký tự (0 đến 1, hai chữ số thập phân). */
  public static BigDecimal similarity(String a, String b) {
    String x = " " + fold(a) + " ";
    String y = " " + fold(b) + " ";
    if (x.isBlank() || y.isBlank()) {
      return BigDecimal.ZERO.setScale(2);
    }
    if (x.equals(y)) {
      return BigDecimal.ONE.setScale(2);
    }
    Map<String, Integer> grams = grams(x);
    int total = x.length() - 2 + Math.max(0, y.length() - 2);
    int common = 0;
    for (int i = 0; i + 3 <= y.length(); i++) {
      String g = y.substring(i, i + 3);
      Integer n = grams.get(g);
      if (n != null && n > 0) {
        common++;
        grams.put(g, n - 1);
      }
    }
    return BigDecimal.valueOf(2.0 * common / Math.max(1, total)).setScale(2, RoundingMode.HALF_UP);
  }

  /** Tên {@code candidate} gần giống {@code title} theo ngưỡng. */
  public static boolean similar(String title, String candidate) {
    return similarity(title, candidate).compareTo(THRESHOLD) >= 0;
  }

  private static Map<String, Integer> grams(String s) {
    Map<String, Integer> m = new HashMap<>();
    for (int i = 0; i + 3 <= s.length(); i++) {
      m.merge(s.substring(i, i + 3), 1, Integer::sum);
    }
    return m;
  }
}
