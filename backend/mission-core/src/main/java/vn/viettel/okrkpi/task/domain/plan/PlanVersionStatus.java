package vn.viettel.okrkpi.task.domain.plan;

/** Trạng thái phiên bản kế hoạch theo baseline mục 9.1 ({@code ck_plan_version__status}). */
public enum PlanVersionStatus {
  NHAP,
  CHO_DUYET,
  TRA_LAI,
  DA_DUYET;

  /** Chỉ phiên bản nháp hoặc bị trả lại mới sửa được dòng (TL-02a QT-04). */
  public boolean editable() {
    return this == NHAP || this == TRA_LAI;
  }
}
