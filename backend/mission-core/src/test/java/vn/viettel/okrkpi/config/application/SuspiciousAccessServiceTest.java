package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.application.port.IdentityAdmin;
import vn.viettel.okrkpi.config.application.port.SecurityAlerts;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class SuspiciousAccessServiceTest {

  private static final TenantId TENANT = TenantId.parse("0192f000-0000-7000-8000-0000000000a1");
  private static final ActorId ME = ActorId.of(UUID.randomUUID());
  private static final UUID ADMIN = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-10-22T01:00:00Z");

  private final List<String> calls = new ArrayList<>();
  private final List<AuditEvent> audits = new ArrayList<>();
  private final List<List<UUID>> notified = new ArrayList<>();
  private boolean failIdentity;

  private final IdentityAdmin identity =
      new IdentityAdmin() {
        @Override
        public int revokeAllSessions(UUID userId) {
          if (failIdentity) {
            throw new IllegalStateException("keycloak");
          }
          calls.add("revoke:" + userId);
          return 3;
        }

        @Override
        public void requirePasswordChange(UUID userId) {
          calls.add("password:" + userId);
        }

        @Override
        public List<UUID> tenantAdmins(TenantId tenant) {
          return List.of(ADMIN, ME.value(), ADMIN);
        }

        @Override
        public String passwordChangeUrl() {
          return "/realms/mission/login-actions/required-action?execution=UPDATE_PASSWORD";
        }
      };

  private final SecurityAlerts alerts =
      (tenant, reportId, reporter, admins, at) -> {
        calls.add("alert");
        notified.add(admins);
        assertThat(at).isEqualTo(NOW);
      };

  private SuspiciousAccessService service() {
    return new SuspiciousAccessService(
        identity, alerts, audits::add, new TransactionTemplate(new NoopTm()), Clock.fixed(NOW));
  }

  @Test
  void thuHoiBuocDoiMatKhauRoiBaoQuanTriVaGhiSoCai() {
    var r = service().report(TENANT, ME, "su-kien-1");

    assertThat(r.sessionsRevoked()).isEqualTo(3);
    assertThat(r.passwordChangeUrl()).contains("UPDATE_PASSWORD");
    assertThat(calls).containsExactly("revoke:" + ME.value(), "password:" + ME.value(), "alert");
    assertThat(notified).containsExactly(List.of(ADMIN));
    assertThat(audits)
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.type()).isEqualTo(AuditEventType.ADMIN_ACTION);
              assertThat(a.objectId()).isEqualTo(r.reportId().toString());
              assertThat(a.attributes()).containsEntry("sessionsRevoked", "3");
            });
  }

  @Test
  void keycloakLoiThiKhongPhatCanhBaoVaKhongGhiSoCai() {
    failIdentity = true;
    assertThatThrownBy(() -> service().report(TENANT, ME, null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(notified).isEmpty();
    assertThat(audits).isEmpty();
  }

  private static final class NoopTm extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition d) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
