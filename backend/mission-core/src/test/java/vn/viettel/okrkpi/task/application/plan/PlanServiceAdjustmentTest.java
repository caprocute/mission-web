package vn.viettel.okrkpi.task.application.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Check;
import vn.viettel.okrkpi.task.application.plan.port.PlanLineFacts;
import vn.viettel.okrkpi.task.application.plan.port.PlanLineFacts.LineFact;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.RemovedItem;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/** CORE-02-10: kiểm tra điều chỉnh trong lưu nháp, kiểm tra tức thì và đọc phiên bản. */
class PlanServiceAdjustmentTest {

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID periodId = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();
  private final PlanRow plan =
      new PlanRow(UUID.randomUUID(), periodId, 20264, "TAP_THE", UUID.randomUUID(), null, null, 2);
  private final VersionRow v1 = version(1, PlanVersionStatus.DA_DUYET, 3);
  private final VersionRow v2 = version(2, PlanVersionStatus.NHAP, 5);
  private final ItemRow done = item(UUID.randomUUID(), 1, "Báo cáo chuyên đề", null, null);
  private final ItemRow kept = item(UUID.randomUUID(), 2, "Giao ban", null, null);

  private PlanRepository repo;
  private PlanLineFacts facts;
  private PolicyQueryApi policies;
  private PlanService service;

  @BeforeEach
  void setUp() {
    repo = mock(PlanRepository.class);
    facts = mock(PlanLineFacts.class);
    policies = mock(PolicyQueryApi.class);
    PeriodApi periods = mock(PeriodApi.class);
    OkrApi okr = mock(OkrApi.class);
    when(okr.listApprovedKeyResults(any(), any(), any())).thenReturn(List.of());
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
    when(repo.findPlan(tenant, plan.id())).thenReturn(Optional.of(plan));
    when(repo.versions(tenant, plan.periodBucket(), plan.id())).thenReturn(List.of(v2, v1));
    when(repo.lockVersion(tenant, plan.periodBucket(), plan.id(), 2)).thenReturn(Optional.of(v2));
    when(repo.items(tenant, plan.periodBucket(), v1.id())).thenReturn(List.of(done, kept));
    when(repo.catalogItems(any(), anyCollection())).thenReturn(Map.of());
    when(facts.of(eq(tenant), eq(plan.periodBucket()), anyCollection()))
        .thenReturn(Map.of(done.id(), new LineFact(done.id(), "HOAN_THANH", true)));
    PlanChecker checker = mock(PlanChecker.class);
    when(checker.check(any(), any())).thenReturn(List.of());
    service =
        new PlanService(
            repo, periods, okr, null, checker, policies, null, facts, Clock.systemUTC());
  }

  @Test
  void boViecDaCoMinhChungXacNhanBiChanNgayKhiLuu() {
    List<ItemRow> drafts = List.of(item(null, 0, kept.title(), kept.id(), null));
    List<RemovedItem> removed = List.of(new RemovedItem(done.id(), "Không còn phù hợp"));

    assertThatThrownBy(() -> service.saveVersion(tenant, plan.id(), 2, 5, drafts, removed, actor))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.detail()).contains("Báo cáo chuyên đề");
              assertThat(e.errors().get(0).field()).isEqualTo("removedItems");
              assertThat(e.errors().get(0).rule()).isEqualTo("BR-04-11");
            });
    verify(repo, never()).replaceItems(any(), any(), any(), any(), any());
  }

  @Test
  void luuNhapGhiDongBoVaThieuLyDoChiCanhBaoKhiKiemTra() {
    when(facts.of(eq(tenant), eq(plan.periodBucket()), anyCollection())).thenReturn(Map.of());
    List<ItemRow> drafts =
        List.of(
            item(null, 0, kept.title() + " mở rộng", kept.id(), null),
            item(null, 0, "Việc mới", null, "Phát sinh"));
    List<RemovedItem> removed = List.of(new RemovedItem(done.id(), "Đã chuyển đơn vị khác"));
    when(repo.items(eq(tenant), eq(plan.periodBucket()), eq(v2.id()))).thenReturn(List.of());

    service.saveVersion(tenant, plan.id(), 2, 5, drafts, removed, actor);

    verify(repo).replaceRemovedItems(tenant, plan, v2, removed);
    verify(repo).replaceItems(eq(tenant), eq(plan), eq(v2), any(), eq(actor));

    List<Check> checks = service.check(tenant, plan.id(), 2, drafts, List.of());
    assertThat(checks).extracting(Check::rule).containsOnly(PlanAdjustments.BR_04_10);
    assertThat(checks).extracting(Check::lineNo).containsExactly(1, null);
  }

  @Test
  void docPhienBanDieuChinhKemLoiDieuChinhPhienBanDauKhongKiem() {
    when(repo.items(tenant, plan.periodBucket(), v2.id()))
        .thenReturn(List.of(item(UUID.randomUUID(), 1, kept.title(), kept.id(), null)));
    when(repo.removedItems(tenant, plan.periodBucket(), v2.id()))
        .thenReturn(List.of(new RemovedItem(done.id(), "Bỏ")));

    PlanService.VersionView view = service.version(tenant, plan.id(), 2);
    assertThat(view.checks()).extracting(Check::msg).containsExactly("MSG-04-11");

    PlanService.VersionView first = service.version(tenant, plan.id(), 1);
    assertThat(first.checks()).isEmpty();
    assertThat(service.check(tenant, plan.id(), 1, List.of(kept))).isEmpty();
    // Phiên bản 1 không có dòng bỏ: không ghi removed_items.
    verify(repo, never()).replaceRemovedItems(any(), any(), any(), any());
  }

  @Test
  void thamSoLayTuGoiMaKyDaGhim() {
    UUID versionId = UUID.randomUUID();
    when(policies.resolve(any(), any(), eq("NHA_NUOC"), any(), any())).thenReturn(Optional.empty());
    when(policies.resolve(any(), any(), eq("DANG"), any(), any()))
        .thenReturn(
            Optional.of(
                new ResolvedPack(
                    UUID.randomUUID(),
                    "DANG",
                    versionId,
                    "2.0",
                    "h",
                    "k",
                    UUID.randomUUID(),
                    true)));
    when(policies.parameter(tenant, versionId, PlanAdjustments.MAX_KEY)).thenReturn(Optional.of(3));
    assertThat(service.parameter(tenant, plan, PlanAdjustments.MAX_KEY)).contains(3);

    PlanService noPolicy =
        new PlanService(
            repo,
            mock(PeriodApi.class),
            mock(OkrApi.class),
            null,
            mock(PlanChecker.class),
            null,
            null,
            Clock.systemUTC());
    assertThat(noPolicy.parameter(tenant, plan, PlanAdjustments.MAX_KEY)).isEmpty();
    when(policies.resolve(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    assertThat(service.parameter(tenant, plan, PlanAdjustments.MAX_KEY)).isEmpty();
    assertThat(service.baseOf(tenant, plan, 1)).isEmpty();
    assertThat(service.baseOf(tenant, plan, 2)).contains(v1);
    verify(repo, never()).insertAdjustment(any(), any(), any(), anyInt(), any(), any(), any());
  }

  private VersionRow version(int no, PlanVersionStatus status, long rowVersion) {
    return new VersionRow(
        UUID.randomUUID(),
        plan.id(),
        no,
        status,
        status == PlanVersionStatus.DA_DUYET,
        null,
        null,
        null,
        null,
        no > 1 ? "Điều chỉnh" : null,
        2,
        rowVersion);
  }

  private ItemRow item(UUID id, int no, String title, UUID origin, String reason) {
    return new ItemRow(
        id,
        no,
        null,
        null,
        null,
        title,
        "THEN_CHOT",
        new Assignee("UNIT", plan.ownerUnitId(), ""),
        List.of(),
        title,
        BigDecimal.ONE,
        "báo cáo",
        null,
        null,
        LocalDate.of(2026, 11, 30),
        origin,
        reason,
        null);
  }
}
