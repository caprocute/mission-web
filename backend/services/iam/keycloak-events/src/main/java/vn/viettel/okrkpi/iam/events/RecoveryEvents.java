package vn.viettel.okrkpi.iam.events;

import java.time.Instant;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;

/**
 * DV-02-08 · Phát sự kiện của mã khôi phục qua hàng đợi bền của event listener DV-02-05: {@code iam.auth.event.v1}
 * cho mọi lần cấp, dùng, dùng sai, thu hồi; {@code sys.audit.v1} cho sổ cái; EVT-21 báo cán bộ khi có mã được cấp.
 * Không mang mã, băm hay lý do dạng tự do.
 */
final class RecoveryEvents {

  static final String ISSUED = "MFA_RECOVERY_CODE_ISSUED";
  static final String USED = "MFA_RECOVERY_CODE_USED";
  static final String USE_ERROR = "MFA_RECOVERY_CODE_USE_ERROR";
  static final String REVOKED = "MFA_RECOVERY_CODE_REVOKED";
  private static final Logger LOG = Logger.getLogger(RecoveryEvents.class);

  private RecoveryEvents() {
  }

  static void issued(KeycloakSession session, UserModel target, UUID admin, String method, String traceparent) {
    publish(session, target, admin, traceparent, ISSUED, "CAP_MA_KHOI_PHUC_MFA", "THANH_CONG", method, true);
  }

  static void revoked(KeycloakSession session, UserModel target, UUID admin, String traceparent) {
    publish(session, target, admin, traceparent, REVOKED, "THU_HOI_MA_KHOI_PHUC_MFA", "THANH_CONG", null, false);
  }

  static void used(KeycloakSession session, UserModel user, boolean ok) {
    publish(session, user, uuid(user.getId()), null, ok ? USED : USE_ERROR, "DUNG_MA_KHOI_PHUC_MFA",
        ok ? "THANH_CONG" : "TU_CHOI", null, false);
  }

  private static void publish(KeycloakSession session, UserModel target, UUID actor, String traceparent, String type,
      String action, String outcome, String method, boolean notify) {
    DurableQueue queue = MissionEventListenerProviderFactory.queue(session);
    if (queue == null) {
      LOG.warn("Chưa nạp mission-events: không phát được sự kiện mã khôi phục");
      return;
    }
    UUID tenant = uuid(target.getFirstAttribute(MissionEventListenerProvider.TENANT_ATTRIBUTE));
    UUID person = uuid(target.getId());
    EventMapper.Context c = new EventMapper.Context(tenant == null ? EventMapper.UNKNOWN_TENANT : tenant,
        Instant.now(), actor, traceparent);
    EventMapper.authEvent(c, type, person, null, null, USE_ERROR.equals(type) ? "invalid_user_credentials" : null)
        .ifPresent(queue::offer);
    queue.offer(EventMapper.recoveryAudit(c, action, person, outcome, method,
        USED.equals(type) || USE_ERROR.equals(type) ? null : "tenant_admin"));
    if (notify) {
      queue.offer(EventMapper.recoveryIssuedNotice(c, person));
    }
  }

  static UUID uuid(String v) {
    try {
      return v == null ? null : UUID.fromString(v);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
