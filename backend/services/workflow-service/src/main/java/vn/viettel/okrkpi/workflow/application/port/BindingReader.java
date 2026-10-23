package vn.viettel.okrkpi.workflow.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;

/** Cổng đọc tham số mẫu luồng theo tenant từ {@code flowable.wf_binding}. */
public interface BindingReader {

  /**
   * Tham số của tenant cho loại hồ sơ và luồng; ưu tiên dòng khớp luồng, sau đó dòng không ghi
   * luồng. Rỗng khi tenant chưa cấu hình.
   */
  Optional<ApprovalParams> find(UUID tenantId, String processKind, ApprovalFlow flow);

  /**
   * {@code params_json} thô của dòng được chọn theo cùng thứ tự ưu tiên (mẫu phần 2 đọc các khoá
   * {@code cap[].buoc}, {@code bo_qua_khi}, {@code nhac_truoc}, {@code khi_qua_han}, {@code
   * nguoi_thay}, {@code so_lan_tra_lai}). Rỗng khi tenant chưa cấu hình.
   */
  default Optional<String> rawParams(UUID tenantId, String processKind, ApprovalFlow flow) {
    return Optional.empty();
  }
}
