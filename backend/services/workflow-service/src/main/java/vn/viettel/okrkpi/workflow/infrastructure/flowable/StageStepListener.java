package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.stage.StageStepHooks;
import vn.viettel.okrkpi.workflow.domain.stage.StageVariables;

/**
 * Listener {@code ${lapLichBuoc}} của bốn mẫu phần 2 (tính toán thuần, đọc bản sao cục bộ, TL-01 G2
 * quy tắc 4). Gắn ở sự kiện {@code start} của user task: đặt {@code nhomBuoc}, {@code nguoiBuoc},
 * {@code hanBuoc}, {@code nhacBuoc} trước khi engine tạo task và timer biên. Gắn ở sự kiện {@code
 * take} của nhánh ra khỏi sự kiện biên {@code quaHan_<bước>}: tăng số lần quá hạn của bước.
 */
@Component("lapLichBuoc")
public class StageStepListener implements ExecutionListener {

  private static final long serialVersionUID = 1L;

  private final transient StageStepHooks hooks;

  public StageStepListener(StageStepHooks hooks) {
    this.hooks = hooks;
  }

  @Override
  public void notify(DelegateExecution execution) {
    FlowElement element = execution.getCurrentFlowElement();
    if (element instanceof SequenceFlow flow) {
      execution.setVariable(
          StageVariables.SO_LAN_QUA_HAN,
          hooks.overdue(flow.getSourceRef(), execution.getVariable(StageVariables.SO_LAN_QUA_HAN)));
      return;
    }
    String definition = execution.getProcessDefinitionId();
    String key = definition.substring(0, definition.indexOf(':'));
    hooks
        .enterStep(key, execution.getCurrentActivityId(), execution.getVariables())
        .forEach(execution::setVariable);
  }
}
