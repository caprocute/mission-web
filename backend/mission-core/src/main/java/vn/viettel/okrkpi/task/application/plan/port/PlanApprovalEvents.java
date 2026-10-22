package vn.viettel.okrkpi.task.application.plan.port;

import vn.viettel.okrkpi.task.api.PlanVersionApprovedEvent;

/** EVT-03 {@code task.plan.approved.v1} ghi trong giao dịch chuyển {@code DA_DUYET}. */
public interface PlanApprovalEvents {

  void approved(PlanVersionApprovedEvent event);
}
