package vn.viettel.okrkpi.replica.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc bản sao tổ chức (CORE-00-03). Mọi truy vấn chạy trong giao dịch có {@code app.tenant_id}
 * của {@code tenant}; dữ liệu là bản sao gần thời gian thực, quyết định cuối cùng vẫn ở org-service
 * (SVC-04).
 */
public interface OrgDirectoryApi {

  /** Phân công hiệu lực của người tại ngày {@code on}, phân công chính trước. */
  List<AssignmentView> assignmentsOf(TenantId tenant, UUID personId, LocalDate on);

  /** Những người đứng đầu đơn vị tại ngày {@code on}. */
  List<UUID> headsOf(TenantId tenant, UUID orgUnitId, LocalDate on);

  /** Cấp trên trực tiếp của đơn vị trên cây {@code tree} (bao đóng hiện hành). */
  Optional<UUID> parentOf(TenantId tenant, OrgTree tree, UUID orgUnitId);

  /** Các đơn vị cấp dưới (không gồm chính nó) theo cây; {@code maxDepth <= 0} là mọi cấp. */
  List<UUID> descendantsOf(TenantId tenant, OrgTree tree, UUID orgUnitId, int maxDepth);

  /** Tổ tiên từ gần tới xa (không gồm chính nó), dùng làm đầu vào cây cho OPA (NEN-040). */
  List<UUID> ancestorsOf(TenantId tenant, OrgTree tree, UUID orgUnitId);

  /** {@code candidate} là hậu duệ (hoặc chính) {@code ancestor} trên cây {@code tree}. */
  boolean isDescendant(TenantId tenant, OrgTree tree, UUID ancestor, UUID candidate);
}
