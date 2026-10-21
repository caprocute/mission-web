package vn.viettel.okrkpi.policy.application;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Dịch vụ ứng dụng của {@code MOD-05 · Policy & Formula} cài đặt cổng {@link PolicyQueryApi}. */
@Service
public class DefaultPolicyQueryService implements PolicyQueryApi {

  private final PeriodApi periodApi;
  private final Map<PolicyKey, PolicyFormulaView> policies = new ConcurrentHashMap<>();

  public DefaultPolicyQueryService(PeriodApi periodApi) {
    this.periodApi = Objects.requireNonNull(periodApi, "periodApi must not be null");
  }

  public void registerPolicy(PolicyFormulaView view) {
    Objects.requireNonNull(view, "view must not be null");
    periodApi.assertOpen(view.tenantId(), view.periodId());
    policies.put(new PolicyKey(view.tenantId(), view.periodId(), view.code()), view);
  }

  @Override
  public Optional<PolicyFormulaView> findActivePolicy(
      TenantId tenantId, PeriodId periodId, String code) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(periodId, "periodId must not be null");
    Objects.requireNonNull(code, "code must not be null");
    return Optional.ofNullable(policies.get(new PolicyKey(tenantId, periodId, code)));
  }

  private record PolicyKey(TenantId tenantId, PeriodId periodId, String code) {}
}
