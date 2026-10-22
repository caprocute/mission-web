package vn.viettel.okrkpi.scoring.domain;

import java.util.Objects;

/**
 * Một khoảng chậm của nhiệm vụ tính tới ngày nộp phiên bản minh chứng cuối được xác nhận: số ngày
 * làm việc, số ngày lịch và nguyên nhân. Khoảng giữa phiên bản bị yêu cầu bổ sung và phiên bản đạt
 * mang nguyên nhân chỉnh sửa người nghiệm thu chọn ({@code revision_cause}, QT-07 bước 9); chỉ
 * khoảng chủ quan bị trừ vào C (BR-09-01).
 */
public record DelaySegment(int workdays, int calendarDays, Cause cause) {

  /** Nguyên nhân chậm. */
  public enum Cause {
    CHU_QUAN,
    KHACH_QUAN
  }

  public DelaySegment {
    if (workdays < 0 || calendarDays < 0) {
      throw new IllegalArgumentException("Số ngày chậm không âm");
    }
    if (workdays > calendarDays) {
      throw new IllegalArgumentException("Số ngày làm việc chậm không vượt số ngày lịch");
    }
    Objects.requireNonNull(cause, "cause");
  }
}
