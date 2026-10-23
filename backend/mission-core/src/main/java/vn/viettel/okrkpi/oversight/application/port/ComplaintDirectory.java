package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi.Participants;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dữ liệu chọn và kiểm người xử lý khiếu nại (CORE-07-03): người tham gia hồ sơ (MOD-04, MOD-03),
 * người giữ vai trò theo đơn vị (MOD-01) và cây đơn vị.
 */
public interface ComplaintDirectory {

  Optional<Participants> participants(TenantId tenant, UUID profileId);

  /** Người giữ vai trò có hiệu lực tại đơn vị. */
  List<UUID> holders(TenantId tenant, String roleCode, UUID unitId, Instant at);

  /** Đơn vị cấp trên, gần trước (không gồm chính nó). */
  List<UUID> ancestorsOf(TenantId tenant, UUID unitId);

  /** Đơn vị và mọi cấp dưới (phạm vi {@code Gn}). */
  Set<UUID> scopeOf(TenantId tenant, UUID unitId);
}
