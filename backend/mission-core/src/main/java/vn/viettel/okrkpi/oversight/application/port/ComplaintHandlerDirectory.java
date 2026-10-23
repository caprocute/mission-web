package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.complaint.Complaint;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dữ liệu chọn và kiểm người xử lý khiếu nại (CORE-07-03): người giữ vai trò {@code appraiser} theo
 * đơn vị ({@code config.role_binding}), cây đơn vị ({@code org_replica}) và người đã tham gia hồ sơ
 * bị khiếu nại ({@code appraisal.rating_proposal}, {@code product.acceptance}).
 */
public interface ComplaintHandlerDirectory {

  /**
   * Người giữ vai trò {@code appraiser} có hiệu lực tại đơn vị (gắn trực tiếp hoặc phủ cấp dưới).
   */
  List<UUID> appraisersAt(TenantId tenant, UUID orgUnitId, Instant at);

  /** Đơn vị cấp trên, gần trước (không gồm chính nó). */
  List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId);

  /** Mọi đơn vị cấp dưới (không gồm chính nó). */
  List<UUID> subunitsOf(TenantId tenant, UUID orgUnitId);

  /**
   * Người đã tham gia hồ sơ theo nhóm {@code acceptor}, {@code appraiser}, {@code proposer}, {@code
   * decider} (tên nhóm của ràng buộc 21.1).
   */
  Map<String, Set<UUID>> participants(TenantId tenant, Complaint complaint);
}
