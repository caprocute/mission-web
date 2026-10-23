package vn.viettel.okrkpi.oversight.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tra người và đơn vị của đối tượng cảnh báo (CORE-07-11, BR-14-03, BR-14-08): {@code CA_NHAN} là
 * hồ sơ đánh giá, {@code DON_VI} là đơn vị; {@code NGUOI_DUYET} chưa tra được (wiki trang 14).
 */
public interface AlertSubjects {

  /** Đơn vị và người là đối tượng; {@code unitId} rỗng khi chưa tra được. */
  record Subject(UUID unitId, Set<UUID> people, String name) {
    public Subject {
      people = people == null ? Set.of() : Set.copyOf(people);
    }
  }

  Map<UUID, Subject> resolve(TenantId tenant, Collection<AnomalyAlert> alerts);

  /** Đơn vị trong phạm vi của đơn vị phân công (chính nó và đơn vị con). */
  Set<UUID> scopeOf(TenantId tenant, UUID unitId);

  Map<UUID, String> personNames(TenantId tenant, Collection<UUID> people);
}
