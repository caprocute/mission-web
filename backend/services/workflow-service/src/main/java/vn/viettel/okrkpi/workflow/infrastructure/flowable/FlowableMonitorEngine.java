package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.TimerEventDefinition;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.impl.db.HasRevision;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.workflow.application.port.MonitorEngine;
import vn.viettel.okrkpi.workflow.application.port.TaskReassignment;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.monitor.MonitoredTask;
import vn.viettel.okrkpi.workflow.domain.monitor.WorkflowDiagram;

/**
 * {@link MonitorEngine} trên API công khai của Flowable 7 (BS-01 SCR-1506). Chuyển người xử lý dùng
 * lại vết uỷ quyền của {@link FlowableTaskReassignment} (TL-01 G2); gia hạn đặt {@code DUE_DATE_}
 * mới, dời các hẹn giờ biên của bước đến hạn từ hạn cũ trở đi (hẹn giờ hạn, leo thang) cùng khoảng,
 * giữ nguyên hẹn giờ nhắc trước hạn, và ghi bình luận loại {@code GIA_HAN}. Không động tới biến
 * tiến trình, không chuyển bước. Hai thao tác chạy trong giao dịch của yêu cầu nên sự kiện {@code
 * TASK_ASSIGNED} cập nhật bảng chiếu cùng giao dịch.
 */
@Component
public class FlowableMonitorEngine implements MonitorEngine {

  /** Loại bình luận vết gia hạn trong {@code ACT_HI_COMMENT}. */
  public static final String EXTEND_COMMENT_TYPE = "GIA_HAN";

  /** Loại phần tử lịch sử không phải bước (luồng nối) không đưa vào sơ đồ. */
  static final String SEQUENCE_FLOW = "sequenceFlow";

  private final TaskService tasks;
  private final RuntimeService runtime;
  private final HistoryService history;
  private final RepositoryService repository;
  private final ManagementService management;
  private final TaskReassignment reassignment;

  public FlowableMonitorEngine(
      TaskService tasks,
      RuntimeService runtime,
      HistoryService history,
      RepositoryService repository,
      ManagementService management,
      TaskReassignment reassignment) {
    this.tasks = tasks;
    this.runtime = runtime;
    this.history = history;
    this.repository = repository;
    this.management = management;
    this.reassignment = reassignment;
  }

  @Override
  public Optional<MonitoredTask> findTask(String taskId) {
    Task task = tasks.createTaskQuery().taskId(taskId).singleResult();
    if (task == null || task.getProcessInstanceId() == null) {
      return Optional.empty();
    }
    Optional<UUID> tenant = uuid(task.getTenantId());
    if (tenant.isEmpty()) {
      return Optional.empty();
    }
    ProcessInstance instance =
        runtime
            .createProcessInstanceQuery()
            .processInstanceId(task.getProcessInstanceId())
            .singleResult();
    if (instance == null) {
      return Optional.empty();
    }
    Optional<BusinessKey> key = BusinessKey.parse(instance.getBusinessKey());
    if (key.isEmpty()) {
      return Optional.empty();
    }
    List<CandidateGroup> candidates =
        tasks.getIdentityLinksForTask(taskId).stream()
            .filter(l -> IdentityLinkType.CANDIDATE.equals(l.getType()))
            .map(IdentityLink::getGroupId)
            .map(CandidateGroup::parse)
            .flatMap(Optional::stream)
            .toList();
    return Optional.of(
        new MonitoredTask(
            task.getId(),
            tenant.get(),
            task.getProcessInstanceId(),
            task.getTaskDefinitionKey(),
            uuid(task.getAssignee()).orElse(null),
            candidates,
            revision(task),
            task.getDueDate() == null ? null : task.getDueDate().toInstant(),
            key.get(),
            uuid(instance.getStartUserId()).orElse(null),
            instance.getProcessDefinitionKey()));
  }

  @Override
  public int reassign(MonitoredTask task, UUID newAssigneeId, UUID actorId, String reason) {
    try {
      reassignment.reassign(
          new TaskReassignment.ReassignableTask(
              task.taskId(), task.tenantId(), task.processInstanceId(), task.assigneeId(), true),
          newAssigneeId,
          actorId,
          reason);
      return currentRevision(task);
    } catch (FlowableOptimisticLockingException | FlowableObjectNotFoundException e) {
      throw new VersionConflictException("WORKFLOW", (long) task.revision(), null);
    }
  }

  @Override
  public int extend(MonitoredTask task, Instant newDueAt, UUID actorId, String reason) {
    Authentication.setAuthenticatedUserId(actorId.toString());
    try {
      Instant oldDue = task.dueAt();
      tasks.setDueDate(task.taskId(), Date.from(newDueAt));
      if (oldDue != null) {
        moveDeadlineTimers(task, oldDue, Duration.between(oldDue, newDueAt));
      }
      tasks.addComment(
          task.taskId(),
          task.processInstanceId(),
          EXTEND_COMMENT_TYPE,
          "Gia hạn bước từ "
              + (oldDue == null ? "không có hạn" : oldDue.toString())
              + " đến "
              + newDueAt
              + ". Lý do: "
              + reason);
      return currentRevision(task);
    } catch (FlowableOptimisticLockingException | FlowableObjectNotFoundException e) {
      throw new VersionConflictException("WORKFLOW", (long) task.revision(), null);
    } finally {
      Authentication.setAuthenticatedUserId(null);
    }
  }

  /**
   * Hẹn giờ biên của bước (định nghĩa {@code timerEventDefinition} gắn vào phần tử) có hạn từ hạn
   * cũ trở đi được dời cùng khoảng; hẹn giờ nhắc trước hạn giữ nguyên.
   */
  void moveDeadlineTimers(MonitoredTask task, Instant oldDue, Duration shift) {
    Set<String> timerIds = boundaryTimerIds(task);
    if (timerIds.isEmpty()) {
      return;
    }
    List<Job> timers =
        management.createTimerJobQuery().processInstanceId(task.processInstanceId()).list();
    for (Job timer : timers) {
      if (timer.getDuedate() == null || !timerIds.contains(timer.getElementId())) {
        continue;
      }
      Instant due = timer.getDuedate().toInstant();
      if (due.isBefore(oldDue)) {
        continue;
      }
      management.rescheduleTimeDateJob(timer.getId(), due.plus(shift).toString());
    }
  }

  private Set<String> boundaryTimerIds(MonitoredTask task) {
    Task current = tasks.createTaskQuery().taskId(task.taskId()).singleResult();
    if (current == null || current.getProcessDefinitionId() == null) {
      return Set.of();
    }
    BpmnModel model = repository.getBpmnModel(current.getProcessDefinitionId());
    FlowElement element = model == null ? null : model.getFlowElement(task.activityId());
    if (!(element instanceof Activity activity)) {
      return Set.of();
    }
    return activity.getBoundaryEvents().stream()
        .filter(
            b -> b.getEventDefinitions().stream().anyMatch(TimerEventDefinition.class::isInstance))
        .map(BoundaryEvent::getId)
        .collect(Collectors.toSet());
  }

  @Override
  public Optional<WorkflowDiagram> diagram(BusinessKey businessKey, UUID tenantId) {
    HistoricProcessInstanceQuery query =
        history
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey.value());
    if (tenantId != null) {
      query.processInstanceTenantId(tenantId.toString());
    }
    List<HistoricProcessInstance> found =
        query.orderByProcessInstanceStartTime().desc().listPage(0, 1);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    HistoricProcessInstance instance = found.get(0);
    Optional<UUID> tenant = uuid(instance.getTenantId());
    if (tenant.isEmpty()) {
      return Optional.empty();
    }
    ProcessDefinition definition =
        repository.getProcessDefinition(instance.getProcessDefinitionId());
    Set<String> active = new LinkedHashSet<>();
    if (instance.getEndTime() == null) {
      active.addAll(runtime.getActiveActivityIds(instance.getId()));
    }
    Set<String> completed = new LinkedHashSet<>();
    for (HistoricActivityInstance a :
        history
            .createHistoricActivityInstanceQuery()
            .processInstanceId(instance.getId())
            .finished()
            .orderByHistoricActivityInstanceStartTime()
            .asc()
            .list()) {
      if (!SEQUENCE_FLOW.equals(a.getActivityType()) && !active.contains(a.getActivityId())) {
        completed.add(a.getActivityId());
      }
    }
    return Optional.of(
        new WorkflowDiagram(
            tenant.get(),
            businessKey,
            definition.getKey(),
            definition.getVersion(),
            bpmnXml(definition.getId()),
            List.copyOf(active),
            List.copyOf(completed)));
  }

  private String bpmnXml(String definitionId) {
    try (InputStream in = repository.getProcessModel(definitionId)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được định nghĩa BPMN", e);
    }
  }

  private int currentRevision(MonitoredTask task) {
    Task current = tasks.createTaskQuery().taskId(task.taskId()).singleResult();
    if (current == null) {
      throw new VersionConflictException("WORKFLOW", (long) task.revision(), null);
    }
    return current instanceof HasRevision r ? r.getRevision() : task.revision() + 1;
  }

  private static int revision(Task task) {
    return task instanceof HasRevision r ? r.getRevision() : 1;
  }

  private static Optional<UUID> uuid(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value.trim()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
