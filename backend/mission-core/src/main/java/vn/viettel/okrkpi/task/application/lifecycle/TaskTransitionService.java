package vn.viettel.okrkpi.task.application.lifecycle;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskStatusChangedEvent;
import vn.viettel.okrkpi.task.api.events.TrangThaiNhiemVuDoi;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.ChangeRequestRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.StateLogRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.StatusUpdate;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskStatusEvents;
import vn.viettel.okrkpi.task.application.lifecycle.port.TransitionTableProvider;
import vn.viettel.okrkpi.task.domain.lifecycle.GuardEvaluator;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStateMachine;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionResult;
import vn.viettel.okrkpi.task.domain.lifecycle.TransitionTable;

/**
 * Chuyển trạng thái nhiệm vụ trong một giao dịch {@code taskTransactionManager} (CORE-02-13): khoá
 * dòng, so {@code row_version} với {@code If-Match}, hỏi {@code PeriodApi} kỳ còn ghi được, chạy
 * {@link TaskStateMachine}, ghi {@code task.task}, {@code task_state_log} và EVT-06 vào outbox, rồi
 * phát sự kiện trong tiến trình. Mã từ chối được ánh xạ sang RFC 9457.
 */
public class TaskTransitionService {

  static final String TX = "taskTransactionManager";

  /** Mã lỗi khi lệnh không có trong bảng hoặc trạng thái đã kết thúc ({@code tasks.yaml}). */
  public static final String INVALID_TRANSITION = "TASK-409-INVALID-TRANSITION";

  public static final String PERIOD_LOCKED = "TASK-409-PERIOD-LOCKED";

  /** Danh mục lỗi chưa có mã riêng cho vai trò và guard; dùng mã chung của hợp đồng. */
  public static final String ROLE_NOT_ALLOWED = "COMMON-403-OUT-OF-SCOPE";

  public static final String GUARD_FAILED = "COMMON-422-VALIDATION";

  private final TaskLifecycleRepository tasks;
  private final TransitionTableProvider tables;
  private final GuardEvaluator guards;
  private final PeriodApi periods;
  private final TaskStatusEvents events;
  private final TaskDeliverables deliverables;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;

  public TaskTransitionService(
      TaskLifecycleRepository tasks,
      TransitionTableProvider tables,
      GuardEvaluator guards,
      PeriodApi periods,
      TaskStatusEvents events,
      TaskDeliverables deliverables,
      ApplicationEventPublisher publisher,
      Clock clock) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.tables = Objects.requireNonNull(tables, "tables");
    this.guards = Objects.requireNonNull(guards, "guards");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.events = Objects.requireNonNull(events, "events");
    this.deliverables = deliverables == null ? (t, id) -> List.of() : deliverables;
    this.publisher = publisher == null ? e -> {} : publisher;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Kết quả chuyển: dòng nhiệm vụ sau khi ghi. */
  public record Transitioned(TaskRow task, TaskStatusChangedEvent event) {}

  /**
   * Chạy lệnh trên nhiệm vụ.
   *
   * @param expectedVersion {@code row_version} người gọi đã đọc; {@code null} bỏ qua so phiên bản
   *     (lệnh nội bộ của task khác gọi trong giao dịch của mình)
   * @throws NoSuchElementException khi không có nhiệm vụ trong tenant
   */
  @Transactional(TX)
  public Transitioned transition(
      TenantId tenant, UUID taskId, TaskCommand command, TaskActor actor, Long expectedVersion) {
    return transition(tenant, taskId, command, actor, expectedVersion, null, null);
  }

  /**
   * Như {@link #transition(TenantId, UUID, TaskCommand, TaskActor, Long)} nhưng biến {@code
   * deliverables} của guard lấy từ {@code deliverablesSnapshot} do MOD-03 đẩy vào qua {@code
   * TaskApi} (CORE-02-12) thay cho cổng {@link TaskDeliverables}; {@code null} dùng cổng. {@code
   * completedAt} khác {@code null} thay thời điểm hoàn thành khi nhiệm vụ sang {@code HOAN_THANH}.
   */
  @Transactional(TX)
  public Transitioned transition(
      TenantId tenant,
      UUID taskId,
      TaskCommand command,
      TaskActor actor,
      Long expectedVersion,
      List<Map<String, Object>> deliverablesSnapshot,
      Instant completedAt) {
    TaskRow task = tasks.lock(tenant, taskId).orElseThrow(NoSuchElementException::new);
    if (expectedVersion != null && expectedVersion != task.rowVersion()) {
      throw new VersionConflictException("TASK", expectedVersion, task.rowVersion());
    }
    if (task.status().isTerminal()) {
      throw new BusinessException(
          INVALID_TRANSITION, "Nhiệm vụ đã kết thúc, không thể chuyển trạng thái");
    }
    TransitionTable table = tables.tableFor(tenant, task.periodId());
    boolean writable = writable(tenant, task.periodId());
    TransitionResult result =
        new TaskStateMachine(table, guards)
            .apply(
                task.status(),
                command,
                actor,
                writable,
                () -> activation(tenant, task, actor, deliverablesSnapshot));
    TransitionResult.Accepted accepted =
        switch (result) {
          case TransitionResult.Accepted a -> a;
          case TransitionResult.Rejected r -> throw rejection(r);
        };

    Instant now = clock.instant();
    long version =
        tasks.changeStatus(
            tenant,
            task.periodBucket(),
            taskId,
            task.rowVersion(),
            new StatusUpdate(
                accepted.to(),
                accepted.policyVersion(),
                actor.personId(),
                now,
                accepted.to() == TaskStatus.HOAN_THANH
                    ? (completedAt != null ? completedAt : now)
                    : null,
                accepted.to() == TaskStatus.HUY ? accepted.reason() : null));
    tasks.appendStateLog(
        tenant,
        task.periodId(),
        task.periodBucket(),
        taskId,
        new StateLogRow(
            now,
            accepted.from(),
            accepted.to(),
            accepted.command(),
            actor.personId(),
            accepted.reason(),
            accepted.policyVersion()),
        beforeAfter(task, accepted, version));
    TaskStatusChangedEvent event =
        new TaskStatusChangedEvent(
            tenant,
            taskId,
            task.periodId(),
            accepted.from().name(),
            accepted.to().name(),
            accepted.command(),
            task.overdue(),
            accepted.reason(),
            actor.personId(),
            version,
            accepted.policyVersion(),
            now);
    events.statusChanged(event);
    publisher.publishEvent(event);
    publisher.publishEvent(
        new TrangThaiNhiemVuDoi(
            tenant,
            task.periodId(),
            taskId,
            accepted.from().name(),
            accepted.to().name(),
            tasks.activeAssignees(tenant, task.periodBucket(), taskId),
            accepted.to().isTerminal(),
            now));
    TaskRow after = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    return new Transitioned(after, event);
  }

  /**
   * Ghi một dòng nhật ký giữ nguyên trạng thái (ví dụ lý do vẫn giao khi có nhiệm vụ gần giống,
   * BR-05-06), trong giao dịch của người gọi.
   */
  @Transactional(TX)
  public void appendNote(
      TenantId tenant,
      TaskRow task,
      String transition,
      UUID actor,
      String reason,
      String beforeAfterJson) {
    tasks.appendStateLog(
        tenant,
        task.periodId(),
        task.periodBucket(),
        task.id(),
        new StateLogRow(
            clock.instant(),
            task.status(),
            task.status(),
            transition,
            actor,
            reason,
            task.stateMachineVersion()),
        beforeAfterJson);
  }

  /**
   * Huỷ nhiệm vụ còn {@code NHAP} ngoài bảng chuyển (bảng chuẩn không có lệnh từ {@code NHAP} sang
   * {@code HUY}): dùng khi người quản lý từ chối nhiệm vụ do cán bộ đăng ký (CORE-02-17). Ghi nhật
   * ký chuyển {@code transition}, phát EVT-06 như một lần chuyển trạng thái.
   */
  @Transactional(TX)
  public Transitioned cancelDraft(
      TenantId tenant,
      UUID taskId,
      long expectedVersion,
      String transition,
      TaskActor actor,
      String reason) {
    TaskRow task = tasks.lock(tenant, taskId).orElseThrow(NoSuchElementException::new);
    if (expectedVersion != task.rowVersion()) {
      throw new VersionConflictException("TASK", expectedVersion, task.rowVersion());
    }
    if (task.status() != TaskStatus.NHAP) {
      throw new BusinessException(INVALID_TRANSITION, "Nhiệm vụ không còn ở trạng thái nháp");
    }
    Instant now = clock.instant();
    String policyVersion = task.stateMachineVersion();
    long version =
        tasks.changeStatus(
            tenant,
            task.periodBucket(),
            taskId,
            task.rowVersion(),
            new StatusUpdate(TaskStatus.HUY, policyVersion, actor.personId(), now, null, reason));
    tasks.appendStateLog(
        tenant,
        task.periodId(),
        task.periodBucket(),
        taskId,
        new StateLogRow(
            now,
            TaskStatus.NHAP,
            TaskStatus.HUY,
            transition,
            actor.personId(),
            reason,
            policyVersion),
        "{\"status\":{\"before\":\"NHAP\",\"after\":\"HUY\"},\"row_version\":" + version + "}");
    TaskStatusChangedEvent event =
        new TaskStatusChangedEvent(
            tenant,
            taskId,
            task.periodId(),
            TaskStatus.NHAP.name(),
            TaskStatus.HUY.name(),
            transition,
            task.overdue(),
            reason,
            actor.personId(),
            version,
            policyVersion,
            now);
    events.statusChanged(event);
    publisher.publishEvent(event);
    publisher.publishEvent(
        new TrangThaiNhiemVuDoi(
            tenant,
            task.periodId(),
            taskId,
            TaskStatus.NHAP.name(),
            TaskStatus.HUY.name(),
            tasks.activeAssignees(tenant, task.periodBucket(), taskId),
            true,
            now));
    return new Transitioned(
        tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new), event);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public java.util.Optional<TaskRow> find(TenantId tenant, UUID taskId) {
    return tasks.find(tenant, taskId);
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public List<StateLogRow> stateLog(TenantId tenant, TaskRow task) {
    return tasks.stateLog(tenant, task.periodBucket(), task.id());
  }

  private boolean writable(TenantId tenant, UUID periodId) {
    try {
      periods.assertWritable(tenant, PeriodId.of(periodId));
      return true;
    } catch (PeriodLockedException e) {
      return false;
    }
  }

  /**
   * Ngữ cảnh guard theo khối {@code ngu_canh} của {@code task_lifecycle}; khoá luôn có mặt (chuỗi
   * rỗng, 0) để biểu thức không lỗi vì thiếu khoá. {@code so_san_pham_da_nop} lấy từ khối lượng đã
   * làm khi MOD-03 chưa cung cấp sản phẩm (suy luận).
   */
  Map<String, Object> activation(TenantId tenant, TaskRow task, TaskActor actor) {
    return activation(tenant, task, actor, null);
  }

  Map<String, Object> activation(
      TenantId tenant,
      TaskRow task,
      TaskActor actor,
      List<Map<String, Object>> deliverablesSnapshot) {
    Map<String, Object> a = new HashMap<>();
    a.put("person_id", actor.personId().toString());
    a.put("roles", List.copyOf(actor.roles()));

    List<Map<String, Object>> items =
        deliverablesSnapshot != null ? deliverablesSnapshot : deliverables.of(tenant, task.id());
    Map<String, Object> t = new HashMap<>();
    t.put(
        "chu_tri_id",
        tasks.leadAssignee(tenant, task.periodBucket(), task.id()).map(UUID::toString).orElse(""));
    t.put("nguoi_giao_id", task.assignerId() == null ? "" : task.assignerId().toString());
    t.put("nguoi_nghiem_thu_id", task.acceptorId() == null ? "" : task.acceptorId().toString());
    t.put("ke_hoach", whole(task.plannedQuantity()));
    long submitted =
        items.isEmpty()
            ? whole(task.doneQuantity())
            : items.stream().filter(d -> !"NHAP".equals(String.valueOf(d.get("status")))).count();
    t.put("so_san_pham_da_nop", submitted);

    ChangeRequestRow cr =
        tasks
            .latestChangeRequest(tenant, task.periodBucket(), task.id())
            .orElse(new ChangeRequestRow("", ""));
    Map<String, Object> change = new HashMap<>();
    change.put("request_type", cr.requestType() == null ? "" : cr.requestType());
    change.put("status", cr.status() == null ? "" : cr.status());

    Map<String, Object> activation = new HashMap<>();
    activation.put("actor", a);
    activation.put("task", t);
    activation.put("deliverables", items);
    activation.put("change_request", change);
    return activation;
  }

  private static long whole(BigDecimal v) {
    return v == null ? 0 : v.longValue();
  }

  private static String beforeAfter(TaskRow task, TransitionResult.Accepted a, long version) {
    return "{\"before\":{\"status\":\""
        + a.from().name()
        + "\",\"row_version\":"
        + task.rowVersion()
        + "},\"after\":{\"status\":\""
        + a.to().name()
        + "\",\"row_version\":"
        + version
        + "}}";
  }

  private static BusinessException rejection(TransitionResult.Rejected r) {
    return switch (r.code()) {
      case PERIOD_LOCKED -> new BusinessException(PERIOD_LOCKED, r.message());
      case TRANSITION_NOT_ALLOWED -> new BusinessException(INVALID_TRANSITION, r.message());
      case ROLE_NOT_ALLOWED -> new BusinessException(ROLE_NOT_ALLOWED, r.message());
      case REASON_REQUIRED ->
          new BusinessException(
              GUARD_FAILED,
              r.message(),
              List.of(new ProblemFieldError("reason", r.message(), null)));
      // Không lộ biểu thức CEL ra giao diện (AGENTS.md quy tắc 9).
      case GUARD_FAILED ->
          new BusinessException(
              GUARD_FAILED, "Nhiệm vụ chưa đáp ứng điều kiện để chuyển trạng thái");
    };
  }
}
