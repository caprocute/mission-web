package vn.viettel.okrkpi.oversight.application;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.appraisal.api.AppraisalResultApi;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.oversight.api.OversightQueryApi;
import vn.viettel.okrkpi.product.api.ProductScoreApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskSummaryApi;

/**
 * Dịch vụ ứng dụng của {@code MOD-07 · Inspection & Oversight} cài đặt {@link OversightQueryApi} và
 * phụ thuộc {@link AppraisalResultApi} ({@code appraisal :: api}), {@link TaskSummaryApi} ({@code
 * task :: api}), {@link ProductScoreApi} ({@code product :: api}) cùng {@link PeriodApi} ({@code
 * config :: api}).
 */
@Service
public class DefaultOversightQueryService implements OversightQueryApi {

  private final AppraisalResultApi appraisalResultApi;
  private final TaskSummaryApi taskSummaryApi;
  private final ProductScoreApi productScoreApi;
  private final PeriodApi periodApi;

  public DefaultOversightQueryService(
      AppraisalResultApi appraisalResultApi,
      TaskSummaryApi taskSummaryApi,
      ProductScoreApi productScoreApi,
      PeriodApi periodApi) {
    this.appraisalResultApi =
        Objects.requireNonNull(appraisalResultApi, "appraisalResultApi must not be null");
    this.taskSummaryApi = Objects.requireNonNull(taskSummaryApi, "taskSummaryApi must not be null");
    this.productScoreApi =
        Objects.requireNonNull(productScoreApi, "productScoreApi must not be null");
    this.periodApi = Objects.requireNonNull(periodApi, "periodApi must not be null");
  }

  @Override
  public boolean hasActiveInspection(TenantId tenantId, PeriodId periodId, UUID personId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(periodId, "periodId must not be null");
    Objects.requireNonNull(personId, "personId must not be null");
    periodApi.findById(tenantId, periodId);
    appraisalResultApi.findFinalGrade(tenantId, periodId, personId);
    taskSummaryApi.countCompletedTasks(tenantId, periodId, personId);
    productScoreApi.calculateProductScore(tenantId, periodId, personId);
    return false;
  }
}
