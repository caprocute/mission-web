package vn.viettel.okrkpi.config.infrastructure.scheduling;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.config.application.CustomRoleService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantCellRoute;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Đẩy lại định nghĩa vai trò tuỳ chỉnh của từng tenant sang OPA khi khởi động và định kỳ
 * (CORE-08-18, BS-04): OPA khởi động lại hay lần đẩy sau giao dịch bị lỗi thì trễ tối đa một chu
 * kỳ. Lỗi của một tenant không chặn tenant khác.
 */
public class CustomRolePublishJob {

  private static final Logger log = LoggerFactory.getLogger(CustomRolePublishJob.class);

  private final CustomRoleService service;
  private final ObjectProvider<TenantDirectory> directory;
  private final String cell;

  public CustomRolePublishJob(
      CustomRoleService service, ObjectProvider<TenantDirectory> directory, String cell) {
    this.service = Objects.requireNonNull(service, "service");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.cell = cell == null ? "" : cell;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    publish();
  }

  @Scheduled(fixedDelayString = "${mission.config.custom-roles.publish-interval:PT1M}")
  public void publish() {
    publishFor(tenants());
  }

  public int publishFor(List<TenantId> tenants) {
    int ok = 0;
    for (TenantId tenant : tenants) {
      try {
        TenantContext.run(tenant, () -> service.publishAll(tenant));
        ok++;
      } catch (Exception e) {
        log.warn(
            "Chưa đẩy được vai trò tuỳ chỉnh của tenant {} sang OPA: {}", tenant, e.getMessage());
      }
    }
    return ok;
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
