package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * EVT-22 {@code oversight.period.reopened.v1} vào {@code oversight.outbox_event}, cùng giao dịch.
 */
public interface ReopenEvents {

  void reopened(TenantId tenant, ReopenRequest request, List<UUID> approverIds, Instant at);
}
