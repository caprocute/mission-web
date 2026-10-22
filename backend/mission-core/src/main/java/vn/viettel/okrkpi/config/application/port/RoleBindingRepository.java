package vn.viettel.okrkpi.config.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.role_binding} (CORE-08-16). */
public interface RoleBindingRepository {

  /**
   * Bộ lọc danh sách: {@code units} rỗng là mọi đơn vị; {@code status} rỗng là mọi trạng thái trừ
   * {@code TU_CHOI}; {@code awaitingApprover} khác rỗng chỉ lấy đề nghị chờ người đó duyệt.
   */
  record Filter(
      Collection<UUID> units,
      UUID personId,
      String roleCode,
      RoleBinding.Status status,
      UUID awaitingApprover) {}

  record Counts(int active, int pending, int awaitingMe, int expiringSoon) {}

  void insert(TenantId tenantId, RoleBinding binding, UUID actorId);

  Optional<RoleBinding> find(TenantId tenantId, UUID id);

  boolean update(TenantId tenantId, RoleBinding binding, int expectedVersion, UUID actorId);

  /** Khoá tư vấn theo người, vai trò, đơn vị, phạm vi để kiểm trùng (409). */
  void lockKey(TenantId tenantId, UUID personId, String roleCode, UUID orgUnitId, String scope);

  /** Còn bản ghi chưa kết thúc và chưa hết hạn trùng người, vai trò, đơn vị, phạm vi. */
  boolean existsOpen(
      TenantId tenantId, UUID personId, String roleCode, UUID orgUnitId, String scope, Instant at);

  List<RoleBinding> list(TenantId tenantId, Filter filter, Instant now, UUID afterId, int limit);

  Counts counts(TenantId tenantId, Collection<UUID> units, UUID caller, Instant now);

  /** Người có vai trò đang hiệu lực tại một trong các đơn vị. */
  boolean holds(
      TenantId tenantId, UUID personId, String roleCode, Collection<UUID> units, Instant at);

  /** Vai trò đang hiệu lực của một người (đồng bộ Keycloak, dữ liệu OPA). */
  List<RoleBinding> effectiveOf(TenantId tenantId, UUID personId, Instant at);

  /** Người có ít nhất một bản ghi vai trò, tăng dần sau {@code afterId} (đối soát Keycloak). */
  List<UUID> persons(TenantId tenantId, UUID afterId, int limit);

  /** Bản ghi chưa kết thúc đã quá {@code valid_to} nhưng chưa phát sự kiện hết hạn. */
  List<RoleBinding> dueExpiry(TenantId tenantId, Instant now, int limit);

  /** Đánh dấu đã phát sự kiện hết hạn; {@code false} khi lượt khác đã đánh dấu. */
  boolean markExpiryEmitted(TenantId tenantId, UUID id, Instant at);

  /** Bản ghi có hoạt động của người hoặc đơn vị, cho dòng thời gian. */
  List<RoleBinding> history(TenantId tenantId, UUID personId, Collection<UUID> units, int limit);
}
