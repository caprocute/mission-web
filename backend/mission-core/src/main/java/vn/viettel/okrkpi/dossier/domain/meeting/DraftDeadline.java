package vn.viettel.okrkpi.dossier.domain.meeting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Kiểm tra thời hạn gửi dự thảo bản kiểm điểm (QT-11 bước 5, BR-11-01, QĐ Điều 6): đếm số ngày làm
 * việc trọn vẹn nằm giữa ngày gửi và ngày hội nghị, không tính chính hai ngày đó (mặc định của tham
 * số {@code kiem_diem.cach_dem_ngay}, wiki trang 14). Ví dụ của TL-02b: gửi 16:20 thứ Sáu
 * 08/01/2027, họp thứ Năm 14/01/2027 thì đếm 11, 12, 13/01 là 3 ngày.
 */
public final class DraftDeadline {

  /** Số ngày làm việc tối thiểu mặc định (BR-11-01). */
  public static final int REQUIRED_WORKING_DAYS = 3;

  public static final String LEGAL_REF = "QĐ Điều 6";

  /** Mã thông báo cảnh báo khi chưa đủ thời hạn. */
  public static final String MSG_NOT_ENOUGH = "MSG-11-01";

  /** Cách đếm mặc định: chỉ ngày làm việc trọn vẹn nằm giữa hai mốc. */
  public static final String METHOD_WORKING_DAYS_BETWEEN = "NGAY_LAM_VIEC";

  /** Đếm ngày làm việc trong khoảng từ sau {@code fromExclusive} đến hết {@code toInclusive}. */
  @FunctionalInterface
  public interface WorkingDayCounter {
    long count(LocalDate fromExclusive, LocalDate toInclusive);
  }

  /**
   * Kết quả: {@code workingDays} rỗng khi chưa gửi dự thảo; khi đó {@code passed} là {@code false}
   * và không có mã thông báo.
   */
  public record Result(Integer workingDays, int requiredWorkingDays, boolean passed) {

    public String msg() {
      return workingDays != null && !passed ? MSG_NOT_ENOUGH : null;
    }
  }

  private DraftDeadline() {}

  public static Result check(
      Instant draftSentAt,
      Instant meetingAt,
      ZoneId zone,
      int requiredWorkingDays,
      WorkingDayCounter counter) {
    Objects.requireNonNull(meetingAt, "meetingAt");
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(counter, "counter");
    if (draftSentAt == null) {
      return new Result(null, requiredWorkingDays, false);
    }
    LocalDate sent = draftSentAt.atZone(zone).toLocalDate();
    LocalDate lastBefore = meetingAt.atZone(zone).toLocalDate().minusDays(1);
    long days = lastBefore.isAfter(sent) ? counter.count(sent, lastBefore) : 0;
    int n = (int) Math.min(Integer.MAX_VALUE, Math.max(0, days));
    return new Result(n, requiredWorkingDays, n >= requiredWorkingDays);
  }
}
