package vn.viettel.okrkpi.task.application.mine;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries;
import vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries.MineFilter;
import vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries.MineRow;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;
import vn.viettel.okrkpi.task.domain.mine.QuickFilter;
import vn.viettel.okrkpi.task.domain.mine.WorkingDays;
import vn.viettel.okrkpi.task.domain.plan.PlanSchedule;

/**
 * "Việc của tôi" (SCR-0503, SCR-0504, khối việc hôm nay của SCR-0002) và nhận việc (QT-05 bước 8).
 * Danh sách chỉ lọc theo người gọi, không hỏi OPA phạm vi đơn vị (điểm chốt HD-04); "Trễ n ngày"
 * tính theo lịch ngày làm việc của tenant.
 */
public class MyTaskService {

  static final String TX = "taskTransactionManager";
  public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private final MyTaskQueries queries;
  private final PeriodApi periods;
  private final WorkingCalendarApi calendar;
  private final OkrApi okr;
  private final TaskTransitionService transitions;
  private final int soonWorkingDays;
  private final Clock clock;

  public MyTaskService(
      MyTaskQueries queries,
      PeriodApi periods,
      WorkingCalendarApi calendar,
      OkrApi okr,
      TaskTransitionService transitions,
      int soonWorkingDays,
      Clock clock) {
    this.queries = Objects.requireNonNull(queries, "queries");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.calendar = calendar;
    this.okr = okr;
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.soonWorkingDays = soonWorkingDays;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Điều kiện người dùng gửi lên. */
  public record MineQuery(
      UUID periodId,
      QuickFilter quick,
      boolean todayView,
      Set<String> statuses,
      Boolean overdue,
      String role,
      String group,
      UUID keyResultId,
      String q) {}

  /** Một dòng kèm số ngày làm việc còn lại tới hạn (âm khi đã trễ). */
  public record MineItem(MineRow row, int workingDaysLeft) {}

  /** Trang "Việc của tôi": kỳ, các dòng (tối đa {@code fetchSize}), số đếm, KR để lọc. */
  public record MinePage(
      PeriodSnapshot period,
      List<MineItem> items,
      Map<QuickFilter, Integer> counts,
      Map<UUID, String> keyResults) {}

  @Transactional(value = TX, readOnly = true)
  public MinePage listMine(
      TenantId tenant,
      MineQuery query,
      TaskActor actor,
      LocalDate afterDue,
      UUID afterId,
      int fetchSize) {
    PeriodSnapshot period = period(tenant, query.periodId());
    int bucket = PlanSchedule.bucketOf(period.startDate());
    MineFilter filter = filter(tenant, query, actor.personId(), period);
    List<MineRow> rows = queries.listMine(tenant, bucket, filter, afterDue, afterId, fetchSize);
    Map<UUID, String> keyResults = new LinkedHashMap<>();
    for (UUID kr :
        queries.keyResultsOfMine(tenant, bucket, actor.personId(), period.id().value())) {
      keyResults.put(kr, title(tenant, kr));
    }
    return new MinePage(
        period,
        withWorkingDays(tenant, rows, filter.today()),
        queries.countMine(tenant, bucket, filter),
        keyResults);
  }

  /** Chỉ các dòng (không đếm, không KR) cho tác vụ xuất. */
  @Transactional(value = TX, readOnly = true)
  public List<MineItem> rowsForExport(
      TenantId tenant,
      MineQuery query,
      UUID personId,
      LocalDate afterDue,
      UUID afterId,
      int limit) {
    PeriodSnapshot period = period(tenant, query.periodId());
    MineFilter filter = filter(tenant, query, personId, period);
    return withWorkingDays(
        tenant,
        queries.listMine(
            tenant, PlanSchedule.bucketOf(period.startDate()), filter, afterDue, afterId, limit),
        filter.today());
  }

  /**
   * Nhận việc: lệnh ACCEPT của máy trạng thái ({@code DA_GIAO → DANG_THUC_HIEN}, EVT-06); guard chỉ
   * cho người chủ trì; người không phải chủ trì bị chặn trước bằng 403. Vai trò {@code member} được
   * thêm vì người chủ trì nhận việc với tư cách người thực hiện (như cập nhật tiến độ lần đầu,
   * CORE-02-19).
   */
  public TaskTransitionService.Transitioned acceptTask(
      TenantId tenant, UUID taskId, long expected, TaskActor actor) {
    if (!"CHU_TRI".equals(queries.roleOn(tenant, taskId, actor.personId()).orElse(null))) {
      throw new BusinessException(
          TaskTransitionService.ROLE_NOT_ALLOWED, "Chỉ người chủ trì được nhận nhiệm vụ này");
    }
    Set<String> roles = new HashSet<>(actor.roles());
    roles.add("member");
    return transitions.transition(
        tenant, taskId, new TaskCommand.Accept(), new TaskActor(actor.personId(), roles), expected);
  }

  /** Ngày làm việc của tenant; chưa có lịch thì thứ Hai đến thứ Sáu. */
  Predicate<LocalDate> working(TenantId tenant) {
    if (calendar == null) {
      return d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
    return d -> calendar.isWorkingDay(tenant, d);
  }

  LocalDate today() {
    return LocalDate.now(clock.withZone(ZONE));
  }

  private MineFilter filter(TenantId tenant, MineQuery q, UUID person, PeriodSnapshot period) {
    LocalDate today = today();
    return new MineFilter(
        person,
        period.id().value(),
        q.quick() == null ? QuickFilter.TAT_CA : q.quick(),
        q.todayView(),
        q.statuses() == null ? Set.of() : q.statuses(),
        q.overdue(),
        q.role(),
        q.group(),
        q.keyResultId(),
        q.q(),
        today,
        WorkingDays.after(today, soonWorkingDays, working(tenant)));
  }

  private List<MineItem> withWorkingDays(TenantId tenant, List<MineRow> rows, LocalDate today) {
    if (rows.isEmpty()) {
      return List.of();
    }
    LocalDate min = today;
    LocalDate max = today;
    for (MineRow r : rows) {
      LocalDate due = r.effectiveDue();
      if (due.isBefore(min)) {
        min = due;
      }
      if (due.isAfter(max)) {
        max = due;
      }
    }
    WorkingDays days = WorkingDays.over(min, max, working(tenant));
    List<MineItem> out = new ArrayList<>(rows.size());
    for (MineRow r : rows) {
      out.add(new MineItem(r, days.between(today, r.effectiveDue())));
    }
    return out;
  }

  private PeriodSnapshot period(TenantId tenant, UUID periodId) {
    return (periodId == null
            ? periods.findCurrent(tenant)
            : periods.findById(tenant, PeriodId.of(periodId)))
        .orElseThrow(NoSuchElementException::new);
  }

  private String title(TenantId tenant, UUID kr) {
    if (okr == null) {
      return "";
    }
    return okr.findKeyResultById(tenant, kr).map(OkrApi.KeyResultSummary::title).orElse("");
  }
}
