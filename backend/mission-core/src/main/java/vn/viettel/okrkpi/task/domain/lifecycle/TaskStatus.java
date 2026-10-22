package vn.viettel.okrkpi.task.domain.lifecycle;

/** Trạng thái {@code task.task.status} theo baseline mục 9.1; cờ quá hạn không phải trạng thái. */
public enum TaskStatus {
  NHAP,
  DA_GIAO,
  DANG_THUC_HIEN,
  CHO_NGHIEM_THU,
  HOAN_THANH,
  TAM_DUNG,
  HUY;

  /** Trạng thái kết thúc: không còn chuyển tiếp. */
  public boolean isTerminal() {
    return this == HOAN_THANH || this == HUY;
  }
}
