package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.history.HistoricIdentityLink;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.port.StageProcesses;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;
import vn.viettel.okrkpi.workflow.domain.stage.StageVariables;

/**
 * {@link StageProcesses} trên engine Flowable 7: tìm thể hiện theo (mẫu, tenant, khoá nghiệp vụ),
 * hoàn thành user task với người làm, chuyển trạng thái bằng {@code ChangeActivityStateBuilder},
 * giao message ký số và phát hành, và đọc lịch sử tham gia của hồ sơ đánh giá (người được giao của
 * bước thẩm định, đề xuất, quyết định; đơn vị của nhóm ứng viên bước thẩm định).
 */
@Component
public class FlowableStageProcesses implements StageProcesses {

  /** Bước của hồ sơ đánh giá → nhóm tham gia (ràng buộc 21.1). */
  static final Map<String, String> PARTICIPANT_GROUPS =
      Map.of("thamDinh", "appraiser", "deXuat", "proposer", "quyetDinh", "decider");

  private final RuntimeService runtime;
  private final TaskService tasks;
  private final HistoryService history;

  public FlowableStageProcesses(RuntimeService runtime, TaskService tasks, HistoryService history) {
    this.runtime = runtime;
    this.tasks = tasks;
    this.history = history;
  }

  @Override
  public Optional<Snapshot> active(UUID tenantId, StageTemplate template, UUID businessId) {
    List<ProcessInstance> instances =
        runtime
            .createProcessInstanceQuery()
            .processDefinitionKey(template.code())
            .processInstanceTenantId(tenantId.toString())
            .processInstanceBusinessKey(
                new BusinessKey(template.businessType(), businessId).value())
            .orderByStartTime()
            .desc()
            .listPage(0, 1);
    if (instances.isEmpty()) {
      return Optional.empty();
    }
    String pid = instances.get(0).getId();
    List<Task> open = tasks.createTaskQuery().processInstanceId(pid).list();
    Task task = open.isEmpty() ? null : open.get(0);
    return Optional.of(
        new Snapshot(
            pid,
            runtime.getActiveActivityIds(pid),
            task == null ? null : task.getId(),
            task == null ? null : task.getTaskDefinitionKey(),
            runtime.getVariables(pid)));
  }

  @Override
  public void completeStep(String taskId, UUID actorId, Map<String, Object> variables) {
    if (actorId != null) {
      tasks.setAssignee(taskId, actorId.toString());
    }
    Authentication.setAuthenticatedUserId(actorId == null ? null : actorId.toString());
    try {
      tasks.complete(taskId, variables);
    } finally {
      Authentication.setAuthenticatedUserId(null);
    }
  }

  @Override
  public void moveTo(String processInstanceId, List<String> fromElementIds, String toElementId) {
    runtime
        .createChangeActivityStateBuilder()
        .processInstanceId(processInstanceId)
        .moveActivityIdsToSingleActivityId(fromElementIds, toElementId)
        .changeState();
  }

  @Override
  public void deliverSigned(String processInstanceId, Map<String, Object> variables) {
    deliver(processInstanceId, StageVariables.MESSAGE_KY_SO, variables);
  }

  @Override
  public boolean deliverPublished(String processInstanceId) {
    return deliver(processInstanceId, StageVariables.MESSAGE_PHAT_HANH, Map.of());
  }

  private boolean deliver(String pid, String message, Map<String, Object> variables) {
    List<Execution> waiting =
        runtime
            .createExecutionQuery()
            .processInstanceId(pid)
            .messageEventSubscriptionName(message)
            .list();
    if (waiting.isEmpty()) {
      return false;
    }
    runtime.messageEventReceived(message, waiting.get(0).getId(), variables);
    return true;
  }

  @Override
  public void setVariables(String processInstanceId, Map<String, Object> variables) {
    runtime.setVariables(processInstanceId, variables);
  }

  @Override
  public Optional<ProfileHistory> appraisalHistory(UUID tenantId, UUID profileId) {
    StageTemplate t = StageTemplate.DUYET_HO_SO_DANH_GIA;
    List<HistoricProcessInstance> found =
        history
            .createHistoricProcessInstanceQuery()
            .processDefinitionKey(t.code())
            .processInstanceTenantId(tenantId.toString())
            .processInstanceBusinessKey(new BusinessKey(t.businessType(), profileId).value())
            .orderByProcessInstanceStartTime()
            .desc()
            .listPage(0, 1);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    String pid = found.get(0).getId();
    Map<String, Object> vars = new HashMap<>();
    for (HistoricVariableInstance v :
        history.createHistoricVariableInstanceQuery().processInstanceId(pid).list()) {
      if (v.getTaskId() == null) {
        vars.put(v.getVariableName(), v.getValue());
      }
    }
    Optional<ApprovalFlow> flow =
        ApprovalFlow.parse(String.valueOf(vars.get(ApprovalVariables.KHOI)));
    Object unit = vars.get(ApprovalVariables.DON_VI_ID);
    if (flow.isEmpty() || unit == null) {
      return Optional.empty();
    }
    Map<String, List<UUID>> participants = new HashMap<>();
    UUID appraisalUnit = null;
    for (HistoricTaskInstance task :
        history
            .createHistoricTaskInstanceQuery()
            .processInstanceId(pid)
            .orderByHistoricTaskInstanceStartTime()
            .asc()
            .list()) {
      String group = PARTICIPANT_GROUPS.get(task.getTaskDefinitionKey());
      if (group == null) {
        continue;
      }
      if (task.getEndTime() != null && task.getAssignee() != null) {
        UUID person = UUID.fromString(task.getAssignee());
        List<UUID> people = participants.computeIfAbsent(group, g -> new ArrayList<>());
        if (!people.contains(person)) {
          people.add(person);
        }
      }
      if ("thamDinh".equals(task.getTaskDefinitionKey())) {
        for (HistoricIdentityLink link : history.getHistoricIdentityLinksForTask(task.getId())) {
          if ("candidate".equals(link.getType()) && link.getGroupId() != null) {
            appraisalUnit =
                CandidateGroup.parse(link.getGroupId())
                    .map(CandidateGroup::unitId)
                    .orElse(appraisalUnit);
          }
        }
      }
    }
    return Optional.of(
        new ProfileHistory(
            flow.get(), UUID.fromString(unit.toString()), appraisalUnit, participants));
  }
}
