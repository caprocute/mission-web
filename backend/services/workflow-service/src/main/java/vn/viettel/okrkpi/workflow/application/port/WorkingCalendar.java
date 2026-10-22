package vn.viettel.okrkpi.workflow.application.port;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Cổng đọc lịch nghỉ của tenant để đổi hạn theo ngày làm việc ra ngày cụ thể. */
public interface WorkingCalendar {

  /**
   * Các ngày khai riêng trong đoạn {@code [from, to]}: {@code true} là ngày làm bù, {@code false}
   * là ngày nghỉ. Ngày không có trong kết quả theo quy tắc thứ Bảy, Chủ nhật nghỉ.
   */
  Map<LocalDate, Boolean> overrides(UUID tenantId, LocalDate from, LocalDate to);
}
