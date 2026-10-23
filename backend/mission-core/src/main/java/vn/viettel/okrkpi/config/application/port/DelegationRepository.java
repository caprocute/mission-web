package vn.viettel.okrkpi.config.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.Delegation;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.delegation} (CORE-08-16). */
public interface DelegationRepository {

  record Filter(
      Collection<UUID> units, UUID personId, Delegation.Kind kind, Delegation.Status status) {}

  void insert(TenantId tenantId, Delegation delegation, UUID actorId);

  Optional<Delegation> find(TenantId tenantId, UUID id);

  boolean update(TenantId tenantId, Delegation delegation, int expectedVersion, UUID actorId);

  void lockKey(TenantId tenantId, UUID delegatorId, Delegation.Kind kind);

  /** Uỷ quyền chưa kết thúc, giao nhau với khoảng từ {@code from} đến trước {@code to}. */
  List<Delegation> overlapping(
      TenantId tenantId,
      Delegation.Kind kind,
      UUID delegatorId,
      UUID delegateId,
      Instant from,
      Instant to);

  /**
   * Uỷ quyền chưa kết thúc mà {@code delegateId} nhận, giao nhau với khoảng từ {@code from} đến
   * trước {@code to}.
   */
  List<Delegation> receivedDuring(
      TenantId tenantId, Delegation.Kind kind, UUID delegateId, Instant from, Instant to);

  List<Delegation> list(TenantId tenantId, Filter filter, Instant now, UUID afterId, int limit);

  List<Delegation> dueExpiry(TenantId tenantId, Instant now, int limit);

  boolean markExpiryEmitted(TenantId tenantId, UUID id, Instant at);

  List<Delegation> history(TenantId tenantId, UUID personId, Collection<UUID> units, int limit);
}
