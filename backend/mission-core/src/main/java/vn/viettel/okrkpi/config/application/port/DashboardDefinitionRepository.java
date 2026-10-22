package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.DashboardDefinition;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.dashboard_def} (CORE-08-17). */
public interface DashboardDefinitionRepository {

  Optional<DashboardDefinition> find(TenantId tenantId, UUID id);

  /**
   * Dashboard người xem thấy được, mới nhất trước, sau {@code beforeId} nếu có.
   *
   * @param ownOnly chỉ lấy dashboard của chính người xem
   */
  List<DashboardDefinition> visible(
      TenantId tenantId,
      UUID userId,
      List<UUID> viewerUnits,
      boolean ownOnly,
      UUID beforeId,
      int limit);

  /** Đã có dashboard khác của người tạo mang tên này (không phân biệt hoa thường). */
  boolean titleTaken(TenantId tenantId, UUID ownerUserId, String title, UUID exceptId);

  /**
   * Khoá phạm vi (tenant, người tạo) tới hết giao dịch để hai yêu cầu đồng thời không cùng lưu một
   * tên (bảng không có ràng buộc duy nhất cho tên).
   */
  void lockOwner(TenantId tenantId, UUID ownerUserId);

  void insert(TenantId tenantId, DashboardDefinition definition, UUID actorId);

  /** Cập nhật khi {@code row_version} còn bằng {@code expectedVersion}. */
  boolean update(TenantId tenantId, DashboardDefinition next, int expectedVersion, UUID actorId);
}
