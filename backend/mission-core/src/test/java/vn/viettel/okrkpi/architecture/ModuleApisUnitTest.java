package vn.viettel.okrkpi.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.DefaultAppraisalResultService;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.domain.EvaluationPeriod;
import vn.viettel.okrkpi.dossier.application.DefaultDossierQueryService;
import vn.viettel.okrkpi.okr.api.OkrApi.KeyResultSummary;
import vn.viettel.okrkpi.okr.application.OkrQueryService;
import vn.viettel.okrkpi.oversight.application.DefaultOversightQueryService;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.PolicyFormulaView;
import vn.viettel.okrkpi.policy.application.DefaultPolicyQueryService;
import vn.viettel.okrkpi.product.application.DefaultProductScoreService;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.DefaultTaskSummaryService;

/**
 * Kiểm thử đơn vị cho tầng {@code domain} và {@code application} của 8 mô-đun nghiệp vụ trong
 * {@code mission-core} (CORE-00-02).
 */
class ModuleApisUnitTest {

  private static final TenantId TENANT = new TenantId(UUID.randomUUID());
  private static final TenantId OTHER_TENANT = new TenantId(UUID.randomUUID());
  private static final PeriodId PERIOD = new PeriodId(UUID.randomUUID());

  @Test
  void evaluationPeriodDomainInvariantsAndGetters() {
    EvaluationPeriod period =
        new EvaluationPeriod(
            PERIOD,
            TENANT,
            "2026-Q1",
            "Quý 1 năm 2026",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 3, 31),
            false);

    assertThat(period.id()).isEqualTo(PERIOD);
    assertThat(period.tenantId()).isEqualTo(TENANT);
    assertThat(period.code()).isEqualTo("2026-Q1");
    assertThat(period.name()).isEqualTo("Quý 1 năm 2026");
    assertThat(period.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(period.endDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    assertThat(period.isLocked()).isFalse();

    assertThatThrownBy(
            () ->
                new EvaluationPeriod(
                    PERIOD,
                    TENANT,
                    "2026-Q1",
                    "Quý 1 năm 2026",
                    LocalDate.of(2026, 4, 1),
                    LocalDate.of(2026, 3, 31),
                    true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void periodConfigServiceAndAllDownstreamModuleServicesWorkInProcess() {
    List<Object> publishedEvents = new ArrayList<>();
    PeriodConfigService periodConfig = new PeriodConfigService(publishedEvents::add);

    PeriodSnapshot draftSnapshot =
        new PeriodSnapshot(
            PERIOD,
            TENANT,
            "2026-Q1",
            "Quý 1/2026",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 3, 31),
            PeriodStatus.KHOI_TAO);
    periodConfig.registerPeriod(draftSnapshot);
    assertThat(periodConfig.findCurrent(TENANT)).isEmpty();
    assertThat(periodConfig.current(TENANT)).isEmpty();

    periodConfig.openPeriod(draftSnapshot);
    assertThat(publishedEvents).hasSize(1);
    assertThat(periodConfig.findCurrent(TENANT)).isPresent();
    assertThat(periodConfig.current(TENANT)).isPresent();
    assertThat(periodConfig.findCurrent(OTHER_TENANT)).isEmpty();
    assertThat(periodConfig.findById(TENANT, PERIOD)).isPresent();

    periodConfig.assertOpen(TENANT, PERIOD);
    periodConfig.assertWritable(TENANT, PERIOD);
    periodConfig.assertOpen(OTHER_TENANT, PERIOD);

    DefaultPolicyQueryService policyService = new DefaultPolicyQueryService(periodConfig);
    PolicyFormulaView policyView =
        new PolicyFormulaView(
            UUID.randomUUID(), TENANT, PERIOD, "APPRAISAL_GRADE", "score >= 90 ? 'A' : 'B'", 1);
    policyService.registerPolicy(policyView);
    assertThat(policyService.findActivePolicy(TENANT, PERIOD, "APPRAISAL_GRADE"))
        .contains(policyView);

    OkrQueryService okrService = new OkrQueryService(periodConfig);
    UUID krId = UUID.randomUUID();
    KeyResultSummary krSummary =
        new KeyResultSummary(krId, TENANT, PERIOD, "Hoàn thành 100% chỉ tiêu", 50.0, 85.0);
    okrService.registerKeyResult(krSummary);
    assertThat(okrService.findKeyResultById(TENANT, krId)).contains(krSummary);
    assertThat(okrService.findKeyResultById(OTHER_TENANT, krId)).isEmpty();
    assertThat(okrService.findKeyResultById(TENANT, UUID.randomUUID())).isEmpty();

    DefaultTaskSummaryService taskService = new DefaultTaskSummaryService(okrService, periodConfig);
    assertThat(taskService.countCompletedTasks(TENANT, PERIOD, krId)).isZero();

    DefaultProductScoreService productService =
        new DefaultProductScoreService(policyService, periodConfig);
    assertThat(productService.calculateProductScore(TENANT, PERIOD, krId)).isZero();

    DefaultAppraisalResultService appraisalService =
        new DefaultAppraisalResultService(
            okrService, taskService, productService, policyService, periodConfig);
    assertThat(appraisalService.findFinalGrade(TENANT, PERIOD, krId)).isEmpty();

    DefaultDossierQueryService dossierService =
        new DefaultDossierQueryService(appraisalService, periodConfig);
    assertThat(dossierService.findDossierId(TENANT, PERIOD, krId)).isEmpty();

    DefaultOversightQueryService oversightService =
        new DefaultOversightQueryService(
            appraisalService, taskService, productService, periodConfig);
    assertThat(oversightService.hasActiveInspection(TENANT, PERIOD, krId)).isFalse();

    // Khóa kỳ đánh giá và kiểm tra PeriodLockedException
    PeriodSnapshot lockedSnapshot =
        new PeriodSnapshot(
            PERIOD,
            TENANT,
            "2026-Q1",
            "Quý 1/2026",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 3, 31),
            PeriodStatus.DA_KHOA);
    periodConfig.registerPeriod(lockedSnapshot);
    assertThatThrownBy(() -> periodConfig.assertOpen(TENANT, PERIOD))
        .isInstanceOf(PeriodLockedException.class);
    assertThatThrownBy(() -> periodConfig.assertWritable(TENANT, PERIOD))
        .isInstanceOf(PeriodLockedException.class);
  }
}
