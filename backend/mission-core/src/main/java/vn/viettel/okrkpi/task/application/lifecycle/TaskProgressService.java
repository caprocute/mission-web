package vn.viettel.okrkpi.task.application.lifecycle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskProgressEvents;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskProgressRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskProgressRepository.NewProgress;
import vn.viettel.okrkpi.task.domain.lifecycle.ProgressRules;
import vn.viettel.okrkpi.task.domain.lifecycle.ProgressRules.Role;
import vn.viettel.okrkpi.task.domain.lifecycle.ProgressRules.Violation;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;

/**
 * Cập nhật tiến độ theo mẫu {@code UpdateProgressUseCase} của TL-01 (QT-06, CORE-02-19): khoá dòng
 * và so {@code row_version}, hỏi kỳ còn ghi được, kiểm BR-06-02, BR-06-03, thêm một dòng {@code
 * task_progress}, cập nhật {@code task.progress_pct} và ghi EVT-05 vào outbox trong một giao dịch.
 * Lần cập nhật đầu của chủ trì khi nhiệm vụ đang {@code DA_GIAO} chạy lệnh ACCEPT của máy trạng
 * thái trước (EVT-06).
 */
public class TaskProgressService {

  static final String TX = "taskTransactionManager";
  static final String VERSION_CONFLICT = "TASK-409-VERSION-CONFLICT";
  static final String INVALID_STATE = "TASK-409-INVALID-STATE";
  static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";
  static final String VALIDATION = "COMMON-422-VALIDATION";

  private final TaskLifecycleRepository tasks;
  private final TaskProgressRepository progress;
  private final TaskProgressEvents events;
  private final TaskTransitionService transitions;
  private final PeriodApi periods;
  private final TransactionTemplate perItem;
  private final Clock clock;

  public TaskProgressService(
      TaskLifecycleRepository tasks,
      TaskProgressRepository progress,
      TaskProgressEvents events,
      TaskTransitionService transitions,
      PeriodApi periods,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.progress = Objects.requireNonNull(progress, "progress");
    this.events = Objects.requireNonNull(events, "events");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.perItem = new TransactionTemplate(transactionManager);
    this.perItem.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.perItem.setTimeout(5);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Nội dung một lần cập nhật; {@code channel} rỗng là {@code WEB}. */
  public record ProgressInput(
      Integer percent, String note, BigDecimal doneQuantity, String channel) {}

  /** Một dòng của cập nhật hàng loạt. */
  public record BatchItem(UUID taskId, Long rowVersion, Integer percent, String note) {}

  /** Kết quả một dòng: {@code task} khi thành công, {@code error} khi lỗi. */
  public record BatchResult(UUID taskId, int status, TaskRow task, BusinessException error) {}

  /**
   * Cập nhật tiến độ một nhiệm vụ; {@code idempotencyKey} được ghi vào dòng tiến độ, gửi lại cùng
   * khoá thì trả nhiệm vụ hiện tại mà không thêm dòng.
   *
   * @throws NoSuchElementException khi không có nhiệm vụ trong tenant
   */
  @Transactional(value = TX, timeout = 5)
  public TaskRow updateProgress(
      TenantId tenant,
      UUID taskId,
      long expectedVersion,
      ProgressInput input,
      TaskActor actor,
      String idempotencyKey) {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (input == null || input.percent() == null) {
      throw invalid("percent");
    }
    String channel = input.channel() == null ? "WEB" : input.channel();
    if (!List.of("WEB", "MOBILE", "TICH_HOP").contains(channel)) {
      throw invalid("channel");
    }
    if (input.doneQuantity() != null && input.doneQuantity().signum() < 0) {
      throw invalid("doneQuantity");
    }
    TaskRow task = tasks.lock(tenant, taskId).orElseThrow(NoSuchElementException::new);
    if (progress.exists(tenant, task.periodBucket(), idempotencyKey)) {
      return task;
    }
    if (task.rowVersion() != expectedVersion) {
      throw new VersionConflictException("TASK", expectedVersion, task.rowVersion());
    }
    try {
      periods.assertWritable(tenant, PeriodId.of(task.periodId()));
    } catch (PeriodLockedException e) {
      throw new BusinessException(
          TaskTransitionService.PERIOD_LOCKED, "Kỳ đã khoá, không cập nhật được tiến độ");
    }
    Role role = role(tenant, task, actor.personId());
    int current = percent(task.progressPct());
    Optional<Violation> violation =
        ProgressRules.check(task.status(), role, current, input.percent(), input.note());
    if (violation.isEmpty() && role == Role.PHOI_HOP && input.doneQuantity() != null) {
      violation = Optional.of(Violation.COORDINATOR_PERCENT);
    }
    if (violation.isPresent()) {
      throw rejection(violation.get());
    }
    long version = task.rowVersion();
    if (ProgressRules.startsWork(task.status(), role)) {
      Set<String> roles = new HashSet<>();
      roles.addAll(actor.roles());
      roles.add("member");
      version =
          transitions
              .transition(
                  tenant,
                  taskId,
                  new TaskCommand.Accept(),
                  new TaskActor(actor.personId(), roles),
                  version)
              .task()
              .rowVersion();
    }
    Instant now = clock.instant();
    UUID progressId = UUID.randomUUID();
    BigDecimal ratio =
        role == Role.CHU_TRI ? ratio(input.percent()) : nullToZero(task.progressPct());
    progress.insert(
        tenant,
        new NewProgress(
            progressId,
            task.periodId(),
            task.periodBucket(),
            taskId,
            now,
            ratio,
            input.doneQuantity(),
            input.note(),
            channel,
            idempotencyKey,
            actor.personId()));
    if (role == Role.CHU_TRI) {
      version =
          progress.applyProgress(
              tenant,
              task.periodBucket(),
              taskId,
              version,
              ratio,
              input.doneQuantity(),
              actor.personId(),
              now);
      if (version < 0) {
        throw new VersionConflictException("TASK", expectedVersion, null);
      }
      events.progressed(
          new TaskProgressEvents.Progressed(
              tenant,
              taskId,
              task.periodId(),
              progressId,
              actor.personId(),
              BigDecimal.valueOf(input.percent()).setScale(3),
              input.doneQuantity(),
              now,
              version));
    }
    return tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
  }

  /**
   * Cập nhật hàng loạt (SCR-0503): mỗi dòng một giao dịch riêng với {@code rowVersion} của dòng,
   * lỗi của một dòng không ảnh hưởng dòng khác. Khoá idempotency của dòng là {@code <khoá>:<thứ
   * tự>}.
   */
  public List<BatchResult> applyProgressBatch(
      TenantId tenant, List<BatchItem> items, TaskActor actor, String idempotencyKey) {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (items == null || items.isEmpty() || items.size() > 200) {
      throw invalid("items");
    }
    List<BatchResult> results = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) {
      BatchItem item = items.get(i);
      String key = idempotencyKey + ":" + i;
      try {
        if (item == null || item.taskId() == null || item.rowVersion() == null) {
          throw invalid("items[" + i + "]");
        }
        TaskRow row =
            perItem.execute(
                s ->
                    updateProgress(
                        tenant,
                        item.taskId(),
                        item.rowVersion(),
                        new ProgressInput(item.percent(), item.note(), null, "WEB"),
                        actor,
                        key));
        results.add(new BatchResult(item.taskId(), 200, row, null));
      } catch (BusinessException e) {
        results.add(
            new BatchResult(item == null ? null : item.taskId(), e.errorCode().status(), null, e));
      } catch (VersionConflictException e) {
        results.add(
            new BatchResult(
                item.taskId(), 409, null, new BusinessException(VERSION_CONFLICT, e.detail())));
      } catch (NoSuchElementException e) {
        results.add(
            new BatchResult(
                item.taskId(),
                403,
                null,
                new BusinessException(OUT_OF_SCOPE, "Không có quyền với nhiệm vụ này")));
      }
    }
    return results;
  }

  private Role role(TenantId tenant, TaskRow task, UUID person) {
    UUID lead = tasks.leadAssignee(tenant, task.periodBucket(), task.id()).orElse(null);
    if (person.equals(lead)) {
      return Role.CHU_TRI;
    }
    return tasks.activeAssignees(tenant, task.periodBucket(), task.id()).contains(person)
        ? Role.PHOI_HOP
        : Role.KHAC;
  }

  private static BusinessException rejection(Violation v) {
    return switch (v) {
      case NOT_ASSIGNEE ->
          new BusinessException(OUT_OF_SCOPE, "Chỉ người được giao cập nhật tiến độ");
      case COORDINATOR_PERCENT ->
          new BusinessException(
              OUT_OF_SCOPE, "Người phối hợp chỉ ghi nhật ký, không thay đổi phần trăm tiến độ");
      case INVALID_STATE ->
          new BusinessException(
              INVALID_STATE, "Nhiệm vụ không ở trạng thái cho phép cập nhật tiến độ");
      case DECREASE_WITHOUT_NOTE -> {
        String m = "Tiến độ mới thấp hơn lần trước. Vui lòng ghi chú lý do.";
        yield new BusinessException(
            VALIDATION, m, List.of(new ProblemFieldError("note", m, null)), "MSG-06-01");
      }
      case INVALID_PERCENT -> invalid("percent");
    };
  }

  static int percent(BigDecimal ratio) {
    return ratio == null ? 0 : ratio.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue();
  }

  private static BigDecimal ratio(int percent) {
    return BigDecimal.valueOf(percent).movePointLeft(2).setScale(4);
  }

  private static BigDecimal nullToZero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO.setScale(4) : v;
  }

  private static BusinessException invalid(String field) {
    String message = "Dữ liệu cập nhật tiến độ không hợp lệ";
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, null)));
  }
}
