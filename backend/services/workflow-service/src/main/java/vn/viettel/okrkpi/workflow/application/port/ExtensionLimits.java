package vn.viettel.okrkpi.workflow.application.port;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * Cổng đọc giới hạn gia hạn của mẫu luồng cho tenant từ {@code flowable.wf_binding.params_json}.
 */
public interface ExtensionLimits {

  /**
   * Số ngày làm việc tối đa được gia thêm cho một bước theo tham số của tenant; rỗng khi tenant
   * chưa khai tham số (dùng mặc định cấu hình).
   */
  OptionalInt maxWorkingDays(UUID tenantId, String templateCode);
}
