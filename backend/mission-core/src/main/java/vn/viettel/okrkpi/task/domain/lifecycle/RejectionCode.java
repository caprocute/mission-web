package vn.viettel.okrkpi.task.domain.lifecycle;

/** Lý do máy trạng thái từ chối một lệnh (TL-01). */
public enum RejectionCode {
  PERIOD_LOCKED,
  TRANSITION_NOT_ALLOWED,
  ROLE_NOT_ALLOWED,
  REASON_REQUIRED,
  GUARD_FAILED
}
