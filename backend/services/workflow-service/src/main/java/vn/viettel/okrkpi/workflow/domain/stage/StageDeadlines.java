package vn.viettel.okrkpi.workflow.domain.stage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.workflow.domain.approval.WorkingDays;

/**
 * Hạn và mốc nhắc của một bước theo lịch ngày làm việc của tenant (thứ Bảy, Chủ nhật nghỉ, trừ ngày
 * làm bù; ngày lễ do lịch nghỉ khai). Hạn là 17 giờ của ngày làm việc thứ {@code n} sau ngày bắt
 * đầu như DV-06-03; nhắc lúc 8 giờ của ngày làm việc đứng trước hạn {@code remindBefore} ngày (suy
 * luận).
 */
public final class StageDeadlines {

  /** Giờ Việt Nam. */
  public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  /** Mốc hạn trong ngày. */
  public static final LocalTime DUE_TIME = LocalTime.of(17, 0);

  /** Mốc nhắc trong ngày. */
  public static final LocalTime REMIND_TIME = LocalTime.of(8, 0);

  private StageDeadlines() {}

  /**
   * Hạn và mốc nhắc.
   *
   * @param due hạn
   * @param remind mốc nhắc, không sớm hơn lúc tính
   */
  public record Schedule(Instant due, Instant remind) {

    public Schedule {
      Objects.requireNonNull(due, "due");
      Objects.requireNonNull(remind, "remind");
    }
  }

  /** Ngày hạn sau {@code days} ngày làm việc kể từ ngày {@code start} (không tính ngày đầu). */
  public static LocalDate dueDay(LocalDate start, int days, Map<LocalDate, Boolean> overrides) {
    return WorkingDays.plus(start, days, overrides);
  }

  /** Lùi {@code days} ngày làm việc từ {@code day} (không tính chính {@code day}). */
  public static LocalDate minus(LocalDate day, int days, Map<LocalDate, Boolean> overrides) {
    LocalDate current = day;
    int remaining = days;
    int scanned = 0;
    while (remaining > 0) {
      current = current.minusDays(1);
      if (++scanned > 366) {
        throw new IllegalStateException("Lịch không có đủ ngày làm việc trong một năm");
      }
      if (WorkingDays.isWorkingDay(current, overrides)) {
        remaining--;
      }
    }
    return current;
  }

  /**
   * Lịch của bước.
   *
   * @param now lúc vào bước
   * @param dueDay ngày hạn
   * @param remindBefore số ngày làm việc nhắc trước
   */
  public static Schedule schedule(
      Instant now, LocalDate dueDay, int remindBefore, Map<LocalDate, Boolean> overrides) {
    Instant due = dueDay.atTime(DUE_TIME).atZone(ZONE).toInstant();
    Instant remind =
        minus(dueDay, remindBefore, overrides).atTime(REMIND_TIME).atZone(ZONE).toInstant();
    if (remind.isBefore(now)) {
      remind = now;
    }
    if (remind.isAfter(due)) {
      remind = due;
    }
    return new Schedule(due, remind);
  }
}
