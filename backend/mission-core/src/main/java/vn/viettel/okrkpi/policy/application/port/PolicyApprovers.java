package vn.viettel.okrkpi.policy.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Người giữ vai trò phê duyệt phát hành Policy Pack (AC-08 {@code decider}) của tenant. */
public interface PolicyApprovers {

  List<UUID> approvers(TenantId tenant);
}
