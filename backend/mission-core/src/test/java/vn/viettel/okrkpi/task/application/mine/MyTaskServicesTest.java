package vn.viettel.okrkpi.task.application.mine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.TaskQueryService;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.UnitTaskRow;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries;
import vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries.MineFilter;
import vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries.MineRow;
import vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries.OpenAssignment;
import vn.viettel.okrkpi.task.application.mine.port.TaskExportFiles;
import vn.viettel.okrkpi.task.application.mine.port.TaskExportJobs;
import vn.viettel.okrkpi.task.application.mine.port.TaskExportSheets;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;
import vn.viettel.okrkpi.task.domain.mine.QuickFilter;

class MyTaskServicesTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID ME = UUID.randomUUID();
  static final TaskActor MEMBER = new TaskActor(ME, Set.of("member"));
  // 01/09/2026 là thứ Ba (giờ Việt Nam).
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T02:00:00Z"), ZoneOffset.UTC);
  static final LocalDate TODAY = LocalDate.of(2026, 9, 1);

  final MyTaskQueries queries = mock(MyTaskQueries.class);
  final PeriodApi periods = mock(PeriodApi.class);
  final WorkingCalendarApi calendar = mock(WorkingCalendarApi.class);
  final OkrApi okr = mock(OkrApi.class);
  final TaskTransitionService transitions = mock(TaskTransitionService.class);

  MyTaskServicesTest() {
    PeriodSnapshot p =
        new PeriodSnapshot(
            PeriodId.of(PERIOD),
            TENANT,
            "2026-Q3",
            "Quý III/2026",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 9, 30),
            PeriodStatus.MO);
    when(periods.findCurrent(TENANT)).thenReturn(Optional.of(p));
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.of(p));
    when(calendar.isWorkingDay(eq(TENANT), any()))
        .thenAnswer(
            i -> {
              LocalDate d = i.getArgument(1);
              return d.getDayOfWeek().getValue() < 6 && !d.equals(LocalDate.of(2026, 9, 2));
            });
  }

  MyTaskService service(WorkingCalendarApi cal, OkrApi okrApi) {
    return new MyTaskService(queries, periods, cal, okrApi, transitions, 3, CLOCK);
  }

  static TaskRow task(UUID id, TaskStatus status, LocalDate due, boolean overdue) {
    return new TaskRow(
        id,
        PERIOD,
        20263,
        "NV-2026-Q3-0001",
        "Báo cáo",
        null,
        "THUONG_XUYEN",
        "KE_HOACH",
        null,
        UUID.randomUUID(),
        null,
        null,
        due,
        null,
        null,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        "bản",
        null,
        null,
        new BigDecimal("0.4"),
        status,
        overdue,
        null,
        "v1",
        2);
  }

  static MineRow row(TaskStatus status, LocalDate due, boolean overdue) {
    return new MineRow(task(UUID.randomUUID(), status, due, overdue), "CHU_TRI", 0, due);
  }

  @Test
  void danhSachTinhNgayLamViecVaSapHanTheoLich() {
    UUID kr = UUID.randomUUID();
    when(queries.listMine(eq(TENANT), eq(20263), any(), any(), any(), anyInt()))
        .thenReturn(
            List.of(
                row(TaskStatus.DANG_THUC_HIEN, LocalDate.of(2026, 8, 28), true),
                row(TaskStatus.DA_GIAO, LocalDate.of(2026, 9, 3), false)));
    when(queries.countMine(eq(TENANT), eq(20263), any()))
        .thenReturn(Map.of(QuickFilter.TAT_CA, 2, QuickFilter.CHAM, 1));
    when(queries.keyResultsOfMine(TENANT, 20263, ME, PERIOD)).thenReturn(List.of(kr));
    when(okr.findKeyResultById(TENANT, kr))
        .thenReturn(
            Optional.of(
                new OkrApi.KeyResultSummary(kr, TENANT, PeriodId.of(PERIOD), "KR 1", 1, 0)));
    MyTaskService.MinePage page =
        service(calendar, okr)
            .listMine(
                TENANT,
                new MyTaskService.MineQuery(null, null, false, null, null, null, null, null, null),
                MEMBER,
                null,
                null,
                21);
    assertThat(page.items())
        .extracting(MyTaskService.MineItem::workingDaysLeft)
        .containsExactly(-2, 1);
    assertThat(page.keyResults()).containsEntry(kr, "KR 1");
    assertThat(page.counts()).containsEntry(QuickFilter.CHAM, 1);
    ArgumentCaptor<MineFilter> f = ArgumentCaptor.forClass(MineFilter.class);
    verify(queries).countMine(eq(TENANT), eq(20263), f.capture());
    assertThat(f.getValue().quick()).isEqualTo(QuickFilter.TAT_CA);
    // Ba ngày làm việc sau 01/09, bỏ ngày lễ 02/09: 03, 04, 07/09.
    assertThat(f.getValue().soonUntil()).isEqualTo(LocalDate.of(2026, 9, 7));
    assertThat(f.getValue().statuses()).isEmpty();
  }

  @Test
  void khongLichThiThuHaiDenThuSauVaKyKhongCo() {
    when(queries.listMine(eq(TENANT), eq(20263), any(), any(), any(), anyInt()))
        .thenReturn(List.of(row(TaskStatus.DA_GIAO, LocalDate.of(2026, 9, 3), false)));
    MyTaskService svc = service(null, null);
    List<MyTaskService.MineItem> rows =
        svc.rowsForExport(
            TENANT,
            new MyTaskService.MineQuery(
                PERIOD, QuickFilter.SAP_HAN, true, Set.of(), null, null, null, null, null),
            ME,
            null,
            null,
            10);
    assertThat(rows.get(0).workingDaysLeft()).isEqualTo(2);
    when(queries.keyResultsOfMine(TENANT, 20263, ME, PERIOD))
        .thenReturn(List.of(UUID.randomUUID()));
    when(queries.listMine(eq(TENANT), eq(20263), any(), any(), any(), anyInt()))
        .thenReturn(List.of());
    assertThat(
            svc.listMine(
                    TENANT,
                    new MyTaskService.MineQuery(
                        PERIOD, null, false, null, null, null, null, null, null),
                    MEMBER,
                    null,
                    null,
                    5)
                .keyResults()
                .values())
        .containsExactly("");
    UUID other = UUID.randomUUID();
    when(periods.findById(TENANT, PeriodId.of(other))).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                svc.listMine(
                    TENANT,
                    new MyTaskService.MineQuery(
                        other, null, false, null, null, null, null, null, null),
                    MEMBER,
                    null,
                    null,
                    5))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void nhanViecChayAcceptVoiVaiTroNguoiThucHien() {
    UUID id = UUID.randomUUID();
    when(queries.roleOn(TENANT, id, ME))
        .thenReturn(Optional.of("PHOI_HOP"), Optional.of("CHU_TRI"));
    assertThatThrownBy(() -> service(calendar, okr).acceptTask(TENANT, id, 3, MEMBER))
        .isInstanceOf(BusinessException.class);
    service(calendar, okr).acceptTask(TENANT, id, 3, new TaskActor(ME, Set.of("manager")));
    ArgumentCaptor<TaskActor> actor = ArgumentCaptor.forClass(TaskActor.class);
    verify(transitions)
        .transition(eq(TENANT), eq(id), any(TaskCommand.Accept.class), actor.capture(), eq(3L));
    assertThat(actor.getValue().roles()).contains("manager", "member");
  }

  @Test
  void khoiLuongChuTriVuotNguong() {
    LocalDate due = LocalDate.of(2026, 9, 3);
    when(queries.openAssignments(TENANT, 20263, ME, PERIOD))
        .thenReturn(
            List.of(
                new OpenAssignment("CHU_TRI", LocalDate.of(2026, 9, 1)),
                new OpenAssignment("CHU_TRI", LocalDate.of(2026, 9, 4)),
                new OpenAssignment("PHOI_HOP", LocalDate.of(2026, 9, 4))));
    LeadWorkloads.Result r = new LeadWorkloads(queries, 2).of(TENANT, 20263, PERIOD, ME, due);
    assertThat(r.leadCount()).isEqualTo(2);
    assertThat(r.coCount()).isEqualTo(1);
    assertThat(r.inDueWeek()).isEqualTo(3);
    assertThat(r.check().passed()).isFalse();
    assertThat(r.check().rule()).isEqualTo("BR-05-11");
    assertThat(r.weeks()).hasSize(4);
    assertThat(new LeadWorkloads(queries, 3).of(TENANT, 20263, PERIOD, ME, due).check().passed())
        .isTrue();
  }

  @Test
  void xuatViecCuaToiVaViecDonVi() throws Exception {
    TaskExportJobs jobs = mock(TaskExportJobs.class);
    TaskExportFiles files = mock(TaskExportFiles.class);
    TaskQueryService units = mock(TaskQueryService.class);
    List<List<Object>> written = new ArrayList<>();
    TaskExportSheets sheets =
        (OutputStream out, String sheet, List<String> header, Iterable<List<Object>> rows) -> {
          rows.forEach(written::add);
          out.write(1);
        };
    List<Runnable> queued = new ArrayList<>();
    TaskExportService svc =
        new TaskExportService(service(calendar, okr), units, sheets, files, jobs, queued::add);
    when(queries.listMine(eq(TENANT), eq(20263), any(), any(), any(), anyInt()))
        .thenReturn(
            List.of(
                row(TaskStatus.DANG_THUC_HIEN, LocalDate.of(2026, 8, 28), true),
                row(TaskStatus.DA_GIAO, TODAY, false),
                row(TaskStatus.DA_GIAO, LocalDate.of(2026, 9, 4), false),
                row(TaskStatus.HOAN_THANH, TODAY, false)));
    MyTaskService.MineQuery all =
        new MyTaskService.MineQuery(null, null, false, null, null, null, null, null, null);
    String jobId = svc.export(TENANT, new TaskExportService.ExportQuery(all, null, false), MEMBER);
    queued.forEach(Runnable::run);
    assertThat(written)
        .extracting(r -> r.get(5))
        .containsExactly("Trễ 2 ngày", "Hôm nay", "Còn 2 ngày", "");
    assertThat(written.get(0).get(2)).isEqualTo("Chủ trì");
    verify(files).put(eq(TENANT), eq(jobId), eq(ME), any());
    verify(jobs).finished(TENANT, jobId, 4, TaskExportService.LOCATION + jobId);

    // Việc của đơn vị: người chỉ có vai trò thành viên không xuất được.
    TaskQueryService.UnitQuery uq =
        new TaskQueryService.UnitQuery(null, null, Set.of(), null, null, false, null);
    assertThatThrownBy(
            () -> svc.export(TENANT, new TaskExportService.ExportQuery(all, uq, true), MEMBER))
        .isInstanceOf(BusinessException.class);
    TaskActor manager = new TaskActor(ME, Set.of("manager"));
    TaskRow restricted = task(UUID.randomUUID(), TaskStatus.DA_GIAO, TODAY, true);
    when(units.listUnitTasks(eq(TENANT), eq(uq), eq(manager), any(), anyInt()))
        .thenReturn(
            List.of(
                new UnitTaskRow(restricted, UUID.randomUUID(), true, null),
                new UnitTaskRow(
                    task(UUID.randomUUID(), TaskStatus.HUY, TODAY, false), ME, true, null)));
    written.clear();
    queued.clear();
    svc.export(TENANT, new TaskExportService.ExportQuery(all, uq, true), manager);
    queued.forEach(Runnable::run);
    assertThat(written.get(0).get(1)).isEqualTo("Nội dung hạn chế");
    assertThat(written.get(0).get(4)).isEqualTo("Quá hạn");
    assertThat(written.get(1).get(1)).isEqualTo("Báo cáo");
    assertThat(written.get(1).get(5)).isEqualTo("Huỷ");

    // Đang có tác vụ chạy: 429; tác vụ hỏng: báo thất bại.
    when(jobs.running(TENANT, ME)).thenReturn(1);
    assertThatThrownBy(
            () -> svc.export(TENANT, new TaskExportService.ExportQuery(all, null, false), MEMBER))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(TaskExportService.RUNNING));
    when(jobs.running(TENANT, ME)).thenReturn(0);
    doThrow(new IllegalStateException("x")).when(files).put(any(), any(), any(), any());
    queued.clear();
    String failed = svc.export(TENANT, new TaskExportService.ExportQuery(all, null, false), MEMBER);
    queued.forEach(Runnable::run);
    verify(jobs).failed(eq(TENANT), eq(failed), any());
    assertThat(svc.file(TENANT, failed, ME)).isEmpty();
  }

  @Test
  void xuatNhieuTrangDungKhoaConTro() throws IOException {
    List<MineRow> full = new ArrayList<>();
    for (int i = 0; i < TaskExportService.PAGE; i++) {
      full.add(row(TaskStatus.DA_GIAO, TODAY.plusDays(i % 20), false));
    }
    when(queries.listMine(eq(TENANT), eq(20263), any(), any(), any(), anyInt()))
        .thenReturn(full, List.of(row(TaskStatus.DA_GIAO, TODAY, false)));
    TaskExportJobs jobs = mock(TaskExportJobs.class);
    List<Runnable> queued = new ArrayList<>();
    TaskExportService svc =
        new TaskExportService(
            service(calendar, okr),
            mock(TaskQueryService.class),
            (out, sheet, header, rows) -> out.write(0),
            mock(TaskExportFiles.class),
            jobs,
            queued::add);
    String jobId =
        svc.export(
            TENANT,
            new TaskExportService.ExportQuery(
                new MyTaskService.MineQuery(null, null, false, null, null, null, null, null, null),
                null,
                false),
            MEMBER);
    queued.forEach(Runnable::run);
    verify(jobs)
        .finished(TENANT, jobId, TaskExportService.PAGE + 1, TaskExportService.LOCATION + jobId);
    verify(queries)
        .listMine(
            eq(TENANT),
            eq(20263),
            any(),
            eq(full.get(full.size() - 1).effectiveDue()),
            eq(full.get(full.size() - 1).task().id()),
            anyInt());
  }
}
