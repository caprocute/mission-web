package vn.viettel.okrkpi.appraisal.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.appraisal.api.AppraisalResultApi;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.product.api.ProductScoreApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskSummaryApi;

/**
 * Dịch vụ ứng dụng của {@code MOD-04 · 360 & Appraisal} cài đặt {@link AppraisalResultApi} và tổng
 * hợp dữ liệu qua các cổng công khai của {@code okr}, {@code task}, {@code product}, {@code policy}
 * và {@code config}.
 */
@Service
public class DefaultAppraisalResultService implements AppraisalResultApi {

  private final OkrApi okrApi;
  private final TaskSummaryApi taskSummaryApi;
  private final ProductScoreApi productScoreApi;
  private final PolicyQueryApi policyQueryApi;
  private final PeriodApi periodApi;

  public DefaultAppraisalResultService(
      OkrApi okrApi,
      TaskSummaryApi taskSummaryApi,
      ProductScoreApi productScoreApi,
      PolicyQueryApi policyQueryApi,
      PeriodApi periodApi) {
    this.okrApi = Objects.requireNonNull(okrApi, "okrApi must not be null");
    this.taskSummaryApi = Objects.requireNonNull(taskSummaryApi, "taskSummaryApi must not be null");
    this.productScoreApi =
        Objects.requireNonNull(productScoreApi, "productScoreApi must not be null");
    this.policyQueryApi = Objects.requireNonNull(policyQueryApi, "policyQueryApi must not be null");
    this.periodApi = Objects.requireNonNull(periodApi, "periodApi must not be null");
  }

  @Override
  public Optional<String> findFinalGrade(TenantId tenantId, PeriodId periodId, UUID personId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(periodId, "periodId must not be null");
    Objects.requireNonNull(personId, "personId must not be null");
    periodApi.findById(tenantId, periodId);
    okrApi.findKeyResultById(tenantId, personId);
    taskSummaryApi.countCompletedTasks(tenantId, periodId, personId);
    productScoreApi.calculateProductScore(tenantId, periodId, personId);
    policyQueryApi.findActivePolicy(tenantId, periodId, "APPRAISAL_GRADE");
    return Optional.empty();
  }
}
