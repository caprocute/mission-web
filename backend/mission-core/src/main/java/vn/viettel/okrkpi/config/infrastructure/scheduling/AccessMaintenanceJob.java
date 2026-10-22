package vn.viettel.okrkpi.config.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.application.DelegationService;
import vn.viettel.okrkpi.config.application.RealmRoleSync;
import vn.viettel.okrkpi.config.application.RoleBindingService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tác vụ định kỳ của quản trị vai trò và uỷ quyền (CORE-08-16): phát EVT-27 hết hạn cho vai trò, uỷ
 * quyền quá {@code valid_to} (đánh dấu {@code expiry_emitted_at} nên chạy lại, nhiều pod vẫn chỉ
 * phát một lần), và đối soát realm role Keycloak với {@code config.role_binding}. Đi lần lượt từng
 * tenant của cell vì RLS; lỗi của một tenant không chặn tenant khác.
 */
public class AccessMaintenanceJob {

  private static final Logger log = LoggerFactory.getLogger(AccessMaintenanceJob.class);
  private static final int BATCH = 200;

  private final RoleBindingService bindings;
  private final DelegationService delegations;
  private final ObjectProvider<RealmRoleSync> sync;
  private final ObjectProvider<TenantDirectory> directory;
  private final TransactionTemplate tx;
  private final String cell;

  public AccessMaintenanceJob(
      RoleBindingService bindings,
      DelegationService delegations,
      ObjectProvider<RealmRoleSync> sync,
      ObjectProvider<TenantDirectory> directory,
      PlatformTransactionManager tm,
      String cell) {
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.delegations = Objects.requireNonNull(delegations, "delegations");
    this.sync = Objects.requireNonNull(sync, "sync");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.tx = new TransactionTemplate(tm);
    this.cell = cell == null ? "" : cell;
  }

  @Scheduled(cron = "${mission.config.access.expiry-cron:0 */5 * * * *}", zone = "Asia/Ho_Chi_Minh")
  public void expire() {
    int total = expireFor(tenants());
    if (total > 0) {
      log.info("Đã phát sự kiện hết hạn cho {} vai trò và uỷ quyền", total);
    }
  }

  @Scheduled(
      cron = "${mission.config.access.reconcile-cron:0 30 2 * * *}",
      zone = "Asia/Ho_Chi_Minh")
  public void reconcile() {
    int fixed = reconcileFor(tenants());
    if (fixed > 0) {
      log.warn("Đối soát realm role đã sửa lệch cho {} người", fixed);
    }
  }

  public int expireFor(List<TenantId> tenants) {
    int total = 0;
    for (TenantId tenant : tenants) {
      try {
        Integer n =
            TenantContext.call(
                tenant,
                () ->
                    tx.execute(
                        s ->
                            bindings.expireDue(tenant, PeriodReopenExpiryJob.SYSTEM_ACTOR, BATCH)
                                + delegations.expireDue(tenant, BATCH)));
        total += n == null ? 0 : n;
      } catch (Exception e) {
        log.warn("Tác vụ hết hạn vai trò lỗi ở tenant {}", tenant, e);
      }
    }
    return total;
  }

  public int reconcileFor(List<TenantId> tenants) {
    RealmRoleSync s = sync.getIfAvailable();
    if (s == null) {
      return 0;
    }
    int fixed = 0;
    for (TenantId tenant : tenants) {
      try {
        Integer n = TenantContext.call(tenant, () -> tx.execute(t -> s.reconcile(tenant, BATCH)));
        fixed += n == null ? 0 : n;
      } catch (Exception e) {
        log.warn("Đối soát realm role lỗi ở tenant {}", tenant, e);
      }
    }
    return fixed;
  }

  private List<TenantId> tenants() {
    TenantDirectory tenants = directory.getIfAvailable();
    if (tenants == null) {
      return List.of();
    }
    return tenants.snapshotByCode().values().stream()
        .filter(r -> cell.isBlank() || cell.equals(r.cellCode()))
        .map(TenantCellRoute::tenantId)
        .distinct()
        .map(TenantId::of)
        .toList();
  }
}
