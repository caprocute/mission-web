package vn.viettel.okrkpi.task.application.assign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.TaskRegistrationService.Decision;
import vn.viettel.okrkpi.task.application.assign.port.RegistrationNotices;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.CatalogDefaults;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskRegistrations;
import vn.viettel.okrkpi.task.application.assign.port.TaskRegistrations.OpenAdjustment;
import vn.viettel.okrkpi.task.application.assign.port.TaskRegistrations.Pending;
import vn.viettel.okrkpi.task.application.catalog.CatalogService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.catalog.CatalogItem;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

class TaskRegistrationServiceTest {

  final TenantId tenant = new TenantId(UUID.randomUUID());
  final UUID periodId = UUID.randomUUID();
  final UUID unit = UUID.randomUUID();
  final UUID reg = UUID.randomUUID();
  final UUID head = UUID.randomUUID();
  final UUID itemId = UUID.randomUUID();
  final UUID taskId = UUID.randomUUID();
  final TaskActor regActor = new TaskActor(reg, java.util.Set.of("member"));
  final TaskActor headActor = new TaskActor(head, java.util.Set.of("manager"));
  final PeriodSnapshot period =
      new PeriodSnapshot(
          PeriodId.of(periodId),
          tenant,
          "Q",
          "Quý",
          LocalDate.of(2030, 7, 1),
          LocalDate.of(2030, 9, 30),
          PeriodStatus.MO);

  final TaskDraftService drafts = mock(TaskDraftService.class);
  final TaskDraftRepository repository = mock(TaskDraftRepository.class);
  final TaskAssignService assign = mock(TaskAssignService.class);
  final TaskTransitionService transitions = mock(TaskTransitionService.class);
  final CatalogService catalog = mock(CatalogService.class);
  final OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  final PeriodApi periods = mock(PeriodApi.class);
  final TaskRegistrations registrations = mock(TaskRegistrations.class);
  final RegistrationNotices notices = mock(RegistrationNotices.class);
  TaskRegistrationService service;
  final TaskRow row = mock(TaskRow.class);

  @BeforeEach
  void setUp() {
    service =
        new TaskRegistrationService(
            drafts,
            repository,
            assign,
            transitions,
            catalog,
            org,
            periods,
            registrations,
            notices,
            Clock.fixed(Instant.parse("2030-08-01T00:00:00Z"), ZoneOffset.UTC));
    when(periods.findCurrent(tenant)).thenReturn(Optional.of(period));
    when(drafts.period(tenant, periodId)).thenReturn(period);
    when(org.headsOf(eq(tenant), eq(unit), any())).thenReturn(List.of(head, reg));
    when(transitions.find(tenant, taskId)).thenReturn(Optional.of(row));
  }

  CatalogItem item(String group) {
    return new CatalogItem(
        itemId,
        UUID.randomUUID(),
        "DK",
        "Tổng hợp",
        null,
        null,
        2,
        BigDecimal.ONE,
        null,
        null,
        group,
        null,
        null,
        "AP_DUNG",
        0,
        null);
  }

  DraftRow draft(String source, UUID assigner) {
    TaskDraftSpec spec =
        new TaskDraftSpec(
            periodId,
            source,
            null,
            itemId,
            "Tổng hợp",
            "THUONG_XUYEN",
            null,
            "TRUNG_BINH",
            reg,
            List.of(),
            LocalDate.of(2030, 9, 1),
            null,
            List.of(),
            "Báo cáo",
            BigDecimal.ONE,
            "báo cáo",
            "Văn bản",
            "Đạt",
            null,
            null,
            null,
            null);
    return new DraftRow(taskId, "NV-1", "NHAP", periodId, 20303, unit, assigner, false, spec, 4);
  }

  @Test
  void dangKyKiemTraDauVao() {
    assertThatThrownBy(
            () -> service.registerTask(tenant, itemId, null, LocalDate.now(), null, regActor))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.registerTask(
                    tenant, itemId, BigDecimal.ONE, LocalDate.now(), "x".repeat(1001), regActor))
        .isInstanceOf(BusinessException.class);
    when(catalog.applicableItem(tenant, reg, itemId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.registerTask(
                    tenant, itemId, BigDecimal.ONE, LocalDate.of(2030, 8, 1), null, regActor))
        .isInstanceOf(BusinessException.class);
    when(catalog.applicableItem(tenant, reg, itemId)).thenReturn(Optional.of(item("THEN_CHOT")));
    assertThatThrownBy(
            () ->
                service.registerTask(
                    tenant, itemId, BigDecimal.ONE, LocalDate.of(2030, 10, 1), null, regActor))
        .isInstanceOf(BusinessException.class);
    doThrow(new vn.viettel.okrkpi.config.api.PeriodLockedException(PeriodId.of(periodId)))
        .when(periods)
        .assertWritable(tenant, PeriodId.of(periodId));
    assertThatThrownBy(
            () ->
                service.registerTask(
                    tenant, itemId, BigDecimal.ONE, LocalDate.of(2030, 8, 1), null, regActor))
        .isInstanceOf(BusinessException.class);
    when(periods.findCurrent(tenant)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.registerTask(
                    tenant, itemId, BigDecimal.ONE, LocalDate.of(2030, 8, 1), null, regActor))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void dangKyTaoNhapVaBaoQuanLy() {
    when(catalog.applicableItem(tenant, reg, itemId)).thenReturn(Optional.of(item("THEN_CHOT")));
    when(drafts.insert(eq(tenant), any(), eq(regActor), any())).thenReturn(draft("DANG_KY", reg));
    assertThat(
            service.registerTask(
                tenant, itemId, BigDecimal.TEN, LocalDate.of(2030, 8, 1), " Ghi chú ", regActor))
        .isSameAs(row);
    ArgumentCaptor<TaskDraftSpec> spec = ArgumentCaptor.forClass(TaskDraftSpec.class);
    verify(drafts).insert(eq(tenant), spec.capture(), eq(regActor), any());
    assertThat(spec.getValue().source()).isEqualTo("DANG_KY");
    assertThat(spec.getValue().taskGroup()).isEqualTo("THEN_CHOT");
    assertThat(spec.getValue().leadPersonId()).isEqualTo(reg);
    assertThat(spec.getValue().basis()).isEqualTo("Ghi chú");
    verify(notices).submitted(tenant, taskId, "NV-1", List.of(head));
    when(catalog.applicableItem(tenant, reg, itemId)).thenReturn(Optional.of(item(null)));
    service.registerTask(tenant, itemId, BigDecimal.TEN, LocalDate.of(2030, 8, 1), " ", regActor);
    verify(drafts, times(2)).insert(eq(tenant), spec.capture(), eq(regActor), any());
    assertThat(spec.getValue().taskGroup()).isEqualTo("THUONG_XUYEN");
    assertThat(spec.getValue().basis()).isNull();
  }

  @Test
  void quyetDinhChanSaiNguoiVaSaiNguon() {
    when(repository.findDraft(tenant, taskId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.decide(tenant, taskId, 4, decision(true), headActor))
        .isInstanceOf(NoSuchElementException.class);
    when(repository.findDraft(tenant, taskId)).thenReturn(Optional.of(draft("DOT_XUAT", reg)));
    assertThatThrownBy(() -> service.decide(tenant, taskId, 4, decision(true), headActor))
        .isInstanceOf(BusinessException.class);
    when(repository.findDraft(tenant, taskId)).thenReturn(Optional.of(draft("DANG_KY", reg)));
    assertThatThrownBy(() -> service.decide(tenant, taskId, 4, decision(true), regActor))
        .isInstanceOf(BusinessException.class);
    TaskActor other = new TaskActor(UUID.randomUUID(), java.util.Set.of("manager"));
    assertThatThrownBy(() -> service.decide(tenant, taskId, 4, decision(true), other))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.decide(tenant, taskId, 4, null, headActor))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.decide(tenant, taskId, 4, new Decision(false, " ", null, null), headActor))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.decide(
                    tenant,
                    taskId,
                    4,
                    new Decision(true, null, LocalDate.of(2031, 1, 1), null),
                    headActor))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> service.decide(tenant, taskId, 4, new Decision(true, null, null, 0), headActor))
        .isInstanceOf(BusinessException.class);
    verify(assign, never()).assignRegistration(any(), any(), anyLong(), any());
  }

  @Test
  void tuChoiHuyNhiemVu() {
    when(repository.findDraft(tenant, taskId)).thenReturn(Optional.of(draft("DANG_KY", reg)));
    assertThat(
            service.decide(
                tenant, taskId, 4, new Decision(false, " Trùng việc ", null, null), headActor))
        .isSameAs(row);
    verify(transitions)
        .cancelDraft(tenant, taskId, 4, TaskRegistrationService.REJECTED, headActor, "Trùng việc");
    verify(notices).rejected(tenant, taskId, "NV-1", reg);
  }

  @Test
  void duyetGiaoVaDuaVaoDieuChinh() {
    when(repository.findDraft(tenant, taskId)).thenReturn(Optional.of(draft("DANG_KY", reg)));
    when(repository.catalogDefaults(tenant, itemId))
        .thenReturn(Optional.of(new CatalogDefaults(2, BigDecimal.ONE)));
    when(repository.storeDraft(eq(tenant), any(), eq(4L), any(), any(), any(), any(), any(), any()))
        .thenReturn(5L);
    OpenAdjustment open = new OpenAdjustment(UUID.randomUUID(), UUID.randomUUID(), reg);
    when(registrations.openAdjustment(tenant, 20303, periodId, unit)).thenReturn(Optional.of(open));
    Pending p =
        new Pending(
            taskId,
            itemId,
            "Tổng hợp",
            "THUONG_XUYEN",
            reg,
            BigDecimal.ONE,
            "báo cáo",
            2,
            BigDecimal.ONE,
            LocalDate.of(2030, 9, 1));
    when(registrations.pending(tenant, 20303, periodId, unit)).thenReturn(List.of(p, p));
    assertThat(
            service.decide(
                tenant,
                taskId,
                4,
                new Decision(true, null, LocalDate.of(2030, 9, 20), 3),
                headActor))
        .isSameAs(row);
    ArgumentCaptor<TaskDraftSpec> spec = ArgumentCaptor.forClass(TaskDraftSpec.class);
    verify(repository)
        .storeDraft(
            eq(tenant), any(), eq(4L), spec.capture(), eq(2), any(), any(), eq(head), any());
    assertThat(spec.getValue().acceptorId()).isEqualTo(head);
    assertThat(spec.getValue().dueDate()).isEqualTo(LocalDate.of(2030, 9, 20));
    assertThat(spec.getValue().quantity()).isEqualByComparingTo("3");
    verify(assign).assignRegistration(tenant, taskId, 5L, headActor);
    verify(registrations, times(2))
        .addItem(eq(tenant), eq(20303), eq(periodId), eq(open), eq(p), eq(reg), any());
    verify(notices, never()).waitingForAdjustment(any(), any(), any(), any());
  }

  @Test
  void duyetChuaCoDieuChinhThiBaoNguoiLap() {
    when(repository.findDraft(tenant, taskId)).thenReturn(Optional.of(draft("DANG_KY", reg)));
    when(repository.catalogDefaults(tenant, itemId)).thenReturn(Optional.empty());
    when(repository.storeDraft(eq(tenant), any(), eq(4L), any(), any(), any(), any(), any(), any()))
        .thenReturn(5L);
    when(registrations.openAdjustment(tenant, 20303, periodId, unit)).thenReturn(Optional.empty());
    UUID planner = UUID.randomUUID();
    when(registrations.planner(tenant, 20303, periodId, unit)).thenReturn(Optional.of(planner));
    service.decide(tenant, taskId, 4, decision(true), headActor);
    verify(notices).waitingForAdjustment(tenant, taskId, "NV-1", List.of(planner));
    when(registrations.planner(tenant, 20303, periodId, unit)).thenReturn(Optional.empty());
    service.decide(tenant, taskId, 4, decision(true), headActor);
    verify(notices).waitingForAdjustment(tenant, taskId, "NV-1", List.of(head, reg));
    when(repository.storeDraft(eq(tenant), any(), eq(4L), any(), any(), any(), any(), any(), any()))
        .thenReturn(-1L);
    assertThatThrownBy(() -> service.decide(tenant, taskId, 4, decision(true), headActor))
        .isInstanceOf(VersionConflictException.class);
  }

  static Decision decision(boolean approve) {
    return new Decision(approve, null, null, null);
  }
}
