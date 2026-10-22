package vn.viettel.okrkpi.okr.application;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dịch vụ ứng dụng của {@code MOD-01 · OKR Engine} cài đặt cổng {@link OkrApi} và sử dụng {@link
 * PeriodApi} của {@code MOD-08 (config)}.
 */
@Service
public class OkrQueryService implements OkrApi {

  private final PeriodApi periodApi;
  private final Map<UUID, KeyResultSummary> summaries = new ConcurrentHashMap<>();

  public OkrQueryService(PeriodApi periodApi) {
    this.periodApi = Objects.requireNonNull(periodApi, "periodApi must not be null");
  }

  public void registerKeyResult(KeyResultSummary summary) {
    Objects.requireNonNull(summary, "summary must not be null");
    periodApi.assertWritable(summary.tenantId(), summary.periodId());
    summaries.put(summary.id(), summary);
  }

  @Override
  public Optional<KeyResultSummary> findKeyResultById(TenantId tenantId, UUID keyResultId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(keyResultId, "keyResultId must not be null");
    return Optional.ofNullable(summaries.get(keyResultId))
        .filter(kr -> kr.tenantId().equals(tenantId));
  }
}
