package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.SavedFilter;
import vn.viettel.okrkpi.config.domain.UserPreference;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho tuỳ chọn cá nhân và bộ lọc đã lưu (CNH-02). Mọi truy vấn lọc theo {@code user_id} của người
 * gọi nên bản ghi của người khác không bao giờ đọc ghi được (phạm vi Bản thân).
 */
public interface UserPreferenceRepository {

  List<UserPreference> findAll(TenantId tenantId, UUID userId);

  Optional<UserPreference> find(TenantId tenantId, UUID userId, String prefKey);

  /** Chèn khoá mới; {@code false} khi khoá vừa được thiết bị khác tạo. */
  boolean insert(TenantId tenantId, UserPreference preference, UUID actorId);

  /** Cập nhật khi {@code row_version} còn bằng {@code expectedVersion}. */
  boolean update(TenantId tenantId, UserPreference next, int expectedVersion, UUID actorId);

  boolean delete(TenantId tenantId, UUID userId, String prefKey, int expectedVersion);

  List<SavedFilter> filters(TenantId tenantId, UUID userId, String screenCode);

  Optional<SavedFilter> filter(TenantId tenantId, UUID userId, UUID filterId);

  /**
   * Khoá phạm vi (tenant, người dùng, màn hình) tới hết giao dịch để đếm giới hạn 20 bộ lọc không
   * bị hai yêu cầu đồng thời vượt qua.
   */
  void lockScreen(TenantId tenantId, UUID userId, String screenCode);

  /** Bỏ cờ mặc định của các bộ lọc khác trên màn hình. */
  void clearDefault(TenantId tenantId, UUID userId, String screenCode, UUID exceptId, UUID actorId);

  boolean insertFilter(TenantId tenantId, SavedFilter filter, UUID actorId);

  boolean updateFilter(TenantId tenantId, SavedFilter next, int expectedVersion, UUID actorId);

  boolean deleteFilter(TenantId tenantId, UUID userId, UUID filterId, int expectedVersion);
}
