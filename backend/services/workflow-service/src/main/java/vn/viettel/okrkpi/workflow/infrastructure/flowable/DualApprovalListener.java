package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.dual.DualApprovalHooks;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval;

/**
 * Listener {@code ${lapLichKep}} của mẫu {@code duyet-kep-chi-huy-cap-uy} (tính toán thuần, đọc bản
 * sao cục bộ, TL-01 G2 quy tắc 4). Gắn ở sự kiện bắt đầu: chụp kế hoạch hai nhánh. Gắn ở sự kiện
 * {@code start} của user task nhánh: đặt nhóm, người, hạn, mốc nhắc trước khi engine tạo task và
 * timer biên. Gắn ở sự kiện {@code take} của nhánh ra khỏi {@code quaHan_<nhánh>}: tăng số lần quá
 * hạn.
 */
@Component("lapLichKep")
public class DualApprovalListener implements ExecutionListener {

  private static final long serialVersionUID = 1L;

  private final transient DualApprovalHooks hooks;

  public DualApprovalListener(DualApprovalHooks hooks) {
    this.hooks = hooks;
  }

  @Override
  public void notify(DelegateExecution execution) {
    FlowElement element = execution.getCurrentFlowElement();
    if (element instanceof SequenceFlow flow) {
      execution.setVariable(
          DualApproval.OVERDUE_COUNTS,
          hooks.overdue(flow.getSourceRef(), execution.getVariable(DualApproval.OVERDUE_COUNTS)));
      return;
    }
    if (DualApproval.START.equals(execution.getCurrentActivityId())) {
      hooks.start(execution.getVariables()).forEach(execution::setVariable);
      return;
    }
    hooks
        .enterBranch(execution.getCurrentActivityId(), execution.getVariables())
        .forEach(execution::setVariable);
  }
}
