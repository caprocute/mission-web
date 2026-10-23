package vn.viettel.okrkpi.integ.application.alert;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thông điệp ra hết lượt thử lại và dấu đã cảnh báo ({@code integ.outbound_alert}). */
public interface OutboundAlerts {

  record Failed(UUID tenantId, UUID messageId, String connectorCode) {}

  /**
   * Qua hàm {@code fn_thu_ra_loi_chua_bao}: thông điệp {@code LOI} chưa cảnh báo của mọi tenant.
   */
  List<Failed> failedUnalerted(Collection<String> connectorCodes, int limit);

  /** Ghi dấu đã cảnh báo; {@code false} khi pod khác vừa ghi. */
  boolean markAlerted(TenantId tenant, UUID id, UUID messageId);
}
