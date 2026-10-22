package vn.viettel.okrkpi.task.domain.change;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Quy tắc của đề nghị điều chỉnh: năm loại theo hợp đồng, lý do 20–2.000 ký tự, hệ số C của
 * BR-06-01 và ngưỡng chuyển cấp trên của BR-06-06.
 */
public final class ChangeRules {

  public static final Set<String> TYPES =
      Set.of("GIA_HAN", "DOI_SO_LUONG", "DOI_SAN_PHAM", "TAM_DUNG", "HUY");
  public static final Set<String> CAUSES = Set.of("KHACH_QUAN", "CHU_QUAN");
  public static final String PENDING = "CHO_DUYET";
  public static final int REASON_MIN = 20;
  public static final int REASON_MAX = 2000;

  private ChangeRules() {}

  /** Lý do sau khi bỏ khoảng trắng hai đầu dài 20–2.000 ký tự. */
  public static boolean validReason(String reason) {
    if (reason == null) {
      return false;
    }
    int n = reason.strip().length();
    return n >= REASON_MIN && n <= REASON_MAX;
  }

  /** Hệ số tiến độ C = 1 − bước × số ngày làm việc chậm chủ quan, không nhỏ hơn 0 (BR-06-01). */
  public static BigDecimal coefficient(BigDecimal step, long subjectiveDays) {
    BigDecimal c =
        BigDecimal.ONE.subtract(step.multiply(BigDecimal.valueOf(Math.max(0, subjectiveDays))));
    return c.signum() < 0 ? BigDecimal.ZERO.setScale(2) : c.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Gia hạn vượt ngưỡng người giao (BR-06-06): lần gia hạn thứ {@code maxPerQuarter + 1} trở đi
   * trong quý, hoặc dời quá {@code maxDays} ngày làm việc.
   */
  public static boolean overThreshold(
      int extensionNo, long extraDays, int maxPerQuarter, int maxDays) {
    return extensionNo > maxPerQuarter || extraDays > maxDays;
  }
}
