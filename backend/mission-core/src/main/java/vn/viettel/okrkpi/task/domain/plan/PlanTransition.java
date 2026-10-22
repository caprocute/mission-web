package vn.viettel.okrkpi.task.domain.plan;

import vn.viettel.okrkpi.task.domain.lifecycle.RejectionCode;

/** Kết quả áp một {@link PlanCommand}, cùng kiểu mã từ chối với máy trạng thái nhiệm vụ. */
public sealed interface PlanTransition {

  record Accepted(PlanVersionStatus from, PlanVersionStatus to, PlanCommand command)
      implements PlanTransition {}

  record Rejected(RejectionCode code, String message) implements PlanTransition {}
}
