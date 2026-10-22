package vn.viettel.okrkpi.config.infrastructure.listener;

import java.util.Objects;
import vn.viettel.okrkpi.config.application.RealmRoleSync;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.TransientInboxException;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.config.v1.VaiTroDaThayDoi;

/**
 * Consumer EVT-27 {@code config.access.changed.v1} của MOD-08 (CORE-08-16): tính lại realm role
 * Keycloak của người được cấp từ {@code config.role_binding}. Đi qua inbox của schema config nên
 * giao lại không làm gì thêm; Keycloak chưa sẵn sàng thì thử lại rồi vào thư chết. Sự kiện uỷ quyền
 * bỏ qua vì uỷ quyền không đổi realm role (OPA đọc người nhận qua {@code delegate_ids}).
 */
public class RealmRoleSyncListener {

  /** Định danh consumer trong {@code config.inbox_processed}. */
  public static final String CONSUMER = "config.realm-role-sync";

  private final RealmRoleSync sync;

  public RealmRoleSyncListener(RealmRoleSync sync) {
    this.sync = Objects.requireNonNull(sync, "sync");
  }

  public void on(VaiTroDaThayDoi event, InboxEnvelope<?> envelope) {
    if (event.getRoleCode() == null) {
      return;
    }
    try {
      TenantContext.run(
          envelope.tenantId(), () -> sync.sync(envelope.tenantId(), event.getPersonId()));
    } catch (DependencyUnavailableException e) {
      throw new TransientInboxException("Keycloak chưa nhận đồng bộ vai trò", e);
    }
  }
}
