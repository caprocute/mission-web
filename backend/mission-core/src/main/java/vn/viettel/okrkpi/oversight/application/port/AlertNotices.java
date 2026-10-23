package vn.viettel.okrkpi.oversight.application.port;

import java.util.Collection;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * EVT-21 của cảnh báo bất thường: tổng hợp cảnh báo mức Cao mới cho một người xử lý (MSG-14-01);
 * giao kiểm tra cho người được giao và xác nhận hợp lệ cho người xử lý khác (CORE-07-11).
 */
public interface AlertNotices {

  void newHighAlerts(TenantId tenant, UUID recipient, int count, UUID batchId);

  /** Người của cơ quan kiểm tra được giao xác minh một cảnh báo. */
  default void assigned(TenantId tenant, UUID recipient, UUID alertId, UUID actionId) {}

  /** Cảnh báo được xác nhận hợp lệ, {@code unblocked} minh chứng được tính điểm trở lại. */
  default void validated(
      TenantId tenant, Collection<UUID> recipients, UUID alertId, int unblocked, UUID actionId) {}
}
