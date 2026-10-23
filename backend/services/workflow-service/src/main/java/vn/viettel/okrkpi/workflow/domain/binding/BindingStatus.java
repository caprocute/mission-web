package vn.viettel.okrkpi.workflow.domain.binding;

/**
 * Trạng thái tham số luồng của một phiên bản Policy Pack ({@code WorkflowBindingStatus}): {@code
 * NHAP} khi đang soạn, {@code HIEU_LUC} khi đã chép vào {@code wf_binding} lúc phát hành gói,
 * {@code NGUNG} khi gói bị gỡ hoặc phiên bản khác thay thế.
 */
public enum BindingStatus {
  NHAP,
  HIEU_LUC,
  NGUNG
}
