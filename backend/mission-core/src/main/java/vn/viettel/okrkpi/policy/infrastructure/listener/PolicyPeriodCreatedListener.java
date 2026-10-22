package vn.viettel.okrkpi.policy.infrastructure.listener;

import java.util.Objects;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.config.api.PeriodCreatedEvent;
import vn.viettel.okrkpi.policy.application.DefaultPolicyQueryService;
import vn.viettel.okrkpi.policy.application.PolicyResolution;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Nhận sự kiện nội bộ "kỳ đã tạo" của MOD-08 (Spring Modulith Event Publication Registry) và ghim
 * phiên bản Policy Pack đang hiệu lực vào {@code policy.policy_binding} cho kỳ (BR-08-07,
 * CORE-05-03). Kỳ chưa có gói hiệu lực thì bỏ qua; lần phân giải đầu tiên sau khi gói được phát
 * hành sẽ ghim.
 */
@Component
public class PolicyPeriodCreatedListener {

  private final PolicyResolution resolution;
  private final DefaultPolicyQueryService query;

  public PolicyPeriodCreatedListener(PolicyResolution resolution, DefaultPolicyQueryService query) {
    this.resolution = Objects.requireNonNull(resolution, "resolution");
    this.query = Objects.requireNonNull(query, "query");
  }

  @ApplicationModuleListener
  public void onPeriodCreated(PeriodCreatedEvent event) {
    TenantContext.run(
        event.tenantId(),
        () -> {
          resolution.pinForPeriod(
              event.tenantId(), event.periodId(), event.flow(), event.startDate());
          query.invalidate(event.tenantId());
        });
  }
}
