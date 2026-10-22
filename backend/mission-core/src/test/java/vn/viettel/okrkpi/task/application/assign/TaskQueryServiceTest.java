package vn.viettel.okrkpi.task.application.assign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static vn.viettel.okrkpi.task.application.assign.AssignFixture.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.AssigneeRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.UnitFilter;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

class TaskQueryServiceTest {

  final AssignFixture f = new AssignFixture();
  final TaskLifecycleRepository tasks = mock(TaskLifecycleRepository.class);
  final TaskQueryService service = new TaskQueryService(tasks, f.repo, f.periods, f.org, CLOCK);

  DraftRow stored(TaskStatus status, boolean restricted) {
    DraftRow d = f.put(status.name(), spec("DOT_XUAT", LEAD), restricted, 1);
    when(tasks.find(TENANT, d.id())).thenReturn(Optional.of(task(d.id(), status, null)));
    when(f.repo.assignees(TENANT, 20264, d.id()))
        .thenReturn(List.of(new AssigneeRow("PERSON", LEAD, "CHU_TRI")));
    return d;
  }

  @Test
  void nguoiThamGiaThayVaiTroVaKhongBiChe() {
    DraftRow d = stored(TaskStatus.DA_GIAO, true);
    TaskQueryService.TaskView v =
        service.getTask(TENANT, d.id(), new TaskActor(LEAD, Set.of("member")));
    assertThat(v.callerRole()).isEqualTo("CHU_TRI");
    assertThat(v.masked()).isFalse();
    assertThat(service.getTask(TENANT, d.id(), MANAGER).masked()).isFalse();
    assertThat(service.listTaskAssignees(TENANT, d.id(), MANAGER)).hasSize(1);
  }

  @Test
  void canBoKhongThamGiaBiCheHoacKhongThay() {
    DraftRow d = stored(TaskStatus.DA_GIAO, true);
    TaskActor leader = new TaskActor(UUID.randomUUID(), Set.of("leader"));
    assertThat(service.getTask(TENANT, d.id(), leader).masked()).isTrue();
    TaskActor member = new TaskActor(UUID.randomUUID(), Set.of("member"));
    assertThatThrownBy(() -> service.getTask(TENANT, d.id(), member))
        .isInstanceOf(NoSuchElementException.class);
    DraftRow draft = stored(TaskStatus.NHAP, false);
    assertThatThrownBy(() -> service.getTask(TENANT, draft.id(), leader))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(
            service
                .getTask(TENANT, draft.id(), new TaskActor(UUID.randomUUID(), Set.of("manager")))
                .masked())
        .isFalse();
    assertThatThrownBy(() -> service.getTask(TENANT, UUID.randomUUID(), MANAGER))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void viecCuaDonViMacDinhDonViCongTacVaGomCapDuoi() {
    UUID child = UUID.randomUUID();
    when(f.org.descendantsOf(eq(TENANT), any(OrgTree.class), eq(UNIT), anyInt()))
        .thenReturn(List.of(child));
    service.listUnitTasks(
        TENANT,
        new TaskQueryService.UnitQuery(null, null, Set.of("DA_GIAO"), null, null, true, "báo cáo"),
        MANAGER,
        null,
        21);
    ArgumentCaptor<UnitFilter> filter = ArgumentCaptor.forClass(UnitFilter.class);
    verify(f.repo).listUnitTasks(eq(TENANT), eq(20264), filter.capture(), eq(null), eq(21));
    assertThat(filter.getValue().unitIds()).containsExactly(UNIT, child);
    assertThat(filter.getValue().periodId()).isEqualTo(PERIOD);
  }

  @Test
  void viecCuaDonViTheoKyChiRoVaThieuDonVi() {
    UUID other = UUID.randomUUID();
    when(f.periods.findById(TENANT, PeriodId.of(other))).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.listUnitTasks(
                    TENANT,
                    new TaskQueryService.UnitQuery(UNIT, other, Set.of(), null, null, false, null),
                    MANAGER,
                    null,
                    20))
        .isInstanceOf(NoSuchElementException.class);
    assertThat(
            service.listUnitTasks(
                TENANT,
                new TaskQueryService.UnitQuery(UNIT, PERIOD, Set.of(), null, true, false, null),
                MANAGER,
                UUID.randomUUID(),
                20))
        .isEmpty();
    when(f.org.assignmentsOf(eq(TENANT), eq(ASSIGNER), any())).thenReturn(List.of());
    assertThatThrownBy(
            () ->
                service.listUnitTasks(
                    TENANT,
                    new TaskQueryService.UnitQuery(null, PERIOD, Set.of(), null, null, false, null),
                    MANAGER,
                    null,
                    20))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
  }
}
