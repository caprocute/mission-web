package vn.viettel.okrkpi.task.domain.plan;

/**
 * Lệnh trên một phiên bản kế hoạch: lưu nháp, sao chép quý trước (CORE-02-05), gửi duyệt, trả lại
 * (CORE-02-08), duyệt (CORE-02-09).
 */
public enum PlanCommand {
  SAVE,
  COPY_FROM_PREVIOUS,
  SUBMIT,
  RETURN,
  APPROVE
}
