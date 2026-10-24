package vn.viettel.okrkpi.config.application;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.port.RealmRoles;
import vn.viettel.okrkpi.config.application.port.RoleBindingRepository;
import vn.viettel.okrkpi.config.domain.CustomRole;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đồng bộ realm role Keycloak theo {@code config.role_binding} (WEB-058 phương án A, CORE-08-16):
 * người có vai trò {@code r} trên Keycloak khi và chỉ khi có ít nhất một bản ghi {@code r} đang
 * hiệu lực. Chỉ đụng tới chín vai trò của MOD-08, không gỡ vai trò realm khác. Gọi lại nhiều lần
 * cho cùng kết quả, nên dùng được cho cả consumer EVT-27 và đối soát định kỳ.
 */
public class RealmRoleSync {

  /** Kết quả một lần đồng bộ một người. */
  public record Result(Set<String> added, Set<String> removed) {
    public boolean changed() {
      return !added.isEmpty() || !removed.isEmpty();
    }
  }

  private final RoleBindingRepository bindings;
  private final RealmRoles realm;
  private final Clock clock;

  public RealmRoleSync(RoleBindingRepository bindings, RealmRoles realm, Clock clock) {
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.realm = Objects.requireNonNull(realm, "realm");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Gọi trong giao dịch có {@code app.tenant_id} của {@code tenantId}. */
  public Result sync(TenantId tenantId, UUID personId) {
    Set<String> desired = new TreeSet<>();
    for (RoleBinding b : bindings.effectiveOf(tenantId, personId, clock.instant())) {
      desired.add(b.roleCode());
    }
    Set<String> current = new TreeSet<>();
    for (String r : realm.rolesOf(personId)) {
      if (RoleBinding.ROLES.contains(r) || CustomRole.isCustomCode(r)) {
        current.add(r);
      }
    }
    Set<String> added = new TreeSet<>(desired);
    added.removeAll(current);
    Set<String> removed = new TreeSet<>(current);
    removed.removeAll(desired);
    for (String r : added) {
      realm.add(personId, r);
    }
    for (String r : removed) {
      realm.remove(personId, r);
    }
    return new Result(added, removed);
  }

  /** Đối soát mọi người có bản ghi vai trò của tenant; trả số người phải sửa. */
  public int reconcile(TenantId tenantId, int pageSize) {
    int fixed = 0;
    UUID after = null;
    while (true) {
      List<UUID> page = bindings.persons(tenantId, after, pageSize);
      for (UUID p : page) {
        if (sync(tenantId, p).changed()) {
          fixed++;
        }
      }
      if (page.size() < pageSize) {
        return fixed;
      }
      after = page.get(page.size() - 1);
    }
  }
}
