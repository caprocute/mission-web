package vn.viettel.okrkpi.product.domain.evidence;

/**
 * Ngưỡng nhắc quá hạn nghiệm thu của QT-07 (BR-07-12, MSG-07-14): minh chứng chờ nghiệm thu quá
 * {@code k} ngày làm việc kể từ ngày nộp thì nhắc người nghiệm thu và cấp trên. Ngày nộp không
 * tính; chờ đúng {@code k} ngày làm việc chưa nhắc, sang ngày làm việc thứ {@code k + 1} mới nhắc.
 */
public final class ReminderRules {

  /** Giá trị mặc định của tham số Policy Pack {@code acceptance_sla_days} (BR-07-12). */
  public static final int DEFAULT_SLA_DAYS = 5;

  /** Trần hợp lý của tham số để chặn giá trị sai trong gói (suy luận). */
  public static final int MAX_SLA_DAYS = 60;

  private ReminderRules() {}

  /** Quá hạn khi số ngày làm việc đã chờ vượt ngưỡng. */
  public static boolean overdue(long waitedWorkingDays, int slaDays) {
    return waitedWorkingDays > slaDays;
  }

  /**
   * Ngưỡng từ giá trị tham số; thiếu, không phải số nguyên dương hay quá trần thì dùng mặc định.
   */
  public static int slaDays(Object parameter) {
    if (parameter instanceof Number n) {
      double d = n.doubleValue();
      if (d == Math.rint(d) && d >= 1 && d <= MAX_SLA_DAYS) {
        return (int) d;
      }
      return DEFAULT_SLA_DAYS;
    }
    if (parameter instanceof String s) {
      try {
        return slaDays(Integer.parseInt(s.strip()));
      } catch (NumberFormatException e) {
        return DEFAULT_SLA_DAYS;
      }
    }
    return DEFAULT_SLA_DAYS;
  }
}
