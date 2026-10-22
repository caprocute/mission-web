package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.FeatureFlag;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.feature_flag} (CORE-08-07). Mô tả cờ lấy từ danh mục, không lưu. */
public interface FeatureFlagRepository {

  /** Các cờ tenant đã ghi; {@code description} trống. */
  List<FeatureFlag> findAll(TenantId tenantId);

  Optional<FeatureFlag> find(TenantId tenantId, String key);

  /**
   * Ghi cờ: thêm khi {@code expectedVersion} là 0 và tenant chưa có dòng, ngược lại cập nhật dòng
   * có {@code row_version = expectedVersion}.
   *
   * @return {@code false} khi xung đột phiên bản
   */
  boolean save(TenantId tenantId, FeatureFlag flag, int expectedVersion, UUID actorId);
}
