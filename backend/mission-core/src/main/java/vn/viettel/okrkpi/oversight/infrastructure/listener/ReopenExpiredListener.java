package vn.viettel.okrkpi.oversight.infrastructure.listener;

import java.util.Objects;
import org.springframework.modulith.events.ApplicationModuleListener;
import vn.viettel.okrkpi.config.api.PeriodReopenExpiredEvent;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenFollowUpService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Hết hạn mở lại hậu kiểm (MOD-08 báo, CORE-08-06): đánh dấu "chưa hoàn tất hậu kiểm" khi còn hồ sơ
 * chưa xong (CORE-07-09). Đi qua Event Publication Registry nên chạy sau khi giao dịch của MOD-08
 * cam kết và không mất khi pod dừng; gọi lại an toàn.
 */
public class ReopenExpiredListener {

  private final ReopenFollowUpService service;

  public ReopenExpiredListener(ReopenFollowUpService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @ApplicationModuleListener
  public void on(PeriodReopenExpiredEvent event) {
    TenantContext.run(
        event.tenantId(), () -> service.onExpired(event.tenantId(), event.reopenRequestId()));
  }
}
