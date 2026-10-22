package vn.viettel.okrkpi.policy.application;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
  private PolicyResolutionService resolution;

  public DefaultPolicyQueryService(PeriodApi periodApi) {
    this.periodApi = Objects.requireNonNull(periodApi, "periodApi must not be null");
  }

  /** Bộ phân giải Policy Pack theo kỳ (CORE-05-03); chưa nối thì các tra cứu mới trả rỗng. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setResolution(PolicyResolutionService resolution) {
    this.resolution = resolution;
  }

  @Override
  public Optional<ResolvedPack> resolve(
      TenantId tenantId, PeriodId periodId, String flow, UUID subjectGroupId, UUID orgUnitId) {
    return resolution == null
        ? Optional.empty()
        : resolution.resolve(tenantId, periodId, flow, subjectGroupId, orgUnitId);
  }

  @Override
  public Optional<Object> parameter(TenantId tenantId, UUID versionId, String key) {
    return resolution == null ? Optional.empty() : resolution.parameter(tenantId, versionId, key);
  }

  @Override
  public Optional<Map<String, Object>> taskLifecycle(TenantId tenantId, UUID versionId) {
    return resolution == null ? Optional.empty() : resolution.taskLifecycle(tenantId, versionId);
  }

  @Override
  public Optional<PolicyContent> content(TenantId tenantId, UUID versionId) {
    return resolution == null ? Optional.empty() : resolution.content(tenantId, versionId);
  }

  public void registerPolicy(PolicyFormulaView view) {
    Objects.requireNonNull(view, "view must not be null");
    periodApi.assertWritable(view.tenantId(), view.periodId());
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
