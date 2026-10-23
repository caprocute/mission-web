package vn.viettel.okrkpi.workflow.domain.monitor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.viettel.okrkpi.workflow.domain.approval.WorkingDays;

/**
 * Quy tắc gia hạn một bước ({@code extendWorkflowTask}, BS-01 SCR-1506): hạn mới phải sau hạn hiện
 * tại và sau thời điểm thao tác; hạn rơi vào ngày nghỉ được dời tới ngày làm việc kế tiếp, giữ
 * nguyên giờ; số ngày làm việc được gia thêm không vượt giới hạn tham số của mẫu (suy luận, wiki
 * trang 14 mục DV-06-10).
 */
public final class DueExtension {

  /** Trần cứng của giới hạn gia hạn, phòng tham số khai sai (suy luận). */
  public static final int HARD_MAX_WORKING_DAYS = 60;

  private DueExtension() {}

  /**
   * Tính hạn mới.
   *
   * @param currentDue hạn hiện tại, {@code null} khi bước chưa có hạn (tính từ {@code now})
   * @param requestedDue hạn người dùng đề nghị
   * @param now thời điểm thao tác
   * @param maxWorkingDays số ngày làm việc tối đa được gia thêm
   * @param overrides lịch nghỉ của tenant (ngày làm bù {@code true}, ngày nghỉ {@code false})
   * @param zone múi giờ nghiệp vụ
   */
  public static Result evaluate(
      Instant currentDue,
      Instant requestedDue,
      Instant now,
      int maxWorkingDays,
      Map<LocalDate, Boolean> overrides,
      ZoneId zone) {
    Objects.requireNonNull(requestedDue, "requestedDue");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(overrides, "overrides");
    Objects.requireNonNull(zone, "zone");
    if (!requestedDue.isAfter(now)) {
      return Result.rejected(Violation.NOT_IN_FUTURE);
    }
    Instant base = currentDue == null || currentDue.isBefore(now) ? now : currentDue;
    if (currentDue != null && !requestedDue.isAfter(currentDue)) {
      return Result.rejected(Violation.NOT_LATER);
    }
    ZonedDateTime requested = requestedDue.atZone(zone);
    LocalDate day = requested.toLocalDate();
    int guard = 0;
    while (!WorkingDays.isWorkingDay(day, overrides)) {
      day = day.plusDays(1);
      if (++guard > 366) {
        throw new IllegalStateException("Lịch không có ngày làm việc trong một năm");
      }
    }
    Instant effective = requested.with(day).toInstant();
    int added = workingDaysBetween(base.atZone(zone).toLocalDate(), day, overrides);
    int limit = Math.min(Math.max(0, maxWorkingDays), HARD_MAX_WORKING_DAYS);
    if (added > limit) {
      return new Result(Optional.of(Violation.OVER_LIMIT), effective, added, limit);
    }
    return new Result(Optional.empty(), effective, added, limit);
  }

  /** Số ngày làm việc trong đoạn {@code (from, to]}. */
  static int workingDaysBetween(LocalDate from, LocalDate to, Map<LocalDate, Boolean> overrides) {
    int count = 0;
    for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
      if (WorkingDays.isWorkingDay(d, overrides)) {
        count++;
      }
    }
    return count;
  }

  /** Lý do từ chối gia hạn. */
  public enum Violation {
    /** Hạn mới không sau thời điểm thao tác. */
    NOT_IN_FUTURE,
    /** Hạn mới không sau hạn hiện tại. */
    NOT_LATER,
    /** Vượt giới hạn ngày làm việc được gia thêm. */
    OVER_LIMIT
  }

  /**
   * Kết quả.
   *
   * @param violation lý do từ chối, rỗng khi hợp lệ
   * @param newDue hạn mới sau khi dời khỏi ngày nghỉ, {@code null} khi bị từ chối trước khi tính
   * @param addedWorkingDays số ngày làm việc được gia thêm
   * @param limit giới hạn đã áp
   */
  public record Result(
      Optional<Violation> violation, Instant newDue, int addedWorkingDays, int limit) {

    static Result rejected(Violation v) {
      return new Result(Optional.of(v), null, 0, 0);
    }

    public boolean accepted() {
      return violation.isEmpty();
    }
  }
}
