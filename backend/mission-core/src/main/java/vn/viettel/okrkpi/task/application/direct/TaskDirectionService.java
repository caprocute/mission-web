package vn.viettel.okrkpi.task.application.direct;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.viettel.okrkpi.task.application.direct.port.TaskDirections;
import vn.viettel.okrkpi.task.application.direct.port.TaskDirections.Facts;
import vn.viettel.okrkpi.task.application.direct.port.TaskDirections.Lead;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.StateLogRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

/**
 * Chỉ đạo trên bảng điều hành (QT-16 bước 4, SCR-1601): nhắc người chủ trì việc chậm và giao lại
 * việc cho người khác bằng quyền của chính người bấm (BR-16-07). Phạm vi {@code ĐvD}: người bấm là
 * lãnh đạo, quản lý và nhiệm vụ thuộc đơn vị mình hoặc đơn vị cấp dưới (hoặc chính người bấm đã
 * giao). Mỗi lần nhắc và giao lại ghi một dòng {@code task_state_log} giữ nguyên trạng thái để có
 * vết và để chống nhắc dồn.
 */
public class TaskDirectionService {

  static final String TX = "taskTransactionManager";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String FORBIDDEN = "COMMON-403-OUT-OF-SCOPE";
  static final String INVALID_STATE = "TASK-409-INVALID-STATE";
  static final String PERIOD_LOCKED = "TASK-409-PERIOD-LOCKED";
  static final String TOO_MANY = "TASK-429-REMINDED";
  static final String REMIND = "NHAC_VIEC";
  static final String REASSIGN = "GIAO_LAI";
  static final String T_REMIND = "NHAC_VIEC";
  static final String T_REASSIGNED = "NHIEM_VU_DA_GIAO_LAI";
  static final Set<String> REMINDABLE = Set.of("DA_GIAO", "DANG_THUC_HIEN");
  static final Set<String> REASSIGNABLE = Set.of("DA_GIAO", "DANG_THUC_HIEN", "TAM_DUNG");
  static final Set<String> ROLES = Set.of("leader", "manager");
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  static final String RESTRICTED_TITLE = "Nhiệm vụ hạn chế";
  static final String NEEDS_REASSIGN = "CAN_GIAO_LAI";
  static final String T_LEAD_MOVED = "CHU_TRI_CHUYEN_CONG_TAC";
  static final UUID SYSTEM = new UUID(0L, 0L);

  private final TaskLifecycleRepository tasks;
  private final TaskDirections directions;
  private final OrgDirectoryApi org;
  private final PeriodApi periods;
  private final TaskReminderNotices notices;
  private final TaskAssignedEvents assigned;
  private final Duration remindWindow;
  private final Clock clock;

  public TaskDirectionService(
      TaskLifecycleRepository tasks,
      TaskDirections directions,
      OrgDirectoryApi org,
      PeriodApi periods,
      TaskReminderNotices notices,
      TaskAssignedEvents assigned,
      Duration remindWindow,
      Clock clock) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.directions = Objects.requireNonNull(directions, "directions");
    this.org = Objects.requireNonNull(org, "org");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.assigned = Objects.requireNonNull(assigned, "assigned");
    this.remindWindow = Objects.requireNonNull(remindWindow, "remindWindow");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Nhắc người chủ trì (202): nhiệm vụ {@code DA_GIAO}, {@code DANG_THUC_HIEN}; cùng người nhắc,
   * cùng nhiệm vụ trong khoảng {@code remindWindow} chỉ gửi một lần (429).
   */
  @Transactional(TX)
  public void remindTask(TenantId tenant, UUID taskId, String note, TaskActor actor) {
    TaskRow task = tasks.lock(tenant, taskId).orElseThrow(NoSuchElementException::new);
    Facts facts = facts(tenant, task);
    requireScope(tenant, task, facts, actor);
    if (!REMINDABLE.contains(task.status().name())) {
      throw new BusinessException(
          INVALID_STATE, "Chỉ nhắc được nhiệm vụ đã giao hoặc đang thực hiện");
    }
    String text = note == null ? null : note.strip();
    if (text != null && text.length() > 500) {
      throw invalid("note", "Ghi chú không quá 500 ký tự");
    }
    writable(tenant, task);
    Instant now = clock.instant();
    if (directions.remindedSince(
        tenant, task.periodBucket(), taskId, actor.personId(), REMIND, now.minus(remindWindow))) {
      throw new BusinessException(
          TOO_MANY, "Đã nhắc nhiệm vụ này trong 24 giờ qua, vui lòng chờ người chủ trì phản hồi");
    }
    tasks.appendStateLog(
        tenant,
        task.periodId(),
        task.periodBucket(),
        taskId,
        new StateLogRow(
            now,
            task.status(),
            task.status(),
            REMIND,
            actor.personId(),
            text == null || text.isEmpty() ? null : text,
            task.stateMachineVersion()),
        "{}");
    Map<String, String> p = params(task, facts);
    if (text != null && !text.isEmpty()) {
      p.put("ghiChu", text);
    }
    notify(tenant, task, T_REMIND, recipients(tenant, task, actor), p, actor.personId());
  }

  /** Thao tác chỉ đạo của một người gọi, tính phạm vi một lần cho cả trang danh sách. */
  public interface DirectionActions {
    /** Khoá {@code SCR-1601.*} người gọi được làm trên nhiệm vụ có các thuộc tính này. */
    Set<String> on(UUID assignerId, UUID orgUnitId, TaskStatus status);
  }

  /**
   * Thao tác chỉ đạo ({@code SCR-1601.nhac}, {@code SCR-1601.giao-lai}) theo đúng điều kiện của
   * {@link #remindTask} và {@link #reassignTask}: vai trò lãnh đạo hoặc quản lý, người gọi đã giao
   * việc hoặc nhiệm vụ thuộc đơn vị trong phạm vi, trạng thái còn nhắc hoặc giao lại được. Không
   * xét khoá kỳ và giới hạn nhắc 24 giờ (thao tác vẫn kiểm khi gọi).
   */
  public DirectionActions directionActions(TenantId tenant, TaskActor actor) {
    if (actor.roles().stream().noneMatch(ROLES::contains)) {
      return (assigner, unit, status) -> Set.of();
    }
    java.util.concurrent.atomic.AtomicReference<Set<UUID>> scope =
        new java.util.concurrent.atomic.AtomicReference<>();
    return (assigner, unit, status) -> {
      boolean inScope = actor.personId().equals(assigner);
      if (!inScope && unit != null) {
        inScope =
            scope
                .updateAndGet(
                    s ->
                        s != null
                            ? s
                            : scope(tenant, actor.personId(), LocalDate.now(clock.withZone(ZONE))))
                .contains(unit);
      }
      if (!inScope) {
        return Set.of();
      }
      Set<String> out = new LinkedHashSet<>();
      if (REMINDABLE.contains(status.name())) {
        out.add(TaskPermittedActions.NHAC);
      }
      if (REASSIGNABLE.contains(status.name())) {
        out.add(TaskPermittedActions.GIAO_LAI);
      }
      return out;
    };
  }

  /** Thao tác chỉ đạo trên một nhiệm vụ (trang chi tiết). */
  @Transactional(value = TX, readOnly = true)
  public Set<String> directionActionsOn(TenantId tenant, TaskRow task, TaskActor actor) {
    UUID unit =
        directions.facts(tenant, task.periodBucket(), task.id()).map(Facts::orgUnitId).orElse(null);
    return directionActions(tenant, actor).on(task.assignerId(), unit, task.status());
  }

  /** Kết quả giao lại để controller dựng {@code TaskDetail}. */
  public record Reassigned(TaskRow task, UUID oldLead, UUID newLead) {}

  /**
   * Giao lại (đổi người chủ trì): lý do bắt buộc, người mới còn công tác và thuộc phạm vi, khác
   * người nghiệm thu (BR-05-02), đúng một chủ trì (BR-05-01). Người cũ giữ dòng chủ trì đã kết thúc
   * nên kết quả trước thời điểm giao lại vẫn tách được (PL 6). Phát EVT-04 cho người mới và EVT-21
   * báo người cũ (MSG-16-03).
   */
  @Transactional(TX)
  public Reassigned reassignTask(
      TenantId tenant, UUID taskId, long expected, UUID newLead, String reason, TaskActor actor) {
    TaskRow task = tasks.lock(tenant, taskId).orElseThrow(NoSuchElementException::new);
    Facts facts = facts(tenant, task);
    requireScope(tenant, task, facts, actor);
    if (task.rowVersion() != expected) {
      throw new VersionConflictException("TASK", expected, task.rowVersion());
    }
    if (!REASSIGNABLE.contains(task.status().name())) {
      throw new BusinessException(INVALID_STATE, "Nhiệm vụ đã kết thúc, không giao lại được");
    }
    String why = reason == null ? "" : reason.strip();
    if (why.isEmpty() || why.length() > 500) {
      throw invalid("reason", "Vui lòng nhập lý do giao lại (không quá 500 ký tự)");
    }
    if (newLead == null) {
      throw invalid("leadPersonId", "Vui lòng chọn người chủ trì mới");
    }
    writable(tenant, task);
    Lead old = directions.lead(tenant, task.periodBucket(), taskId).orElse(null);
    if (old != null && newLead.equals(old.personId())) {
      throw rule("leadPersonId", "Người được chọn đang là người chủ trì", "BR-05-01");
    }
    if (newLead.equals(task.acceptorId())) {
      throw rule("leadPersonId", "Người chủ trì không được trùng người nghiệm thu", "BR-05-02");
    }
    LocalDate today = LocalDate.now(clock.withZone(ZONE));
    List<AssignmentView> where = org.assignmentsOf(tenant, newLead, today);
    if (where.isEmpty()) {
      throw invalid(
          "leadPersonId", "Người chủ trì mới không còn công tác tại đơn vị trong hệ thống");
    }
    if (!actor.personId().equals(task.assignerId())) {
      Set<UUID> scope = scope(tenant, actor.personId(), today);
      if (where.stream().noneMatch(a -> scope.contains(a.orgUnitId()))) {
        throw new BusinessException(
            FORBIDDEN, "Người chủ trì mới không thuộc đơn vị trong phạm vi chỉ đạo");
      }
    }
    Instant now = clock.instant();
    directions.replaceLead(
        tenant,
        task.periodId(),
        task.periodBucket(),
        taskId,
        old,
        newLead,
        today,
        actor.personId(),
        now);
    long version =
        directions.bumpVersion(
            tenant, task.periodBucket(), taskId, expected, actor.personId(), now);
    if (version < 0) {
      throw new VersionConflictException("TASK", expected, null);
    }
    tasks.appendStateLog(
        tenant,
        task.periodId(),
        task.periodBucket(),
        taskId,
        new StateLogRow(
            now,
            task.status(),
            task.status(),
            REASSIGN,
            actor.personId(),
            why,
            task.stateMachineVersion()),
        "{\"leadBefore\":"
            + (old == null || old.personId() == null ? "null" : "\"" + old.personId() + "\"")
            + ",\"leadAfter\":\""
            + newLead
            + "\"}");
    TaskRow after = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    assigned.assigned(
        new TaskAssignedEvents.Assigned(
            tenant,
            taskId,
            facts.planItemId(),
            facts.restricted() ? RESTRICTED_TITLE : task.title(),
            task.code(),
            newLead,
            List.of(),
            List.of(newLead),
            task.dueDate(),
            facts.keyTask(),
            facts.restricted(),
            actor.personId(),
            after.rowVersion()));
    UUID oldPerson = old == null ? null : old.personId();
    if (oldPerson != null && !oldPerson.equals(actor.personId())) {
      Map<String, String> p = params(after, facts);
      p.put("lyDo", why);
      notify(tenant, after, T_REASSIGNED, List.of(oldPerson), p, actor.personId());
    }
    return new Reassigned(after, oldPerson, newLead);
  }

  /**
   * EVT-15 (QT-05 ngoại lệ "người chủ trì được điều động trước hạn"): phân công của {@code person}
   * tại {@code unit} kết thúc từ {@code end} mà người đó không còn phân công khác tại đơn vị đó thì
   * mọi nhiệm vụ đang mở người đó chủ trì có hạn tính điểm từ {@code end} trở đi được đánh dấu cần
   * giao lại (dòng {@code task_state_log} {@code CAN_GIAO_LAI}) và mỗi người giao việc nhận một
   * EVT-21 liệt kê các nhiệm vụ của mình. Điều động tương lai cũng báo ngay (báo trước). Trả số
   * nhiệm vụ đã đánh dấu.
   */
  @Transactional(TX)
  public int onAssignmentEnded(
      TenantId tenant, UUID person, UUID assignment, UUID unit, LocalDate end) {
    if (person == null || end == null) {
      return 0;
    }
    boolean stays =
        org.assignmentsOf(tenant, person, end).stream()
            .anyMatch(a -> !a.id().equals(assignment) && a.orgUnitId().equals(unit));
    if (stays) {
      return 0;
    }
    Map<UUID, List<TaskDirections.LedTask>> byAssigner = new LinkedHashMap<>();
    Instant now = clock.instant();
    for (TaskDirections.LedTask t : directions.openTasksLedBy(tenant, person)) {
      if (t.scoreDueDate() != null && t.scoreDueDate().isBefore(end)) {
        continue;
      }
      try {
        periods.assertWritable(tenant, PeriodId.of(t.periodId()));
      } catch (PeriodLockedException e) {
        continue;
      }
      TaskStatus status = TaskStatus.valueOf(t.status());
      tasks.appendStateLog(
          tenant,
          t.periodId(),
          t.periodBucket(),
          t.id(),
          new StateLogRow(
              now,
              status,
              status,
              NEEDS_REASSIGN,
              SYSTEM,
              "Người chủ trì chuyển công tác từ ngày " + end,
              t.stateMachineVersion()),
          "{}");
      byAssigner.computeIfAbsent(t.assignerId(), k -> new java.util.ArrayList<>()).add(t);
    }
    int marked = 0;
    for (Map.Entry<UUID, List<TaskDirections.LedTask>> e : byAssigner.entrySet()) {
      List<TaskDirections.LedTask> list = e.getValue();
      marked += list.size();
      if (e.getKey() == null) {
        continue;
      }
      Map<String, String> p = new LinkedHashMap<>();
      p.put("soNhiemVu", String.valueOf(list.size()));
      p.put(
          "danhSachNhiemVu",
          String.join(", ", list.stream().map(TaskDirections.LedTask::code).toList()));
      p.put("ngayHieuLuc", end.toString());
      TaskDirections.LedTask first = list.get(0);
      notices.notice(
          new TaskReminderNotices.Notice(
              tenant,
              first.id(),
              first.rowVersion(),
              T_LEAD_MOVED,
              List.of(e.getKey()),
              p,
              SYSTEM));
    }
    return marked;
  }

  /** Đơn vị của người gọi và mọi đơn vị cấp dưới trên hai cây. */
  Set<UUID> scope(TenantId tenant, UUID person, LocalDate on) {
    Set<UUID> units = new LinkedHashSet<>();
    for (AssignmentView a : org.assignmentsOf(tenant, person, on)) {
      units.add(a.orgUnitId());
      for (OrgTree tree : OrgTree.values()) {
        units.addAll(org.descendantsOf(tenant, tree, a.orgUnitId(), 0));
      }
    }
    return units;
  }

  private void requireScope(TenantId tenant, TaskRow task, Facts facts, TaskActor actor) {
    if (actor.roles().stream().noneMatch(ROLES::contains)) {
      throw new BusinessException(FORBIDDEN, "Chỉ lãnh đạo, quản lý mới chỉ đạo được nhiệm vụ");
    }
    if (actor.personId().equals(task.assignerId())) {
      return;
    }
    Set<UUID> scope = scope(tenant, actor.personId(), LocalDate.now(clock.withZone(ZONE)));
    if (facts.orgUnitId() == null || !scope.contains(facts.orgUnitId())) {
      throw new BusinessException(FORBIDDEN, "Nhiệm vụ không thuộc đơn vị trong phạm vi chỉ đạo");
    }
  }

  private Facts facts(TenantId tenant, TaskRow task) {
    return directions
        .facts(tenant, task.periodBucket(), task.id())
        .orElseThrow(NoSuchElementException::new);
  }

  private List<UUID> recipients(TenantId tenant, TaskRow task, TaskActor actor) {
    Lead lead = directions.lead(tenant, task.periodBucket(), task.id()).orElse(null);
    if (lead == null) {
      return List.of();
    }
    if (lead.personId() != null) {
      return List.of(lead.personId());
    }
    return org.headsOf(tenant, lead.orgUnitId(), LocalDate.now(clock.withZone(ZONE)));
  }

  private void notify(
      TenantId tenant,
      TaskRow task,
      String template,
      List<UUID> to,
      Map<String, String> params,
      UUID actor) {
    List<UUID> recipients =
        to.stream().filter(Objects::nonNull).filter(id -> !id.equals(actor)).distinct().toList();
    if (recipients.isEmpty()) {
      return;
    }
    notices.notice(
        new TaskReminderNotices.Notice(
            tenant, task.id(), task.rowVersion(), template, recipients, params, actor));
  }

  private static Map<String, String> params(TaskRow task, Facts facts) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("maNhiemVu", task.code());
    p.put("tenNhiemVu", facts.restricted() ? RESTRICTED_TITLE : task.title());
    LocalDate due = task.extendedDueDate() != null ? task.extendedDueDate() : task.dueDate();
    if (due != null) {
      p.put("hanHoanThanh", due.toString());
    }
    return p;
  }

  private void writable(TenantId tenant, TaskRow task) {
    try {
      periods.assertWritable(tenant, PeriodId.of(task.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(PERIOD_LOCKED, "Kỳ đã khoá, không chỉ đạo được nhiệm vụ");
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static BusinessException rule(String field, String message, String rule) {
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, rule)));
  }
}
