package vn.viettel.okrkpi.task.application.change;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskStatusChangedEvent;
import vn.viettel.okrkpi.task.application.change.port.ChangeRequestNotices;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests.ChangeRow;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.StateLogRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskStatusEvents;
import vn.viettel.okrkpi.task.domain.change.ChangeRules;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

/**
 * Quyết định đề nghị và áp dụng hệ quả lên nhiệm vụ (QT-06 bước 6, 7; SCR-0601, CORE-02-22), cùng
 * lệnh tiếp tục sau tạm dừng (BR-06-10). Mọi quyết định bắt buộc chọn nguyên nhân và ghi lý do;
 * người gửi không tự duyệt (BR-06-08); đề nghị vượt ngưỡng BR-06-06 chỉ lãnh đạo cấp trên (AC-04)
 * quyết định. Chấp nhận áp dụng trong cùng giao dịch: gia hạn khách quan dời cả hạn thực hiện và
 * hạn tính điểm, chủ quan chỉ dời hạn thực hiện (BR-06-04, BR-06-05); tạm dừng, huỷ đi qua máy
 * trạng thái.
 */
public class TaskChangeDecisionService {

  static final String TX = "taskTransactionManager";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String FORBIDDEN = "COMMON-403-OUT-OF-SCOPE";
  static final String SAME_AUTHOR = "TASK-403-SAME-AUTHOR";
  static final String INVALID_STATE = "TASK-409-INVALID-STATE";
  static final String PERIOD_LOCKED = "TASK-409-PERIOD-LOCKED";
  static final String LEADER = "leader";
  static final String T_APPROVED = "DE_NGHI_DA_DUYET";
  static final String T_REJECTED = "DE_NGHI_TU_CHOI";
  private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

  private final TaskChangeRequests changes;
  private final TaskLifecycleRepository tasks;
  private final TaskTransitionService transitions;
  private final TaskStatusEvents events;
  private final PeriodApi periods;
  private final WorkingCalendarApi calendar;
  private final TaskDeliverables deliverables;
  private final ChangeRequestNotices notices;
  private final Clock clock;

  public TaskChangeDecisionService(
      TaskChangeRequests changes,
      TaskLifecycleRepository tasks,
      TaskTransitionService transitions,
      TaskStatusEvents events,
      PeriodApi periods,
      WorkingCalendarApi calendar,
      TaskDeliverables deliverables,
      ChangeRequestNotices notices,
      Clock clock) {
    this.changes = Objects.requireNonNull(changes, "changes");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.events = Objects.requireNonNull(events, "events");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.calendar = calendar;
    this.deliverables = deliverables;
    this.notices = Objects.requireNonNull(notices, "notices");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Quyết định ({@code ChangeRequestDecision}). */
  public record Decision(Boolean approve, String causeDecided, String note) {}

  /** Nhiệm vụ sau khi áp dụng kèm đề nghị đã quyết định. */
  public record Result(TaskRow task, ChangeRow request) {}

  /**
   * Quyết định đề nghị; {@code expectedVersion} (từ {@code If-Match}, có thể rỗng) so với {@code
   * row_version} của đề nghị. Cùng người gọi lại cùng quyết định cho đề nghị đã quyết định thì trả
   * kết quả hiện tại mà không áp dụng lần nữa (worker của tiến trình chạy lại cùng job).
   */
  @Transactional(TX)
  public Result decide(
      TenantId tenant,
      UUID taskId,
      UUID requestId,
      Long expectedVersion,
      Decision d,
      TaskActor actor) {
    TaskRow task = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    ChangeRow r =
        changes
            .find(tenant, task.periodBucket(), taskId, requestId)
            .orElseThrow(NoSuchElementException::new);
    UUID me = actor.personId();
    if (me.equals(r.createdBy())) {
      throw new BusinessException(SAME_AUTHOR, "Người gửi đề nghị không tự duyệt được đề nghị");
    }
    if (d == null || d.approve() == null) {
      throw invalid("approve", "Chưa chọn chấp nhận hay từ chối");
    }
    String outcome = d.approve() ? "DA_DUYET" : "TU_CHOI";
    if (!ChangeRules.PENDING.equals(r.status())) {
      if (outcome.equals(r.status()) && me.equals(r.decidedBy())) {
        return new Result(task, r);
      }
      throw new BusinessException(INVALID_STATE, "Đề nghị đã được xử lý");
    }
    requireDecider(task, r, actor);
    if (expectedVersion != null && expectedVersion != r.rowVersion()) {
      throw new VersionConflictException("TASK", expectedVersion, r.rowVersion());
    }
    if (d.causeDecided() == null || !ChangeRules.CAUSES.contains(d.causeDecided())) {
      throw invalid("causeDecided", "Chưa xác định nguyên nhân khách quan hay chủ quan");
    }
    // Ý kiến không bắt buộc khi duyệt, bắt buộc khi từ chối (hợp đồng ChangeRequestDecision.note)
    boolean noNote = d.note() == null || d.note().isBlank();
    if (noNote && !Boolean.TRUE.equals(d.approve()) || !noNote && d.note().length() > 2000) {
      throw invalid("note", "Vui lòng ghi lý do của quyết định (tối đa 2.000 ký tự)");
    }
    try {
      periods.assertWritable(tenant, PeriodId.of(task.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(PERIOD_LOCKED, "Kỳ đã khoá, không duyệt được đề nghị");
    }
    Instant now = clock.instant();
    if (!changes.decide(
        tenant,
        task.periodBucket(),
        requestId,
        outcome,
        d.causeDecided(),
        noNote ? null : d.note().strip(),
        me,
        now)) {
      throw new BusinessException(INVALID_STATE, "Đề nghị đã được xử lý");
    }
    TaskRow after = d.approve() ? apply(tenant, task, r, d, actor, now) : task;
    ChangeRow decided =
        changes
            .find(tenant, task.periodBucket(), taskId, requestId)
            .orElseThrow(NoSuchElementException::new);
    notify(tenant, after, decided, d.approve() ? T_APPROVED : T_REJECTED, actor);
    return new Result(after, decided);
  }

  /**
   * Tiếp tục nhiệm vụ đang tạm dừng (lệnh RESUME), dời hạn thực hiện và hạn tính điểm thêm đúng số
   * ngày làm việc đã tạm dừng (BR-06-10).
   */
  @Transactional(TX)
  public TaskRow resume(TenantId tenant, UUID taskId, long expectedVersion, TaskActor actor) {
    TaskRow task = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    LocalDate today = LocalDate.now(clock.withZone(VN));
    LocalDate suspended =
        tasks.stateLog(tenant, task.periodBucket(), taskId).stream()
            .filter(l -> l.to() == TaskStatus.TAM_DUNG && l.from() != TaskStatus.TAM_DUNG)
            .max(Comparator.comparing(StateLogRow::occurredAt))
            .map(l -> LocalDate.ofInstant(l.occurredAt(), VN))
            .orElse(today);
    TaskRow resumed =
        transitions
            .transition(tenant, taskId, new TaskCommand.Resume(), actor, expectedVersion)
            .task();
    long paused = workingDays(tenant, suspended, today);
    if (paused <= 0) {
      return resumed;
    }
    LocalDate due = due(resumed);
    LocalDate scoreDue = resumed.scoreDueDate() != null ? resumed.scoreDueDate() : due;
    long v =
        changes.applyToTask(
            tenant,
            resumed.periodBucket(),
            taskId,
            resumed.rowVersion(),
            new TaskChangeRequests.TaskChange(
                plusWorkingDays(tenant, due, paused),
                plusWorkingDays(tenant, scoreDue, paused),
                null,
                null,
                actor.personId(),
                clock.instant()));
    if (v < 0) {
      throw new VersionConflictException("TASK", resumed.rowVersion(), null);
    }
    return tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
  }

  private TaskRow apply(
      TenantId tenant, TaskRow task, ChangeRow r, Decision d, TaskActor actor, Instant now) {
    boolean objective = "KHACH_QUAN".equals(d.causeDecided());
    TaskChangeRequests.TaskChange change =
        switch (r.type()) {
          case "GIA_HAN" -> {
            LocalDate oldDue = due(task);
            LocalDate scoreDue = task.scoreDueDate() != null ? task.scoreDueDate() : oldDue;
            yield new TaskChangeRequests.TaskChange(
                r.proposedDueDate(),
                objective ? r.proposedDueDate() : scoreDue,
                null,
                null,
                actor.personId(),
                now);
          }
          case "DOI_SO_LUONG" ->
              new TaskChangeRequests.TaskChange(
                  null, null, r.proposedQuantity(), null, actor.personId(), now);
          case "DOI_SAN_PHAM" ->
              new TaskChangeRequests.TaskChange(
                  null, null, null, r.newProduct(), actor.personId(), now);
          default -> null;
        };
    if (change != null) {
      TaskRow locked = tasks.lock(tenant, task.id()).orElseThrow(NoSuchElementException::new);
      long v =
          changes.applyToTask(tenant, task.periodBucket(), task.id(), locked.rowVersion(), change);
      if (v < 0) {
        throw new VersionConflictException("TASK", locked.rowVersion(), null);
      }
      String transition = "GIA_HAN".equals(r.type()) ? r.type() + "_" + d.causeDecided() : r.type();
      events.statusChanged(
          new TaskStatusChangedEvent(
              tenant,
              task.id(),
              task.periodId(),
              locked.status().name(),
              locked.status().name(),
              transition,
              locked.overdue(),
              reason(d, r),
              actor.personId(),
              v,
              locked.stateMachineVersion(),
              now));
      return tasks.find(tenant, task.id()).orElseThrow(NoSuchElementException::new);
    }
    TaskCommand command =
        "TAM_DUNG".equals(r.type())
            ? new TaskCommand.Suspend(reason(d, r))
            : new TaskCommand.Cancel(reason(d, r));
    if ("HUY".equals(r.type())
        && deliverables != null
        && deliverables.of(tenant, task.id()).stream()
            .anyMatch(m -> "DA_XAC_NHAN".equals(m.get("status")))) {
      throw new BusinessException(
          INVALID_STATE,
          "Nhiệm vụ đã có sản phẩm được nghiệm thu nên không thể huỷ.",
          List.of(),
          "MSG-06-09");
    }
    Set<String> roles = new HashSet<>(actor.roles());
    roles.add("manager");
    return transitions
        .transition(tenant, task.id(), command, new TaskActor(actor.personId(), roles), null)
        .task();
  }

  /**
   * Người gửi đã bị chặn trước; đề nghị vượt ngưỡng chỉ lãnh đạo (AC-04) khác người giao quyết
   * định, đề nghị thường thì người duyệt được chỉ định (người giao) hoặc lãnh đạo.
   */
  private static void requireDecider(TaskRow task, ChangeRow r, TaskActor actor) {
    UUID me = actor.personId();
    boolean leader = actor.roles().contains(LEADER);
    if (r.escalated()) {
      if (!leader || me.equals(r.approverId()) || me.equals(task.assignerId())) {
        throw new BusinessException(
            FORBIDDEN,
            "Đề nghị vượt ngưỡng (gia hạn lần thứ ba trong quý hoặc quá 10 ngày làm việc) cần lãnh"
                + " đạo cấp trên duyệt; đề nghị đã được chuyển lên cấp trên.");
      }
      return;
    }
    if (!me.equals(r.approverId()) && !leader) {
      throw new BusinessException(FORBIDDEN, "Chỉ người duyệt được chỉ định mới quyết định được");
    }
  }

  private void notify(
      TenantId tenant, TaskRow task, ChangeRow r, String template, TaskActor actor) {
    if (r.createdBy() == null || r.createdBy().equals(actor.personId())) {
      return;
    }
    Map<String, String> p = new LinkedHashMap<>();
    p.put("maNhiemVu", task.code());
    p.put("tenNhiemVu", task.title());
    p.put("loaiDeNghi", r.type());
    if (r.causeDecided() != null) {
      p.put("nguyenNhan", r.causeDecided());
    }
    notices.notice(
        new ChangeRequestNotices.Notice(
            tenant,
            task.id(),
            r.id(),
            r.rowVersion(),
            template,
            List.of(r.createdBy()),
            p,
            actor.personId()));
  }

  private static LocalDate due(TaskRow t) {
    return t.extendedDueDate() != null ? t.extendedDueDate() : t.dueDate();
  }

  long workingDays(TenantId tenant, LocalDate from, LocalDate to) {
    if (!to.isAfter(from)) {
      return 0;
    }
    if (calendar != null) {
      return calendar.countWorkingDays(tenant, from, to);
    }
    long n = 0;
    for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
      n += weekday(d) ? 1 : 0;
    }
    return n;
  }

  LocalDate plusWorkingDays(TenantId tenant, LocalDate from, long n) {
    if (calendar != null) {
      return calendar.addWorkingDays(tenant, from, (int) n);
    }
    LocalDate d = from;
    for (long left = n; left > 0; ) {
      d = d.plusDays(1);
      if (weekday(d)) {
        left--;
      }
    }
    return d;
  }

  private static boolean weekday(LocalDate d) {
    return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, null)));
  }

  /** Lý do ghi vào nhật ký trạng thái: ý kiến của người duyệt, không có thì lý do của đề nghị. */
  private static String reason(Decision d, ChangeRow r) {
    return d.note() == null || d.note().isBlank() ? r.reason() : d.note().strip();
  }
}
