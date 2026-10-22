package vn.viettel.okrkpi.config.api;

import java.util.Objects;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Sự kiện ứng dụng (trong tiến trình) khi lịch nghỉ, làm bù của tenant thay đổi (CORE-08-04);
 * mô-đun có đệm hạn tính theo ngày làm việc nghe sự kiện này để làm mới.
 */
public record HolidayCalendarChangedEvent(TenantId tenantId, int year) {
  public HolidayCalendarChangedEvent {
    Objects.requireNonNull(tenantId, "tenantId");
  }
}
