package vn.viettel.okrkpi.oversight.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 tổng hợp cảnh báo mức Cao mới cho một người xử lý (MSG-14-01). */
public interface AlertNotices {

  void newHighAlerts(TenantId tenant, UUID recipient, int count, UUID batchId);
}
