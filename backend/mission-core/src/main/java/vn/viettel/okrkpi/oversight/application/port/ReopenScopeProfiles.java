package vn.viettel.okrkpi.oversight.application.port;

import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Hồ sơ đánh giá nằm trong phạm vi của yêu cầu mở lại (MOD-04 qua {@code appraisal :: api}). */
@FunctionalInterface
public interface ReopenScopeProfiles {

  Set<UUID> profilesOf(TenantId tenant, ReopenRequest request);
}
