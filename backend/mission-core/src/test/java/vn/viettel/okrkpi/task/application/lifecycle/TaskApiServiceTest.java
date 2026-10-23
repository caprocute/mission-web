package vn.viettel.okrkpi.task.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskApi.DeliverableState;
import vn.viettel.okrkpi.task.api.TaskApi.Outcome;
import vn.viettel.okrkpi.task.api.TaskApi.TransitionRequest;
import vn.viettel.okrkpi.task.api.events.TrangThaiNhiemVuDoi;
import vn.viettel.okrkpi.task.application.TaskApiService;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTables;

/** {@link TaskApiService} trên máy trạng thái thật và kho giả (CORE-02-12). */
class TaskApiServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());

  final LifecycleFakes.Repo repo = new LifecycleFakes.Repo();
  final List<Object> published = new ArrayList<>();
  final UUID period = UUID.randomUUID();
  final TaskTransitionService transitions =
      new TaskTransitionService(
          repo,
          (t, p) -> TransitionTables.defaults(),
          new CelGuardEvaluator(new CelEngine()),
          new LifecycleFakes.Periods(),
          e -> {},
          null,
          published::add,
          Clock.fixed(Instant.parse("2027-03-12T03:00:00Z"), ZoneOffset.UTC));
  final TaskApi api = new TaskApiService(repo, transitions);

  private UUID put(TaskStatus status) {
    UUID id = UUID.randomUUID();
    repo.rows.put(id, LifecycleFakes.task(id, period, status, UUID.randomUUID()));
    return id;
  }

  @Test
  void nopDuSanPhamChuyenChoNghiemThuVaPhatSuKienNoiBo() {
    UUID lead = UUID.randomUUID();
    repo.lead = lead;
    UUID id = put(TaskStatus.DANG_THUC_HIEN);

    var out =
        api.requestTransition(
            TENANT,
            new TransitionRequest(
                id,
                TaskApi.Command.SUBMIT,
                lead,
                Set.of("member"),
                null,
                List.of(
                    new DeliverableState(UUID.randomUUID(), "DA_NOP"),
                    new DeliverableState(UUID.randomUUID(), "DA_NOP")),
                null));

    assertThat(out.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(out.task().status()).isEqualTo("CHO_NGHIEM_THU");
    assertThat(out.task().leadAssigneeId()).isEqualTo(lead);
    assertThat(published)
        .filteredOn(TrangThaiNhiemVuDoi.class::isInstance)
        .singleElement()
        .satisfies(
            e -> {
              TrangThaiNhiemVuDoi ev = (TrangThaiNhiemVuDoi) e;
              assertThat(ev.newStatus()).isEqualTo("CHO_NGHIEM_THU");
              assertThat(ev.isTerminal()).isFalse();
              assertThat(ev.assigneeIds()).containsExactly(lead);
            });

    // Gọi lại: đã ở trạng thái đích, không chuyển thêm.
    var again =
        api.requestTransition(
            TENANT,
            new TransitionRequest(
                id, TaskApi.Command.SUBMIT, lead, Set.of("member"), null, List.of(), null));
    assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_IN_TARGET);
    // REWORK khi nhiệm vụ không ở CHO_NGHIEM_THU (và chưa ở đích): không áp dụng.
    UUID other = put(TaskStatus.DA_GIAO);
    assertThat(
            api.requestTransition(
                    TENANT,
                    new TransitionRequest(
                        other,
                        TaskApi.Command.REWORK,
                        lead,
                        Set.of("manager"),
                        "x",
                        List.of(),
                        null))
                .outcome())
        .isEqualTo(Outcome.NOT_APPLICABLE);
  }

  @Test
  void duyetDungNgayHoanThanhVaGuardDungSnapshot() {
    UUID acceptor = UUID.randomUUID();
    UUID id = put(TaskStatus.CHO_NGHIEM_THU);
    // Một sản phẩm chưa xác nhận: guard deliverables.all(...) không đạt.
    assertThatThrownBy(
            () ->
                api.requestTransition(
                    TENANT,
                    new TransitionRequest(
                        id,
                        TaskApi.Command.APPROVE,
                        acceptor,
                        Set.of("manager"),
                        null,
                        List.of(
                            new DeliverableState(UUID.randomUUID(), "DA_XAC_NHAN"),
                            new DeliverableState(UUID.randomUUID(), "DA_NOP")),
                        null)))
        .isInstanceOf(BusinessException.class);

    Instant submittedAt = Instant.parse("2027-03-09T08:00:00Z");
    var out =
        api.requestTransition(
            TENANT,
            new TransitionRequest(
                id,
                TaskApi.Command.APPROVE,
                acceptor,
                Set.of("manager"),
                null,
                List.of(new DeliverableState(UUID.randomUUID(), "DA_XAC_NHAN")),
                submittedAt));
    assertThat(out.task().status()).isEqualTo("HOAN_THANH");
    assertThat(out.task().terminal()).isTrue();
    assertThat(repo.updates.get(repo.updates.size() - 1).completedAt()).isEqualTo(submittedAt);
    assertThat(published)
        .filteredOn(TrangThaiNhiemVuDoi.class::isInstance)
        .singleElement()
        .extracting(e -> ((TrangThaiNhiemVuDoi) e).isTerminal())
        .isEqualTo(true);
  }

  @Test
  void lietKeTheoNguoiGioiHan200() {
    for (int i = 0; i < 3; i++) {
      put(TaskStatus.DA_GIAO);
    }
    var page1 = api.listForPerson(TENANT, PeriodId.of(period), UUID.randomUUID(), null, 2);
    assertThat(page1).hasSize(2);
    var page2 =
        api.listForPerson(TENANT, PeriodId.of(period), UUID.randomUUID(), page1.get(1).id(), 500);
    assertThat(page2).hasSize(1);
    assertThat(api.find(TENANT, page2.get(0).id())).isPresent();
  }

  @Test
  void yeuCauBoSungVoiSnapshotThieuMaVaHanGiaHan() {
    UUID id = UUID.randomUUID();
    var base = LifecycleFakes.task(id, period, TaskStatus.CHO_NGHIEM_THU, UUID.randomUUID());
    java.time.LocalDate extended = java.time.LocalDate.of(2027, 1, 15);
    UUID acceptor = UUID.randomUUID();
    repo.rows.put(
        id,
        new vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow(
            base.id(),
            base.periodId(),
            base.periodBucket(),
            base.code(),
            base.title(),
            base.description(),
            base.taskGroup(),
            base.source(),
            base.keyResultId(),
            base.assignerId(),
            base.assignedAt(),
            acceptor,
            base.dueDate(),
            extended,
            base.scoreDueDate(),
            base.plannedQuantity(),
            base.doneQuantity(),
            base.unit(),
            base.difficulty(),
            base.standardScore(),
            base.progressPct(),
            base.status(),
            base.overdue(),
            base.delayRisk(),
            base.stateMachineVersion(),
            base.rowVersion()));
    assertThat(api.find(TENANT, id)).get().extracting(s -> s.dueDate()).isEqualTo(extended);

    var out =
        api.requestTransition(
            TENANT,
            new TransitionRequest(
                id,
                TaskApi.Command.REWORK,
                acceptor,
                Set.of("manager"),
                "Bổ sung số liệu quý",
                List.of(new DeliverableState(null, null)),
                null));
    assertThat(out.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(out.task().status()).isEqualTo("DANG_THUC_HIEN");
    assertThat(api.listForUnit(TENANT, PeriodId.of(period), UUID.randomUUID(), null, 0)).hasSize(1);
  }

  @Test
  void docDauVaoTinhDiemQuaCongDocRieng() {
    TaskApiService service = new TaskApiService(repo, transitions);
    PeriodId p = PeriodId.of(period);
    UUID person = UUID.randomUUID();
    assertThat(service.listWorkItems(TENANT, p, person, null, 10)).isEmpty();
    assertThat(service.listWorkItems(TENANT, p, List.of(person), 10)).isEmpty();
    assertThat(service.listResponsibleItems(TENANT, p, person)).isEmpty();

    vn.viettel.okrkpi.task.application.lifecycle.port.TaskScoringReads reads =
        org.mockito.Mockito.mock(
            vn.viettel.okrkpi.task.application.lifecycle.port.TaskScoringReads.class);
    service.setScoringReads(reads);
    TaskApi.WorkItemSnapshot item =
        new TaskApi.WorkItemSnapshot(
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            "THUONG_XUYEN",
            null,
            null,
            "DA_GIAO",
            null,
            0,
            0,
            false,
            false,
            false);
    org.mockito.Mockito.when(reads.workItems(TENANT, period, person, null, 201))
        .thenReturn(List.of(item));
    org.mockito.Mockito.when(reads.workItems(TENANT, period, List.of(person), 1))
        .thenReturn(java.util.Map.of(person, List.of(item)));
    assertThat(service.listWorkItems(TENANT, p, person, null, 5000)).containsExactly(item);
    assertThat(item.standardScore()).isZero();
    assertThat(item.coefficientLevels()).isEmpty();
    assertThat(service.listWorkItems(TENANT, p, List.of(person), 0)).containsKey(person);
    assertThat(service.listWorkItems(TENANT, p, List.of(), 10)).isEmpty();
    service.listResponsibleItems(TENANT, p, person);
    org.mockito.Mockito.verify(reads).responsibleItems(TENANT, period, person);
  }
}
