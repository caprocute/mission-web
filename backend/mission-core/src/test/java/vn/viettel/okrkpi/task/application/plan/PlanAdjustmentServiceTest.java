package vn.viettel.okrkpi.task.application.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.RemovedItem;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/** CORE-02-10: tạo điều chỉnh trong giới hạn và hạn, so sánh phiên bản. */
class PlanAdjustmentServiceTest {

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID periodId = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();
  private final PlanRow plan =
      new PlanRow(UUID.randomUUID(), periodId, 20264, "TAP_THE", UUID.randomUUID(), null, null, 3);

  private PlanRepository repo;
  private PlanService planService;
  private PeriodApi periods;
  private final List<VersionRow> versions = new ArrayList<>();

  @BeforeEach
  void setUp() {
    repo = mock(PlanRepository.class);
    planService = mock(PlanService.class);
    periods = mock(PeriodApi.class);
    when(repo.findPlan(tenant, plan.id())).thenReturn(Optional.of(plan));
    when(repo.versions(tenant, plan.periodBucket(), plan.id())).thenReturn(versions);
    when(periods.findById(tenant, PeriodId.of(periodId)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(periodId),
                    tenant,
                    "2026-Q4",
                    "Quý IV năm 2026",
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 12, 31),
                    PeriodStatus.MO)));
    when(planService.parameter(any(), any(), anyString())).thenReturn(Optional.empty());
  }

  private PlanAdjustmentService service(String today) {
    return new PlanAdjustmentService(
        repo,
        planService,
        periods,
        Clock.fixed(Instant.parse(today + "T03:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void taoPhienBanNCongMotSaoChepDongVaTroDongGoc() {
    VersionRow v1 = version(1, PlanVersionStatus.DA_DUYET, true);
    versions.add(v1);
    ItemRow source = item(UUID.randomUUID(), null);
    when(repo.items(tenant, plan.periodBucket(), v1.id())).thenReturn(List.of(source));

    service("2026-10-23").create(tenant, plan.id(), "  Bổ sung theo kết luận  ", actor);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ItemRow>> items = ArgumentCaptor.forClass(List.class);
    verify(repo)
        .insertAdjustment(
            eq(tenant),
            eq(plan),
            any(),
            eq(2),
            eq("Bổ sung theo kết luận"),
            items.capture(),
            eq(actor));
    ItemRow copied = items.getValue().get(0);
    assertThat(copied.id()).isNotEqualTo(source.id());
    assertThat(copied.originItemId()).isEqualTo(source.id());
    assertThat(copied.changeReason()).isNull();
    assertThat(copied.title()).isEqualTo(source.title());
    verify(periods).assertWritable(tenant, PeriodId.of(periodId));
    verify(planService).version(tenant, plan.id(), 2);
  }

  @Test
  void dieuChinhLanThuBaVoiGioiHanHaiBiChan() {
    versions.add(version(3, PlanVersionStatus.DA_DUYET, true));
    versions.add(version(2, PlanVersionStatus.DA_DUYET, false));
    versions.add(version(1, PlanVersionStatus.DA_DUYET, false));

    assertThatThrownBy(() -> service("2026-10-23").create(tenant, plan.id(), "Lý do", actor))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.detail())
                  .isEqualTo("Kế hoạch đã điều chỉnh 2 lần trong quý, đạt giới hạn.");
              assertThat(e.errors().get(0).rule()).isEqualTo("BR-04-10");
            });
    verify(repo, never()).insertAdjustment(any(), any(), any(), anyInt(), any(), any(), any());

    // Tham số của gói nâng giới hạn lên 3.
    when(planService.parameter(tenant, plan, PlanAdjustments.MAX_KEY)).thenReturn(Optional.of("3"));
    service("2026-10-23").create(tenant, plan.id(), "Lý do", actor);
    verify(repo).insertAdjustment(any(), any(), any(), eq(4), any(), any(), any());
  }

  @Test
  void quaHanDieuChinhHoacDangCoPhienBanChuaDuyetBiChan() {
    versions.add(version(1, PlanVersionStatus.DA_DUYET, true));
    assertThatThrownBy(() -> service("2026-12-01").create(tenant, plan.id(), "Lý do", actor))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("01/12/2026"));

    when(planService.parameter(tenant, plan, PlanAdjustments.CUTOFF_KEY))
        .thenReturn(Optional.of(2));
    assertThatThrownBy(() -> service("2026-11-05").create(tenant, plan.id(), "Lý do", actor))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("01/11/2026"));

    versions.add(0, version(2, PlanVersionStatus.TRA_LAI, false));
    assertThatThrownBy(() -> service("2026-10-23").create(tenant, plan.id(), "Lý do", actor))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(PlanService.INVALID_TRANSITION));

    assertThatThrownBy(() -> service("2026-10-23").create(tenant, plan.id(), " ", actor))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errors().get(0).field()).isEqualTo("changeReason"));
    assertThatThrownBy(() -> service("2026-10-23").create(tenant, UUID.randomUUID(), "x", actor))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void soSanhKemLanDieuChinhVaDongBo() {
    VersionRow v1 = version(1, PlanVersionStatus.DA_DUYET, true);
    VersionRow v2 = version(2, PlanVersionStatus.NHAP, false);
    versions.add(v2);
    versions.add(v1);
    ItemRow kept = item(UUID.randomUUID(), null);
    ItemRow gone = item(UUID.randomUUID(), null);
    when(repo.items(tenant, plan.periodBucket(), v1.id())).thenReturn(List.of(kept, gone));
    when(repo.items(tenant, plan.periodBucket(), v2.id()))
        .thenReturn(List.of(item(UUID.randomUUID(), kept.id())));
    when(repo.removedItems(tenant, plan.periodBucket(), v2.id()))
        .thenReturn(List.of(new RemovedItem(gone.id(), "Đã chuyển đơn vị khác")));

    PlanAdjustmentService.DiffView view = service("2026-10-23").diff(tenant, plan.id(), 2, 1);

    assertThat(view.adjustmentNo()).isEqualTo(1);
    assertThat(view.adjustmentsMax()).isEqualTo(2);
    assertThat(view.groupWeightChanged()).isFalse();
    assertThat(view.diff().count(PlanAdjustments.Change.BO)).isEqualTo(1);
    assertThat(view.diff().lines().get(1).reason()).isEqualTo("Đã chuyển đơn vị khác");

    PlanAdjustmentService.DiffView empty = service("2026-10-23").diff(tenant, plan.id(), 1, 0);
    assertThat(empty.diff().count(PlanAdjustments.Change.THEM)).isEqualTo(2);
    assertThat(empty.adjustmentNo()).isZero();
    assertThatThrownBy(() -> service("2026-10-23").diff(tenant, plan.id(), 9, 1))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void thamSoSoNguyen() {
    assertThat(PlanAdjustmentService.integer(Optional.of(3))).contains(3);
    assertThat(PlanAdjustmentService.integer(Optional.of(" 4 "))).contains(4);
    assertThat(PlanAdjustmentService.integer(Optional.of("hai"))).isEmpty();
    assertThat(PlanAdjustmentService.integer(Optional.empty())).isEmpty();
    assertThat(
            PlanAdjustmentService.used(
                List.of(
                    version(3, PlanVersionStatus.NHAP, false),
                    version(2, PlanVersionStatus.DA_DUYET, true),
                    version(1, PlanVersionStatus.DA_DUYET, false))))
        .isEqualTo(1);
  }

  private VersionRow version(int no, PlanVersionStatus status, boolean current) {
    return new VersionRow(
        UUID.randomUUID(), plan.id(), no, status, current, null, null, null, null, null, 1, 1);
  }

  private static ItemRow item(UUID id, UUID origin) {
    return new ItemRow(
        id,
        1,
        null,
        null,
        null,
        "Thẩm định hồ sơ",
        "THEN_CHOT",
        new Assignee("UNIT", UUID.randomUUID(), ""),
        List.of(),
        "Hồ sơ",
        BigDecimal.ONE,
        "bộ",
        null,
        null,
        LocalDate.of(2026, 11, 30),
        origin,
        null,
        null);
  }
}
