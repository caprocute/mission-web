package vn.viettel.okrkpi.integ.application.alert;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cảnh báo cho quản trị (metric và EVT-21 {@code notify.message.requested.v1}); gọi trong giao dịch
 * đang mở để yêu cầu thông báo đi cùng dấu đã cảnh báo.
 */
public interface AdminAlerts {

  /** Một ngày chưa đủ số biên nhận neo. */
  void anchorShortfall(
      TenantId tenant,
      UUID windowId,
      String cellCode,
      Instant windowEnd,
      int receipts,
      int required);

  /** Thông điệp ra đã hết lượt thử lại mà bên nhận chưa xác nhận. */
  void outboundOverdue(TenantId tenant, String connectorCode, UUID messageId);
}
