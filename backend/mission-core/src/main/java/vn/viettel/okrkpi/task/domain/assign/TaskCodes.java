package vn.viettel.okrkpi.task.domain.assign;

/** Mã nhiệm vụ {@code NV-yyyy-Qn-nnnn}, duy nhất trong tenant (TL-02a SCR-0501 trường 1). */
public final class TaskCodes {

  private TaskCodes() {}

  /** Tiền tố theo phân vùng kỳ {@code năm*10 + quý}; kỳ năm (quý 0) dùng {@code N}. */
  public static String prefix(int periodBucket) {
    int year = periodBucket / 10;
    int quarter = periodBucket % 10;
    return "NV-" + year + "-" + (quarter == 0 ? "N" : "Q" + quarter) + "-";
  }

  /** Mã thứ {@code n} của tiền tố, ít nhất bốn chữ số. */
  public static String code(int periodBucket, int n) {
    if (n < 1) {
      throw new IllegalArgumentException("n");
    }
    return prefix(periodBucket) + String.format("%04d", n);
  }

  /** Số thứ tự trong mã có tiền tố của phân vùng; 0 khi mã không theo mẫu. */
  public static int sequenceOf(int periodBucket, String code) {
    String p = prefix(periodBucket);
    if (code == null || !code.startsWith(p)) {
      return 0;
    }
    try {
      return Integer.parseInt(code.substring(p.length()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
