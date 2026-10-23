package vn.viettel.okrkpi.task.application.fromplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.NewDraft;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests.ChangeRow;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTaskNotices;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTasks;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTasks.ItemContent;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTasks.LinkedTask;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ApprovalRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.CatalogRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTables;

class PlanTaskGeneratorTest {

  static final int BUCKET = 20302;
  final TenantId tenant = TenantId.of(UUID.randomUUID());
  final UUID planId = UUID.randomUUID();
  final UUID periodId = UUID.randomUUID();
  final UUID unit = UUID.randomUUID();
  final UUID v1 = UUID.randomUUID();
  final UUID v2 = UUID.randomUUID();
  final UUID planner = UUID.randomUUID();
  final UUID head = UUID.randomUUID();
  final UUID catalogId = UUID.randomUUID();

  final PlanRepository plans = mock(PlanRepository.class);
  final PlanTasks tasks = mock(PlanTasks.class);
  final TaskDraftRepository drafts = mock(TaskDraftRepository.class);
  final TaskLifecycleRepository lifecycle = mock(TaskLifecycleRepository.class);
  final TaskTransitionService transitions = mock(TaskTransitionService.class);
  final TaskChangeRequests changes = mock(TaskChangeRequests.class);
  final TaskDeliverables deliverables = mock(TaskDeliverables.class);
  final PeriodApi periods = mock(PeriodApi.class);
  final PlanTaskNotices notices = mock(PlanTaskNotices.class);
  PlanTaskGenerator generator;

  @BeforeEach
  void setUp() {
    generator =
        new PlanTaskGenerator(
            plans,
            tasks,
            drafts,
            lifecycle,
            transitions,
            changes,
            deliverables,
            (t, p) -> TransitionTables.defaults(),
            periods,
            notices,
            Clock.fixed(Instant.parse("2030-05-01T00:00:00Z"), ZoneOffset.UTC));
    when(plans.findPlan(tenant, planId))
        .thenReturn(
            Optional.of(new PlanRow(planId, periodId, BUCKET, "TAP_THE", unit, null, v2, 3)));
    when(plans.approvalRow(eq(tenant), eq(BUCKET), any()))
        .thenReturn(Optional.of(new ApprovalRow(planner, null, head)));
    when(periods.findById(tenant, PeriodId.of(periodId)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(periodId),
                    tenant,
                    "2030-Q2",
                    "Quý II năm 2030",
                    LocalDate.of(2030, 4, 1),
                    LocalDate.of(2030, 6, 30),
                    PeriodStatus.MO)));
    when(plans.catalogItems(eq(tenant), anyCollection()))
        .thenReturn(
            Map.of(
                catalogId,
                new CatalogRow(
                    catalogId,
                    "DM-01",
                    "Báo cáo",
                    "báo cáo",
                    2,
                    new BigDecimal("5"),
                    "Đúng hạn",
                    "Văn bản")));
    when(drafts.nextCodeNumber(tenant, BUCKET)).thenReturn(7);
  }

  ItemRow item(UUID id, int no, Assignee lead, UUID origin, UUID catalog, LocalDate due) {
    return new ItemRow(
        id,
        no,
        catalog,
        null,
        null,
        "Công việc " + no,
        "THEN_CHOT",
        lead,
        List.of(new Assignee("PERSON", UUID.randomUUID(), "")),
        catalog == null ? "Sản phẩm" : null,
        catalog == null ? BigDecimal.ONE : null,
        "báo cáo",
        catalog == null ? 3 : null,
        catalog == null ? BigDecimal.TEN : null,
        due,
        origin,
        null,
        null);
  }

  @Test
  void khongCoKeHoachThiBoQua() {
    UUID other = UUID.randomUUID();
    assertThat(generator.onApproved(tenant, other, v1, null, head).created()).isZero();
    verify(notices, never()).generated(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void dongMoiSinhNhiemVuNhapLayGiaTriDanhMuc() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID done = UUID.randomUUID();
    when(plans.items(tenant, BUCKET, v1))
        .thenReturn(
            List.of(
                item(a, 1, new Assignee("UNIT", unit, ""), null, null, LocalDate.of(2030, 5, 1)),
                item(b, 2, new Assignee("PERSON", planner, ""), null, catalogId, null),
                item(done, 3, new Assignee("UNIT", unit, ""), null, null, null)));
    when(tasks.linked(eq(tenant), eq(BUCKET), anyCollection()))
        .thenReturn(List.of(new LinkedTask(UUID.randomUUID(), "NV-1", done, "NHAP", 0)));
    PlanTaskGenerator.Summary s = generator.onApproved(tenant, planId, v1, null, head);
    assertThat(s.created()).isEqualTo(2);
    ArgumentCaptor<NewDraft> captor = ArgumentCaptor.forClass(NewDraft.class);
    verify(drafts, org.mockito.Mockito.times(2)).insertDraft(eq(tenant), captor.capture(), any());
    NewDraft first = captor.getAllValues().get(0);
    assertThat(first.code()).isEqualTo("NV-2030-Q2-0007");
    assertThat(first.leadUnitId()).isEqualTo(unit);
    assertThat(first.spec().source()).isEqualTo("KE_HOACH");
    assertThat(first.difficulty()).isEqualTo(3);
    NewDraft second = captor.getAllValues().get(1);
    assertThat(second.code()).isEqualTo("NV-2030-Q2-0008");
    assertThat(second.spec().leadPersonId()).isEqualTo(planner);
    assertThat(second.spec().dueDate()).isEqualTo(LocalDate.of(2030, 6, 30));
    assertThat(second.spec().productName()).isEqualTo("Báo cáo");
    assertThat(second.spec().qualityCriteria()).isEqualTo("Đúng hạn");
    assertThat(second.difficulty()).isEqualTo(2);
    assertThat(second.standardScore()).isEqualByComparingTo("5");
    verify(lifecycle, org.mockito.Mockito.times(2))
        .appendStateLog(eq(tenant), eq(periodId), eq(BUCKET), any(), any(), any());
    verify(notices).generated(tenant, planId, v1, planner, 2, 0, 0);
  }

  @Test
  void dieuChinhCapNhatVaHuyTheoTrangThai() {
    UUID o1 = UUID.randomUUID();
    UUID o2 = UUID.randomUUID();
    UUID o3 = UUID.randomUUID();
    UUID o4 = UUID.randomUUID();
    UUID o5 = UUID.randomUUID();
    UUID o6 = UUID.randomUUID();
    UUID o7 = UUID.randomUUID();
    UUID o8 = UUID.randomUUID();
    UUID o9 = UUID.randomUUID();
    UUID n1 = UUID.randomUUID();
    UUID n2 = UUID.randomUUID();
    UUID n3 = UUID.randomUUID();
    UUID n4 = UUID.randomUUID();
    UUID n5 = UUID.randomUUID();
    Assignee lead = new Assignee("UNIT", unit, "");
    LocalDate due = LocalDate.of(2030, 6, 1);
    when(plans.items(tenant, BUCKET, v2))
        .thenReturn(
            List.of(
                item(n1, 1, lead, o1, null, due),
                item(n2, 2, lead, o2, null, due),
                item(n3, 3, lead, o3, null, due),
                item(n4, 4, lead, o4, null, due),
                item(n5, 5, lead, o5, null, due)));
    when(plans.items(tenant, BUCKET, v1))
        .thenReturn(
            List.of(
                item(o1, 1, lead, null, null, due),
                item(o2, 2, lead, null, null, due),
                item(o3, 3, lead, null, null, due),
                item(o4, 4, lead, null, null, due),
                item(o5, 5, lead, null, null, due),
                item(o6, 6, lead, null, null, due),
                item(o7, 7, lead, null, null, due),
                item(o8, 8, lead, null, null, due),
                item(o9, 9, lead, null, null, due)));
    UUID t1 = UUID.randomUUID();
    UUID t2 = UUID.randomUUID();
    UUID t3 = UUID.randomUUID();
    UUID t4 = UUID.randomUUID();
    UUID t5 = UUID.randomUUID();
    UUID t6 = UUID.randomUUID();
    UUID t7 = UUID.randomUUID();
    UUID t8 = UUID.randomUUID();
    UUID t9 = UUID.randomUUID();
    when(tasks.linked(eq(tenant), eq(BUCKET), anyCollection()))
        .thenReturn(
            List.of(
                new LinkedTask(t1, "NV-1", o1, "NHAP", 2),
                new LinkedTask(t2, "NV-2", o2, "DA_GIAO", 3),
                new LinkedTask(t3, "NV-3", o3, "DANG_THUC_HIEN", 4),
                new LinkedTask(t4, "NV-4", o4, "HUY", 4),
                new LinkedTask(t5, "NV-5", o5, "NHAP", 1),
                new LinkedTask(t6, "NV-6", o6, "NHAP", 1),
                new LinkedTask(t7, "NV-7", o7, "DA_GIAO", 1),
                new LinkedTask(t8, "NV-8", o8, "TAM_DUNG", 1),
                new LinkedTask(t9, "NV-9", o9, "CHO_NGHIEM_THU", 1)));
    TaskDraftSpec current =
        new TaskDraftSpec(
            periodId,
            "KE_HOACH",
            o1,
            null,
            "Cũ",
            "THEN_CHOT",
            null,
            "CAO",
            null,
            List.of(),
            due,
            head,
            List.of(),
            "",
            BigDecimal.ONE,
            "",
            "",
            "",
            null,
            null,
            "Căn cứ");
    when(drafts.findDraft(tenant, t1))
        .thenReturn(
            Optional.of(
                new DraftRow(
                    t1, "NV-1", "NHAP", periodId, BUCKET, unit, planner, false, current, 2)));
    when(drafts.findDraft(tenant, t5)).thenReturn(Optional.empty());
    when(drafts.storeDraft(eq(tenant), any(), eq(2L), any(), any(), any(), any(), any(), any()))
        .thenReturn(3L);
    when(tasks.refreshAssigned(eq(tenant), eq(BUCKET), eq(t2), any(), any(), any()))
        .thenReturn(true);
    // t7: đề nghị huỷ mới; t8: đã có đề nghị huỷ đang chờ.
    when(changes.insert(eq(tenant), any(), any())).thenReturn(true, false);
    when(changes.listOf(tenant, BUCKET, t8))
        .thenReturn(
            List.of(
                change(UUID.randomUUID(), "GIA_HAN", "CHO_DUYET", planner),
                change(UUID.randomUUID(), "HUY", "CHO_DUYET", planner)));
    when(changes.decide(
            eq(tenant), eq(BUCKET), any(), eq("DA_DUYET"), isNull(), any(), eq(head), any()))
        .thenReturn(true);

    PlanTaskGenerator.Summary s = generator.onApproved(tenant, planId, v2, v1, head);

    assertThat(s.updated()).isEqualTo(2);
    assertThat(s.created()).isEqualTo(1);
    assertThat(s.discarded()).isEqualTo(1);
    assertThat(s.cancelled()).isEqualTo(2);
    assertThat(s.blocked()).containsExactly("NV-9");
    ArgumentCaptor<TaskDraftSpec> spec = ArgumentCaptor.forClass(TaskDraftSpec.class);
    verify(drafts)
        .storeDraft(eq(tenant), any(), eq(2L), spec.capture(), any(), any(), any(), any(), any());
    assertThat(spec.getValue().planItemId()).isEqualTo(n1);
    assertThat(spec.getValue().priority()).isEqualTo("CAO");
    assertThat(spec.getValue().acceptorId()).isEqualTo(head);
    ArgumentCaptor<ItemContent> content = ArgumentCaptor.forClass(ItemContent.class);
    verify(tasks).refreshAssigned(eq(tenant), eq(BUCKET), eq(t2), content.capture(), any(), any());
    assertThat(content.getValue().planItemId()).isEqualTo(n2);
    verify(tasks).relink(eq(tenant), eq(BUCKET), eq(t3), eq(n3), eq(planner), any());
    verify(tasks).relink(eq(tenant), eq(BUCKET), eq(t5), eq(n5), eq(planner), any());
    verify(drafts).discardDraft(tenant, BUCKET, t6, 1);
    verify(transitions)
        .transition(eq(tenant), eq(t7), any(TaskCommand.Cancel.class), any(), isNull());
    verify(transitions)
        .transition(eq(tenant), eq(t8), any(TaskCommand.Cancel.class), any(), isNull());
    verify(notices).generated(tenant, planId, v2, planner, 1, 2, 3);
    verify(notices).cancelBlocked(tenant, planId, v2, planner, List.of("NV-9"));
  }

  @Test
  void khongHuyKhiCoMinhChungHoacCungMotNguoi() {
    UUID o1 = UUID.randomUUID();
    UUID o2 = UUID.randomUUID();
    UUID o3 = UUID.randomUUID();
    Assignee lead = new Assignee("UNIT", unit, "");
    when(plans.items(tenant, BUCKET, v2)).thenReturn(List.of());
    when(plans.items(tenant, BUCKET, v1))
        .thenReturn(
            List.of(
                item(o1, 1, lead, null, null, null),
                item(o2, 2, lead, null, null, null),
                item(o3, 3, lead, null, null, null)));
    UUID t1 = UUID.randomUUID();
    UUID t2 = UUID.randomUUID();
    UUID t3 = UUID.randomUUID();
    when(tasks.linked(eq(tenant), eq(BUCKET), anyCollection()))
        .thenReturn(
            List.of(
                new LinkedTask(t1, "NV-1", o1, "DANG_THUC_HIEN", 1),
                new LinkedTask(t2, "NV-2", o2, "DA_GIAO", 1),
                new LinkedTask(t3, "NV-3", o3, "DA_GIAO", 1)));
    when(deliverables.of(tenant, t1)).thenReturn(List.of(Map.of("status", "DA_XAC_NHAN")));
    // t2: người duyệt trùng người lập; t3: đã có đề nghị nhưng không duyệt được.
    when(plans.approvalRow(eq(tenant), eq(BUCKET), any()))
        .thenReturn(Optional.of(new ApprovalRow(head, null, head)));
    when(changes.listOf(tenant, BUCKET, t2)).thenReturn(List.of());
    when(changes.listOf(tenant, BUCKET, t3))
        .thenReturn(List.of(change(UUID.randomUUID(), "HUY", "CHO_DUYET", UUID.randomUUID())));
    when(changes.decide(any(), anyInt(), any(), any(), any(), any(), any(), any()))
        .thenReturn(false);

    PlanTaskGenerator.Summary s = generator.onApproved(tenant, planId, v2, v1, head);

    assertThat(s.blocked()).containsExactly("NV-1", "NV-2", "NV-3");
    verify(changes, never()).insert(any(), any(), any());
    verify(transitions, never()).transition(any(), any(), any(), any(), any());
    verify(drafts, never()).discardDraft(any(), anyInt(), any(), anyLong());
  }

  ChangeRow change(UUID id, String type, String status, UUID author) {
    return new ChangeRow(
        id,
        UUID.randomUUID(),
        type,
        status,
        null,
        null,
        null,
        null,
        null,
        "",
        head,
        author,
        Instant.now(),
        null,
        null,
        null,
        null,
        false,
        0);
  }

  @Test
  void giaTriMacDinhKhiThieu() {
    ItemRow bare =
        new ItemRow(
            UUID.randomUUID(),
            1,
            null,
            null,
            null,
            "X",
            "THEN_CHOT",
            null,
            null,
            " ",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    assertThat(PlanTaskGenerator.product(bare, null)).isEmpty();
    assertThat(PlanTaskGenerator.quantity(bare)).isEqualByComparingTo("1");
    assertThat(PlanTaskGenerator.difficulty(bare, null)).isNull();
    assertThat(PlanTaskGenerator.score(bare, null)).isNull();
  }
}
