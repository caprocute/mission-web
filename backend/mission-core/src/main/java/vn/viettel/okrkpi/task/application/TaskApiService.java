package vn.viettel.okrkpi.task.application;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskSummary;
import vn.viettel.okrkpi.task.application.assign.TaskAssignService;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.AssigneeKind;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskScoringReads;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;

/**
 * Hiện thực {@link TaskApi} (CORE-02-12): đọc qua {@link TaskLifecycleRepository} trên {@code
 * taskTransactionManager}; lệnh chuyển trạng thái do MOD-03 yêu cầu đi qua {@link
 * TaskTransitionService} nên vẫn chạy đủ bảng chuyển của Policy Pack, guard CEL, {@code
 * task_state_log} và EVT-06.
 */
public class TaskApiService implements TaskApi {

  private static final String TX = "taskTransactionManager";

  /** Lựa chọn "vẫn tạo mới" khi có việc gần giống, ghi kèm việc do mô-đun khác lập (BR-05-06). */
  static final String DIRECTED_REASON =
      "Việc phát sinh theo quy định, lập từ đề xuất của mô-đun khác";

  static final String ASSIGNER_ROLE = "manager";

  private final TaskLifecycleRepository tasks;
  private final TaskTransitionService transitions;
  private TaskScoringReads scoring;
  private final Supplier<TaskDraftService> drafts;
  private final Supplier<TaskAssignService> assigns;

  public TaskApiService(TaskLifecycleRepository tasks, TaskTransitionService transitions) {
    this(tasks, transitions, () -> null, () -> null);
  }

  /** Như trên, kèm soạn và giao để lập việc từ mô-đun khác (CORE-04-11); nạp lười. */
  public TaskApiService(
      TaskLifecycleRepository tasks,
      TaskTransitionService transitions,
      Supplier<TaskDraftService> drafts,
      Supplier<TaskAssignService> assigns) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.drafts = Objects.requireNonNull(drafts, "drafts");
    this.assigns = Objects.requireNonNull(assigns, "assigns");
  }

  /**
   * Soạn nhiệm vụ đột xuất rồi giao trong cùng giao dịch {@code taskTransactionManager}: quy tắc
   * giao chặn thì không còn bản nháp sót lại. Nhóm thường xuyên, ưu tiên cao, một sản phẩm văn bản
   * (suy luận).
   */
  @Override
  @Transactional(TX)
  public UUID openDirectedTask(TenantId tenant, DirectedTask task) {
    TaskDraftService draftService = drafts.get();
    TaskAssignService assignService = assigns.get();
    if (draftService == null || assignService == null) {
      return TaskApi.super.openDirectedTask(tenant, task);
    }
    // Lệnh ASSIGN của bảng chuyển chỉ cho vai trò người giao việc; mô-đun nguồn đã kiểm quyền người
    // gọi trên chính đề xuất (OPA), nên việc theo quy định được giao dưới vai trò này (suy luận).
    Set<String> roles = new HashSet<>(task.actorRoles());
    roles.add(ASSIGNER_ROLE);
    TaskActor actor = new TaskActor(task.actorId(), roles);
    TaskDraftSpec spec =
        new TaskDraftSpec(
            task.periodId(),
            "DOT_XUAT",
            null,
            null,
            task.title(),
            "THUONG_XUYEN",
            null,
            "CAO",
            task.leadPersonId(),
            List.of(),
            task.dueDate(),
            task.acceptorId(),
            List.of(),
            task.productName(),
            BigDecimal.ONE,
            "Văn bản",
            task.evidenceRequired(),
            task.qualityCriteria(),
            null,
            null,
            task.basis());
    DraftRow draft = draftService.createDraft(tenant, spec, actor);
    assignService.assignTask(tenant, draft.id(), draft.rowVersion(), DIRECTED_REASON, null, actor);
    return draft.id();
  }

  /** Nối đọc đầu vào tính điểm (CORE-04-13); vắng thì các hàm đọc đó trả rỗng như mặc định. */
  public void setScoringReads(TaskScoringReads scoring) {
    this.scoring = scoring;
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public List<WorkItemSnapshot> listWorkItems(
      TenantId tenant, PeriodId period, UUID personId, UUID afterId, int limit) {
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(personId, "personId");
    if (scoring == null) {
      return List.of();
    }
    return scoring.workItems(
        tenant, period.value(), personId, afterId, Math.max(1, Math.min(limit, MAX_LIMIT + 1)));
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public Map<UUID, List<WorkItemSnapshot>> listWorkItems(
      TenantId tenant, PeriodId period, Collection<UUID> personIds, int limitPerPerson) {
    Objects.requireNonNull(period, "period");
    if (scoring == null || personIds.isEmpty()) {
      return Map.of();
    }
    return scoring.workItems(
        tenant, period.value(), personIds, Math.max(1, Math.min(limitPerPerson, MAX_LIMIT + 1)));
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public List<ResponsibleItem> listResponsibleItems(
      TenantId tenant, PeriodId period, UUID personId) {
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(personId, "personId");
    return scoring == null ? List.of() : scoring.responsibleItems(tenant, period.value(), personId);
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<TaskSummary> find(TenantId tenant, UUID taskId) {
    return tasks
        .find(tenant, taskId)
        .map(t -> summary(t, tasks.leadAssignee(tenant, t.periodBucket(), t.id()).orElse(null)));
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public List<TaskSummary> listForPerson(
      TenantId tenant, PeriodId period, UUID personId, UUID afterId, int limit) {
    return list(tenant, period, AssigneeKind.PERSON, personId, afterId, limit);
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public List<TaskSummary> listForUnit(
      TenantId tenant, PeriodId period, UUID orgUnitId, UUID afterId, int limit) {
    return list(tenant, period, AssigneeKind.UNIT, orgUnitId, afterId, limit);
  }

  @Override
  @Transactional(TX)
  public TransitionOutcome requestTransition(TenantId tenant, TransitionRequest request) {
    Objects.requireNonNull(request, "request");
    TaskRow current = tasks.lock(tenant, request.taskId()).orElseThrow(NoSuchElementException::new);
    Command command = request.command();
    String status = current.status().name();
    if (command.to().equals(status)) {
      return new TransitionOutcome(Outcome.ALREADY_IN_TARGET, summary(tenant, current));
    }
    if (!command.from().equals(status)) {
      return new TransitionOutcome(Outcome.NOT_APPLICABLE, summary(tenant, current));
    }
    List<Map<String, Object>> snapshot =
        request.deliverables().stream()
            .map(
                d -> {
                  Map<String, Object> m = new HashMap<>();
                  m.put(
                      "deliverable_id",
                      d.deliverableId() == null ? "" : d.deliverableId().toString());
                  m.put("status", d.status() == null ? "" : d.status());
                  return m;
                })
            .toList();
    TaskRow after =
        transitions
            .transition(
                tenant,
                request.taskId(),
                command(command, request.reason()),
                new TaskActor(request.actorId(), request.actorRoles()),
                null,
                snapshot,
                request.completedAt())
            .task();
    return new TransitionOutcome(Outcome.APPLIED, summary(tenant, after));
  }

  private List<TaskSummary> list(
      TenantId tenant, PeriodId period, AssigneeKind kind, UUID id, UUID afterId, int limit) {
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(id, "id");
    int size = Math.max(1, Math.min(limit, MAX_LIMIT));
    return tasks.listByAssignee(tenant, period.value(), kind, id, afterId, size).stream()
        .map(r -> summary(r.task(), r.leadAssigneeId()))
        .toList();
  }

  private TaskSummary summary(TenantId tenant, TaskRow t) {
    return summary(t, tasks.leadAssignee(tenant, t.periodBucket(), t.id()).orElse(null));
  }

  private static TaskCommand command(Command command, String reason) {
    return switch (command) {
      case SUBMIT -> new TaskCommand.Submit(null);
      case APPROVE -> new TaskCommand.ApproveAcceptance(null);
      case REWORK -> new TaskCommand.RequestRework(reason);
    };
  }

  static TaskSummary summary(TaskRow t, UUID lead) {
    return new TaskSummary(
        t.id(),
        t.periodId(),
        t.periodBucket(),
        t.code(),
        t.title(),
        t.status().name(),
        t.status().isTerminal(),
        t.overdue(),
        t.assignerId(),
        t.acceptorId(),
        lead,
        t.extendedDueDate() != null ? t.extendedDueDate() : t.dueDate(),
        t.plannedQuantity(),
        t.doneQuantity(),
        t.rowVersion());
  }
}
