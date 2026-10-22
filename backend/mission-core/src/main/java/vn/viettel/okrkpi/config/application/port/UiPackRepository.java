package vn.viettel.okrkpi.config.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.UiPack;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.config.domain.UiPackVersionStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.ui_pack} và {@code config.ui_pack_version} (CORE-08-08). */
public interface UiPackRepository {

  /** UI Pack của tenant theo mã dòng tăng dần, sau {@code afterId} nếu có. */
  List<UiPack> listPacks(TenantId tenantId, UUID afterId, int limit);

  Optional<UiPack> findPack(TenantId tenantId, UUID packId);

  void insertPack(TenantId tenantId, UiPack pack, UUID actorId);

  /** Phiên bản mới nhất trước (mã UUIDv7 giảm dần), trước {@code beforeId} nếu có. */
  List<UiPackVersion> listVersions(
      TenantId tenantId, UUID packId, UiPackVersionStatus status, UUID beforeId, int limit);

  Optional<UiPackVersion> findVersion(TenantId tenantId, UUID packId, String version);

  Optional<UiPackVersion> findVersionById(TenantId tenantId, UUID versionId);

  /** Phiên bản đang soạn hoặc đang duyệt mới nhất của UI Pack. */
  Optional<String> findInProgressVersion(TenantId tenantId, UUID packId);

  /**
   * @return {@code false} khi UI Pack đã có phiên bản cùng số
   */
  boolean insertVersion(TenantId tenantId, UiPackVersion version);

  /**
   * Ghi trạng thái và nội dung mới của phiên bản khi phiên bản dòng còn là {@code expectedVersion}.
   *
   * @return {@code false} khi dòng đã bị người khác đổi
   */
  boolean updateVersion(TenantId tenantId, UiPackVersion version, int expectedVersion);

  /** UI Pack đang có phiên bản phát hành của tenant, phát hành gần nhất trước. */
  Optional<UiPack> findCurrentPack(TenantId tenantId);

  /**
   * Gắn phiên bản hiện hành cho UI Pack khi phiên bản dòng của gói còn là {@code
   * pack.rowVersion()}.
   *
   * @return {@code false} khi dòng đã bị người khác đổi
   */
  boolean setCurrentVersion(
      TenantId tenantId, UiPack pack, UUID versionId, UUID actorId, Instant at);
}
