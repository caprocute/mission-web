package vn.viettel.okrkpi.policy.infrastructure.listener;

import java.util.Objects;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.config.api.PeriodCreatedEvent;
import vn.viettel.okrkpi.policy.application.PolicyResolutionService;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Nhận sự kiện "kỳ đã tạo" của MOD-08 và ghim phiên bản Policy Pack đang hiệu lực vào {@code
 * policy.policy_binding} cho kỳ (BR-08-07, CORE-05-03).
 */
@Component
public class PolicyPeriodCreatedListener {

  private final PolicyResolutionService resolution;

  public PolicyPeriodCreatedListener(PolicyResolutionService resolution) {
    this.resolution = Objects.requireNonNull(resolution, "resolution");
  }

  @ApplicationModuleListener
  public void onPeriodCreated(PeriodCreatedEvent event) {
    TenantContext.run(
        event.tenantId(),
        () ->
            resolution.pinForPeriod(
                event.tenantId(), event.periodId(), event.flow(), event.startDate()));
  }
}
