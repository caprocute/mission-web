package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.CustomRole;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-18 · {@code config.custom_role}. */
public interface CustomRoleRepository {

  /** Mọi vai trò tuỳ chỉnh của tenant, {@code status} rỗng là mọi trạng thái; theo tên. */
  List<CustomRole> list(TenantId tenantId, CustomRole.Status status);

  Optional<CustomRole> find(TenantId tenantId, UUID id);

  Optional<CustomRole> findByCode(TenantId tenantId, String code);

  /** Mã đã có ở bất kỳ tenant nào trong cell (mã là realm role dùng chung realm). */
  boolean codeTaken(String code);

  boolean nameTaken(TenantId tenantId, String name, UUID exceptId);

  void insert(TenantId tenantId, CustomRole role, UUID actorId);

  /** Ghi đè nội dung và trạng thái; {@code false} khi {@code expectedVersion} lệch. */
  boolean update(
      TenantId tenantId, CustomRole role, int expectedVersion, UUID actorId, String retireReason);

  /** Số người đang giữ từng vai trò tuỳ chỉnh (bản ghi cấp còn hiệu lực). */
  Map<String, Integer> holderCounts(TenantId tenantId);
}
