package vn.viettel.okrkpi.oversight.domain.complaint;

/**
 * Nhóm lý do đóng {@code oversight.complaint.close_reason_code}: ba lý do không thụ lý của bảng
 * trạng thái QT-12 (mã theo {@code rejectComplaint}, suy luận) và rút khiếu nại.
 */
public enum ComplaintCloseReason {
  QUA_HAN,
  SAI_THAM_QUYEN,
  TRUNG_NOI_DUNG,
  RUT;

  public boolean rejection() {
    return this != RUT;
  }
}
