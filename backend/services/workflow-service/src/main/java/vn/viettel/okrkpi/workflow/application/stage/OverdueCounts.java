package vn.viettel.okrkpi.workflow.application.stage;

import java.util.Map;
import java.util.TreeMap;

/** Số lần quá hạn theo bước trong biến {@code soLanQuaHan}, khuôn {@code elementId=n,...}. */
public final class OverdueCounts {

  private OverdueCounts() {}

  /** Đọc biến; sai dạng coi như chưa quá hạn lần nào. */
  public static Map<String, Integer> decode(Object raw) {
    Map<String, Integer> out = new TreeMap<>();
    if (raw == null || raw.toString().isBlank()) {
      return out;
    }
    for (String part : raw.toString().split(",", -1)) {
      int eq = part.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      try {
        out.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
      } catch (NumberFormatException e) {
        // Bỏ phần sai dạng.
      }
    }
    return out;
  }

  /** Số lần quá hạn của bước. */
  public static int of(Object raw, String elementId) {
    return decode(raw).getOrDefault(elementId, 0);
  }

  /** Tăng số lần quá hạn của bước, trả biến mới. */
  public static String increment(Object raw, String elementId) {
    Map<String, Integer> counts = decode(raw);
    counts.merge(elementId, 1, Integer::sum);
    StringBuilder sb = new StringBuilder();
    counts.forEach(
        (k, v) -> {
          if (!sb.isEmpty()) {
            sb.append(',');
          }
          sb.append(k).append('=').append(v);
        });
    return sb.toString();
  }
}
