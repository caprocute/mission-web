package vn.viettel.okrkpi.config.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ghi sổ cái thay đổi cấu hình qua outbox ({@code sys.audit.v1}, SVC-09 ghi sổ cái) trong cùng giao
 * dịch (CORE-08-07).
 */
@FunctionalInterface
public interface ConfigAudit {

  /**
   * @param action loại hành động, ví dụ {@code DOI_CO_TINH_NANG}
   * @param objectType loại đối tượng, ví dụ {@code config.feature_flag}
   * @param objectId mã đối tượng (mã cờ, khoá cấu hình)
   * @param aggregateId mã dòng
   * @param version phiên bản dòng sau khi ghi
   */
  void changed(
      TenantId tenantId,
      UUID actorId,
      String action,
      String objectType,
      String objectId,
      UUID aggregateId,
      long version);
}
