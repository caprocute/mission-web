package vn.viettel.okrkpi.product.application;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.product.api.ProductScoreApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dịch vụ ứng dụng của {@code MOD-03 · Product Catalog & Scoring} cài đặt {@link ProductScoreApi}
 * và phụ thuộc {@link PolicyQueryApi} ({@code policy :: api}) cùng {@link PeriodApi} ({@code config
 * :: api}).
 */
@Service
public class DefaultProductScoreService implements ProductScoreApi {

  private final PolicyQueryApi policyQueryApi;
  private final PeriodApi periodApi;

  public DefaultProductScoreService(PolicyQueryApi policyQueryApi, PeriodApi periodApi) {
    this.policyQueryApi = Objects.requireNonNull(policyQueryApi, "policyQueryApi must not be null");
    this.periodApi = Objects.requireNonNull(periodApi, "periodApi must not be null");
  }

  @Override
  public double calculateProductScore(TenantId tenantId, PeriodId periodId, UUID personId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(periodId, "periodId must not be null");
    Objects.requireNonNull(personId, "personId must not be null");
    periodApi.findById(tenantId, periodId);
    policyQueryApi.findActivePolicy(tenantId, periodId, "PRODUCT_SCORE");
    return 0.0d;
  }
}
