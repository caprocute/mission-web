package vn.viettel.okrkpi.workflow.domain;

/**
 * Quyết định khi hoàn thành user task, đặt vào biến {@code decision} của tiến trình (hợp đồng
 * {@code WorkflowDecision}; TL-01 chương 8 dùng {@code TRA_LAI}).
 */
public enum Decision {
  DUYET,
  TRA_LAI
}
