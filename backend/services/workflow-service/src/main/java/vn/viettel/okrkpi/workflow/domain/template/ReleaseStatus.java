package vn.viettel.okrkpi.workflow.domain.template;

/** Trạng thái đề nghị phát hành ({@code wf_template_release.status}). */
public enum ReleaseStatus {
  /** Merge request đã mở, chờ phê duyệt. */
  MO,
  /** Đã phê duyệt, chờ pipeline ký. */
  DA_DUYET,
  /** Pipeline đã ký cosign. */
  DA_KY,
  /** Chữ ký đã được ghi vào phiên bản mẫu ({@code HIEU_LUC}). */
  DA_AP_DUNG
}
