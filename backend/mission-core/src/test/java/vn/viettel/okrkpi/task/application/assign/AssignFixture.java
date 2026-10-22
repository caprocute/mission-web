package vn.viettel.okrkpi.task.application.assign;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.NewDraft;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TransitionTableProvider;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTable;

/** Dựng chung cho kiểm thử soạn và giao nhiệm vụ: kho nháp trong bộ nhớ trên mock Mockito. */
final class AssignFixture {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID ASSIGNER = UUID.randomUUID();
  static final UUID LEAD = UUID.randomUUID();
  static final LocalDate START = LocalDate.of(2026, 10, 1);
  static final LocalDate END = LocalDate.of(2026, 12, 31);
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"), ZoneOffset.UTC);
  static final TaskActor MANAGER = new TaskActor(ASSIGNER, java.util.Set.of("manager"));

  final TaskDraftRepository repo = mock(TaskDraftRepository.class);
  final PeriodApi periods = mock(PeriodApi.class);
  final OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  final TransitionTableProvider tables = mock(TransitionTableProvider.class);
  final Map<UUID, DraftRow> rows = new HashMap<>();
  final TaskDraftService drafts;

  AssignFixture() {
    PeriodSnapshot p = period();
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.of(p));
    when(periods.findCurrent(TENANT)).thenReturn(Optional.of(p));
    when(org.assignmentsOf(eq(TENANT), any(), any())).thenReturn(List.of(assignment(UNIT)));
    when(tables.tableFor(any(), any())).thenReturn(new TransitionTable(List.of(), "mac-dinh@1"));
    when(repo.nextCodeNumber(eq(TENANT), anyInt())).thenReturn(1);
    when(repo.findDraft(eq(TENANT), any()))
        .thenAnswer(i -> Optional.ofNullable(rows.get(i.<UUID>getArgument(1))));
    doAnswer(
            i -> {
              NewDraft d = i.getArgument(1);
              rows.put(
                  d.id(),
                  new DraftRow(
                      d.id(),
                      d.code(),
                      "NHAP",
                      d.periodId(),
                      d.periodBucket(),
                      d.orgUnitId(),
                      d.assignerId(),
                      false,
                      d.spec(),
                      0));
              return null;
            })
        .when(repo)
        .insertDraft(eq(TENANT), any(), any());
    drafts = new TaskDraftService(repo, periods, org, tables, CLOCK);
  }

  static PeriodSnapshot period() {
    return new PeriodSnapshot(
        PeriodId.of(PERIOD), TENANT, "2026-Q4", "Quý IV/2026", START, END, PeriodStatus.MO);
  }

  static AssignmentView assignment(UUID unit) {
    return new AssignmentView(
        UUID.randomUUID(), LEAD, unit, null, null, "CHINH", "CHINH", false, START, null);
  }

  static TaskDraftSpec spec(String source, UUID lead) {
    return new TaskDraftSpec(
        PERIOD,
        source,
        "KE_HOACH".equals(source) ? UUID.randomUUID() : null,
        null,
        "  Hoàn thành báo cáo quý  ",
        "THUONG_XUYEN",
        null,
        null,
        lead,
        List.of(),
        LocalDate.of(2026, 11, 30),
        null,
        List.of(),
        "Báo cáo",
        BigDecimal.ONE,
        "bản",
        "Tệp báo cáo",
        "Đúng mẫu",
        null,
        null,
        "Chỉ đạo của lãnh đạo");
  }

  DraftRow put(String status, TaskDraftSpec spec, boolean restricted, long version) {
    UUID id = UUID.randomUUID();
    DraftRow row =
        new DraftRow(
            id,
            "NV-2026-Q4-0009",
            status,
            PERIOD,
            20264,
            UNIT,
            ASSIGNER,
            restricted,
            spec,
            version);
    rows.put(id, row);
    return row;
  }

  static TaskRow task(UUID id, TaskStatus status, UUID acceptor) {
    return new TaskRow(
        id,
        PERIOD,
        20264,
        "NV-2026-Q4-0009",
        "Hoàn thành báo cáo quý",
        null,
        "THUONG_XUYEN",
        "DOT_XUAT",
        null,
        ASSIGNER,
        null,
        acceptor,
        LocalDate.of(2026, 11, 30),
        null,
        null,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        "bản",
        null,
        null,
        null,
        status,
        false,
        null,
        "mac-dinh@1",
        1);
  }
}
