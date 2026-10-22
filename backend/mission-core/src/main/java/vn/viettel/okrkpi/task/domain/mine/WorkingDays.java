package vn.viettel.okrkpi.task.domain.mine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Bảng cộng dồn ngày làm việc trên một khoảng ngày, dựng một lần cho cả trang để tính "Trễ n ngày",
 * "Còn n ngày" mà không hỏi lịch nghỉ cho từng dòng.
 */
public final class WorkingDays {

  /** Khoảng tối đa dựng bảng (hai năm) để một hạn sai không làm vòng lặp dài. */
  static final int MAX_SPAN = 732;

  private final LocalDate from;
  private final int[] cumulative;

  private WorkingDays(LocalDate from, int[] cumulative) {
    this.from = from;
    this.cumulative = cumulative;
  }

  /** Dựng bảng cho {@code [from, to]}; {@code working} trả {@code true} cho ngày làm việc. */
  public static WorkingDays over(LocalDate from, LocalDate to, Predicate<LocalDate> working) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(working, "working");
    LocalDate end = to.isBefore(from) ? from : to;
    int span = (int) Math.min(ChronoUnit.DAYS.between(from, end), MAX_SPAN);
    int[] cum = new int[span + 1];
    for (int i = 1; i <= span; i++) {
      cum[i] = cum[i - 1] + (working.test(from.plusDays(i)) ? 1 : 0);
    }
    return new WorkingDays(from, cum);
  }

  /**
   * Số ngày làm việc từ {@code today} tới {@code due}: dương là còn bấy nhiêu ngày (không tính hôm
   * nay, tính ngày hạn), âm là đã trễ bấy nhiêu ngày, 0 là hạn hôm nay hoặc chỉ cách ngày nghỉ.
   */
  public int between(LocalDate today, LocalDate due) {
    return at(due) - at(today);
  }

  private int at(LocalDate day) {
    long i = ChronoUnit.DAYS.between(from, day);
    if (i <= 0) {
      return 0;
    }
    return cumulative[(int) Math.min(i, cumulative.length - 1)];
  }

  /** Ngày sau {@code n} ngày làm việc kể từ {@code start} (không tính {@code start}). */
  public static LocalDate after(LocalDate start, int n, Predicate<LocalDate> working) {
    LocalDate day = start;
    int left = n;
    for (int i = 0; i < MAX_SPAN && left > 0; i++) {
      day = day.plusDays(1);
      if (working.test(day)) {
        left--;
      }
    }
    return day;
  }
}
