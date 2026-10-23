package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.port.DualApprovalProcesses;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval.Branch;
import vn.viettel.okrkpi.workflow.domain.stage.StageVariables;

/**
 * {@link DualApprovalProcesses} trên API công khai của Flowable 7: tìm thể hiện mẫu duyệt kép theo
 * khoá nghiệp vụ kế thừa từ luồng cha ({@code <LOẠI>:<objectId>}), lấy người duyệt nhánh từ lịch sử
 * user task của nhánh.
 */
@Component
public class FlowableDualApprovalProcesses implements DualApprovalProcesses {

  private final RuntimeService runtime;
  private final HistoryService history;

  public FlowableDualApprovalProcesses(RuntimeService runtime, HistoryService history) {
    this.runtime = runtime;
    this.history = history;
  }

  @Override
  public List<SignWait> waitingSignatures(UUID tenantId, UUID objectId) {
    List<SignWait> out = new ArrayList<>();
    List<ProcessInstance> instances =
        runtime
            .createProcessInstanceQuery()
            .processDefinitionKey(DualApproval.TEMPLATE)
            .processInstanceTenantId(tenantId.toString())
            .processInstanceBusinessKeyLike("%:" + objectId)
            .list();
    for (ProcessInstance instance : instances) {
      for (Execution e :
          runtime
              .createExecutionQuery()
              .processInstanceId(instance.getId())
              .messageEventSubscriptionName(StageVariables.MESSAGE_KY_SO)
              .list()) {
        Optional<Branch> branch = Branch.byElement(e.getActivityId());
        branch.ifPresent(b -> out.add(new SignWait(e.getId(), b, approver(instance.getId(), b))));
      }
    }
    return out;
  }

  @Override
  public void deliverSigned(SignWait wait) {
    runtime.messageEventReceived(
        StageVariables.MESSAGE_KY_SO,
        wait.executionId(),
        Map.of(wait.branch().signedVariable(), true));
  }

  private UUID approver(String processInstanceId, Branch branch) {
    List<HistoricTaskInstance> done =
        history
            .createHistoricTaskInstanceQuery()
            .processInstanceId(processInstanceId)
            .taskDefinitionKey(branch.elementId())
            .finished()
            .orderByHistoricTaskInstanceEndTime()
            .desc()
            .listPage(0, 1);
    if (done.isEmpty() || done.get(0).getAssignee() == null) {
      return null;
    }
    try {
      return UUID.fromString(done.get(0).getAssignee());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
