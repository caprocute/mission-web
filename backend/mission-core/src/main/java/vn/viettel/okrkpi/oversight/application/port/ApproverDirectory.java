package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dữ liệu để chọn người duyệt mở lại kỳ (CORE-07-08): người giữ vai trò theo đơn vị ({@code
 * config.role_binding}), cây đơn vị ({@code org_replica}) và người đã thẩm định, quyết định hồ sơ
 * trong phạm vi ({@code appraisal}, BR-13-03).
 */
public interface ApproverDirectory {

  /**
   * Người giữ vai trò có hiệu lực tại {@code orgUnitId}: gắn đúng đơn vị, hoặc gắn ở đơn vị cấp
   * trên với phạm vi gồm cấp dưới.
   */
  List<UUID> holders(TenantId tenant, String roleCode, UUID orgUnitId, Instant at);

  /** Đơn vị cấp trên, gần trước (không gồm chính nó). */
  List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId);

  /**
   * Người đã thẩm định hoặc quyết định hồ sơ ({@code profileIds}) hay hồ sơ của {@code unitIds}.
   */
  Set<UUID> participants(
      TenantId tenant, UUID periodId, Collection<UUID> profileIds, Collection<UUID> unitIds);
}
