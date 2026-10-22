package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.port.HistoryReader;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.HistoryAction;
import vn.viettel.okrkpi.workflow.domain.ProcessHistory;

/**
 * {@link HistoryReader} trên API lịch sử của Flowable ({@code ACT_HI_PROCINST}, {@code
 * ACT_HI_TASKINST}, {@code ACT_HI_VARINST} ở mức {@code audit}). Mỗi user task đã hoàn thành qua
 * API có biến cục bộ {@code taskDecision}, {@code taskComment}; task bị huỷ không có quyết định nên
 * không vào lịch sử duyệt.
 */
@Component
public class FlowableHistoryReader implements HistoryReader {

  private final HistoryService history;

  public FlowableHistoryReader(HistoryService history) {
    this.history = history;
  }

  @Override
  public Optional<ProcessHistory> find(UUID tenantId, BusinessKey businessKey) {
    List<HistoricProcessInstance> instances =
        history
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey.value())
            .processInstanceTenantId(tenantId.toString())
            .orderByProcessInstanceStartTime()
            .desc()
            .listPage(0, 1);
    if (instances.isEmpty()) {
      return Optional.empty();
    }
    HistoricProcessInstance instance = instances.get(0);
    Map<String, Map<String, Object>> taskVariables = new HashMap<>();
    for (HistoricVariableInstance v :
        history.createHistoricVariableInstanceQuery().processInstanceId(instance.getId()).list()) {
      if (v.getTaskId() != null) {
        taskVariables
            .computeIfAbsent(v.getTaskId(), k -> new HashMap<>())
            .put(v.getVariableName(), v.getValue());
      }
    }
    UUID initiator = uuid(instance.getStartUserId());
    List<ProcessHistory.Entry> entries = new ArrayList<>();
    entries.add(
        new ProcessHistory.Entry(
            instance.getStartTime().toInstant(),
            HistoryAction.TAO_TIEN_TRINH,
            initiator,
            null,
            null));
    for (HistoricTaskInstance task :
        history
            .createHistoricTaskInstanceQuery()
            .processInstanceId(instance.getId())
            .finished()
            .orderByHistoricTaskInstanceEndTime()
            .asc()
            .list()) {
      Map<String, Object> vars = taskVariables.getOrDefault(task.getId(), Map.of());
      Object decision = vars.get(FlowableTaskEngine.TASK_DECISION_VARIABLE);
      if (decision == null || task.getEndTime() == null) {
        continue;
      }
      Object comment = vars.get(FlowableTaskEngine.TASK_COMMENT_VARIABLE);
      entries.add(
          new ProcessHistory.Entry(
              task.getEndTime().toInstant(),
              HistoryAction.of(Decision.valueOf(decision.toString())),
              uuid(task.getAssignee()),
              task.getName(),
              comment == null ? null : comment.toString()));
    }
    if (instance.getEndTime() != null) {
      entries.add(
          new ProcessHistory.Entry(
              instance.getEndTime().toInstant(), HistoryAction.KET_THUC, null, null, null));
    }
    return Optional.of(
        new ProcessHistory(
            businessKey,
            instance.getProcessDefinitionKey(),
            instance.getProcessDefinitionVersion(),
            instance.getEndTime() != null,
            initiator,
            entries));
  }

  private static UUID uuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
