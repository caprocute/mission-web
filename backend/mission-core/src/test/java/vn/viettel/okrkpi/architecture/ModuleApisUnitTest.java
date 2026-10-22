package vn.viettel.okrkpi.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.DefaultAppraisalResultService;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.application.InMemoryPeriodRepository;
import vn.viettel.okrkpi.config.application.NewPeriod;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.dossier.application.DefaultDossierQueryService;
import vn.viettel.okrkpi.okr.api.OkrApi.KeyResultSummary;
import vn.viettel.okrkpi.okr.application.OkrQueryService;
import vn.viettel.okrkpi.oversight.application.DefaultOversightQueryService;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.PolicyFormulaView;
import vn.viettel.okrkpi.policy.application.DefaultPolicyQueryService;
import vn.viettel.okrkpi.product.application.DefaultProductScoreService;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Clock;
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
  private static final ActorId ACTOR = ActorId.of(UUID.randomUUID());

  @Test
  void periodConfigServiceAndAllDownstreamModuleServicesWorkInProcess() {
    List<Object> publishedEvents = new ArrayList<>();
    InMemoryPeriodRepository repository = new InMemoryPeriodRepository();
    PeriodConfigService periodConfig =
        new PeriodConfigService(
            repository,
            (p, t, lockOrderId, reason) -> {},
            publishedEvents::add,
            null,
            Clock.fixed(Instant.parse("2026-02-01T03:00:00Z")));
    assertThat(periodConfig.findCurrent(TENANT)).isEmpty();
    assertThat(periodConfig.current(TENANT)).isEmpty();

    PeriodId period =
        periodConfig
            .create(
                TENANT,
                ACTOR.value(),
                new NewPeriod(
                    "2026-Q1",
                    "Quý I/2026",
                    PeriodKind.QUY,
                    PeriodFlow.CA_HAI,
                    null,
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 3, 31),
                    null,
                    null,
                    null))
            .period()
            .id();
    assertThat(publishedEvents).hasSize(2);
    assertThat(periodConfig.findCurrent(TENANT)).isPresent();
    assertThat(periodConfig.current(TENANT)).isPresent();
    assertThat(periodConfig.current(TENANT, "QUY").id()).isEqualTo(period);
    assertThat(periodConfig.findCurrent(OTHER_TENANT)).isEmpty();
    assertThat(periodConfig.findById(TENANT, period)).isPresent();

    periodConfig.assertOpen(TENANT, period);
    periodConfig.assertWritable(TENANT, period);
    periodConfig.assertOpen(OTHER_TENANT, period);

    DefaultPolicyQueryService policyService = new DefaultPolicyQueryService(periodConfig);
    PolicyFormulaView policyView =
        new PolicyFormulaView(
            UUID.randomUUID(), TENANT, period, "APPRAISAL_GRADE", "score >= 90 ? 'A' : 'B'", 1);
    policyService.registerPolicy(policyView);
    assertThat(policyService.findActivePolicy(TENANT, period, "APPRAISAL_GRADE"))
        .contains(policyView);

    OkrQueryService okrService = new OkrQueryService(periodConfig);
    UUID krId = UUID.randomUUID();
    KeyResultSummary krSummary =
        new KeyResultSummary(krId, TENANT, period, "Hoàn thành 100% chỉ tiêu", 50.0, 85.0);
    okrService.registerKeyResult(krSummary);
    assertThat(okrService.findKeyResultById(TENANT, krId)).contains(krSummary);
    assertThat(okrService.findKeyResultById(OTHER_TENANT, krId)).isEmpty();
    assertThat(okrService.findKeyResultById(TENANT, UUID.randomUUID())).isEmpty();

    DefaultTaskSummaryService taskService = new DefaultTaskSummaryService(okrService, periodConfig);
    assertThat(taskService.countCompletedTasks(TENANT, period, krId)).isZero();

    DefaultProductScoreService productService =
        new DefaultProductScoreService(policyService, periodConfig);
    assertThat(productService.calculateProductScore(TENANT, period, krId)).isZero();

    DefaultAppraisalResultService appraisalService =
        new DefaultAppraisalResultService(
            okrService, taskService, productService, policyService, periodConfig);
    assertThat(appraisalService.findFinalGrade(TENANT, period, krId)).isEmpty();

    DefaultDossierQueryService dossierService =
        new DefaultDossierQueryService(appraisalService, periodConfig);
    assertThat(dossierService.findDossierId(TENANT, period, krId)).isEmpty();

    DefaultOversightQueryService oversightService =
        new DefaultOversightQueryService(
            appraisalService, taskService, productService, periodConfig);
    assertThat(oversightService.hasActiveInspection(TENANT, period, krId)).isFalse();

    // Khóa kỳ đánh giá và kiểm tra PeriodLockedException
    UUID saga = UUID.randomUUID();
    periodConfig.transition(TENANT, period, PeriodState.DANG_KHOA, saga, ACTOR.value(), null, null);
    periodConfig.assertOpen(TENANT, period);
    assertThatThrownBy(() -> periodConfig.assertWritable(TENANT, period))
        .isInstanceOf(PeriodLockedException.class);
    periodConfig.transition(TENANT, period, PeriodState.DA_KHOA, saga, ACTOR.value(), null, null);
    assertThatThrownBy(() -> periodConfig.assertOpen(TENANT, period))
        .isInstanceOf(PeriodLockedException.class);
    assertThatThrownBy(() -> periodConfig.assertWritable(TENANT, period))
        .isInstanceOf(PeriodLockedException.class);
  }
}
