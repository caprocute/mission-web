package vn.viettel.okrkpi.oversight.domain.complaint;

/**
 * Nhóm lý do đóng khiếu nại không qua kết luận ({@code complaint.close_reason_code}): ba nhóm không
 * thụ lý của bảng trạng thái QT-12 ({@code rejectComplaint.reasonCode}) và rút khiếu nại.
 */
public enum CloseReason {
  QUA_HAN,
  SAI_THAM_QUYEN,
  TRUNG_NOI_DUNG,
  RUT
}
