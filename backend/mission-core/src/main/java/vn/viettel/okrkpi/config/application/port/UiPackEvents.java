package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-23 {@code config.uipack.published.v1} qua outbox, trong giao dịch phát hành (CORE-08-11). */
public interface UiPackEvents {

  void published(TenantId tenantId, UiPackVersion version, List<String> cdnPaths);
}
