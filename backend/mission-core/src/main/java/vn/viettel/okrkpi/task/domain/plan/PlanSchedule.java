package vn.viettel.okrkpi.task.domain.plan;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Quy tắc thời gian của kế hoạch quý: phân vùng {@code period_bucket} và dịch hạn giữa các quý. */
public final class PlanSchedule {

  private PlanSchedule() {}

  /** {@code period_bucket} của kỳ quý: năm × 10 + quý của ngày bắt đầu (như {@code config}). */
  public static int bucketOf(LocalDate quarterStart) {
    return quarterStart.getYear() * 10 + (quarterStart.getMonthValue() - 1) / 3 + 1;
  }

  /** Phân vùng của quý liền trước. */
  public static int previousBucket(int bucket) {
    int year = bucket / 10;
    int quarter = bucket % 10;
    return quarter <= 1 ? (year - 1) * 10 + 4 : year * 10 + quarter - 1;
  }

  /**
   * Dịch hạn sang kỳ đích theo cùng độ lệch ngày tính từ đầu quý, kẹp trong kỳ đích (suy luận:
   * TL-02a chỉ ghi "hạn dịch theo kỳ").
   */
  public static LocalDate shiftDue(
      LocalDate due, LocalDate sourceStart, LocalDate targetStart, LocalDate targetEnd) {
    Objects.requireNonNull(due, "due");
    long offset = Math.max(0, ChronoUnit.DAYS.between(sourceStart, due));
    LocalDate shifted = targetStart.plusDays(offset);
    return shifted.isAfter(targetEnd) ? targetEnd : shifted;
  }
}
