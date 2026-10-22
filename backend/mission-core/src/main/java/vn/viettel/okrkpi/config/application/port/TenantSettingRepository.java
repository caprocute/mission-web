package vn.viettel.okrkpi.config.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.tenant_setting} (CORE-08-07). */
public interface TenantSettingRepository {

  /** Một dòng cấu hình. */
  record Setting(
      UUID id,
      String key,
      UUID scopeUnitId,
      JsonNode value,
      String valueSchema,
      String description,
      int rowVersion,
      Instant updatedAt) {}

  /** Dòng đúng phạm vi: {@code unitId} trống là dòng toàn tenant. */
  Optional<Setting> find(TenantId tenantId, String key, UUID unitId);

  /**
   * Thêm dòng mới với phiên bản của {@code setting}; {@code false} khi đã có dòng cùng khoá, phạm
   * vi.
   */
  boolean insert(TenantId tenantId, Setting setting, UUID actorId);

  /** Cập nhật dòng có {@code row_version = expectedVersion}; {@code false} khi xung đột. */
  boolean update(TenantId tenantId, Setting setting, int expectedVersion, UUID actorId);
}
