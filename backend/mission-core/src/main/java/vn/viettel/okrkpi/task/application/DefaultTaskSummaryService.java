package vn.viettel.okrkpi.task.application;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskSummaryApi;

/**
 * Dịch vụ ứng dụng của {@code MOD-02 · Task Management} cài đặt {@link TaskSummaryApi} và phụ thuộc
 * {@link OkrApi} ({@code okr :: api}) cùng {@link PeriodApi} ({@code config :: api}).
 */
@Service
public class DefaultTaskSummaryService implements TaskSummaryApi {

  private final OkrApi okrApi;
  private final PeriodApi periodApi;

  public DefaultTaskSummaryService(OkrApi okrApi, PeriodApi periodApi) {
    this.okrApi = Objects.requireNonNull(okrApi, "okrApi must not be null");
    this.periodApi = Objects.requireNonNull(periodApi, "periodApi must not be null");
  }

  @Override
  public long countCompletedTasks(TenantId tenantId, PeriodId periodId, UUID assigneeId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(periodId, "periodId must not be null");
    Objects.requireNonNull(assigneeId, "assigneeId must not be null");
    periodApi.findById(tenantId, periodId);
    okrApi.findKeyResultById(tenantId, assigneeId);
    return 0L;
  }
}
