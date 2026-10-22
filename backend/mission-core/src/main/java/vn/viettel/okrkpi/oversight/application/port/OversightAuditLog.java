package vn.viettel.okrkpi.oversight.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Ghi sự kiện kiểm toán của MOD-07 lên {@code sys.audit.v1} qua outbox của schema oversight. */
@FunctionalInterface
public interface OversightAuditLog {

  /**
   * @param action loại hành động, ví dụ {@code XEM_NHAT_KY_KIEM_TOAN}
   * @param objectType loại đối tượng
   * @param objectId mã đối tượng hoặc tóm tắt điều kiện (chỉ mã, không văn bản tự do)
   * @param purpose mã mục đích, có thể {@code null}
   * @param actorRole vai trò đang chọn của người thực hiện
   */
  void record(
      TenantId tenantId,
      UUID actorId,
      String action,
      String objectType,
      String objectId,
      String purpose,
      String actorRole);
}
