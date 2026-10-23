package vn.viettel.okrkpi.oversight.domain.complaint;

/**
 * Trạng thái {@code oversight.complaint.status} theo TL-02b QT-12, thêm {@code RUT}; khiếu nại nháp
 * có trạng thái {@code null}.
 */
public enum ComplaintStatus {
  MOI,
  DA_TIEP_NHAN,
  DANG_GIAI_TRINH,
  CHO_KET_LUAN,
  DA_TRA_LOI,
  DANG_THI_HANH,
  KHONG_THU_LY,
  RUT,
  DONG
}
