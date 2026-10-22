package vn.viettel.okrkpi.config.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.application.port.IdentityAdmin;
import vn.viettel.okrkpi.config.application.port.SecurityAlerts;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * DV-02-05 · QT-00 · BR-00-06: người dùng báo truy cập bất thường từ cảnh báo thiết bị mới
 * (MSG-00-08). Thu hồi mọi phiên trên Keycloak (Keycloak phát {@code ADMIN_LOGOUT_USER} lên {@code
 * iam.auth.event.v1}, bộ ghi danh sách thu hồi chặn token cũ ở cổng), buộc đổi mật khẩu, rồi trong
 * một giao dịch của MOD-08 ghi EVT-21 cho quản trị tenant và ghi sổ cái kiểm toán.
 *
 * <p>Gọi Keycloak trước giao dịch: hai thao tác đều lặp lại được, nên lỗi ở bước ghi sau chỉ cần
 * người dùng bấm lại. Ghi chú tự do của người dùng (C2) không được ghi vào sự kiện hay sổ cái.
 */
public class SuspiciousAccessService {

  static final String ACTION = "config.security.report-suspicious";

  private final IdentityAdmin identity;
  private final SecurityAlerts alerts;
  private final AuditLogger audit;
  private final TransactionTemplate tx;
  private final Clock clock;

  public SuspiciousAccessService(
      IdentityAdmin identity,
      SecurityAlerts alerts,
      AuditLogger audit,
      TransactionTemplate tx,
      Clock clock) {
    this.identity = Objects.requireNonNull(identity, "identity");
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Kết quả trả cho người dùng. */
  public record Result(UUID reportId, int sessionsRevoked, String passwordChangeUrl) {}

  public Result report(TenantId tenant, ActorId reporter, String loginEventId) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(reporter, "reporter");
    Instant now = clock.instant();
    UUID reportId = Ids.newUuidV7();
    UUID user = reporter.value();
    int sessions = identity.revokeAllSessions(user);
    identity.requirePasswordChange(user);
    List<UUID> admins =
        identity.tenantAdmins(tenant).stream().filter(a -> !a.equals(user)).distinct().toList();
    tx.executeWithoutResult(
        status -> {
          alerts.suspiciousAccessReported(tenant, reportId, reporter, admins, now);
          audit.record(
              AuditEvent.builder(AuditEventType.ADMIN_ACTION, reporter, ACTION)
                  .outcome(AuditOutcome.SUCCESS)
                  .tenant(tenant)
                  .object("suspicious_access_report", reportId.toString())
                  .attribute("loginEventId", loginEventId == null ? "" : loginEventId)
                  .attribute("sessionsRevoked", Integer.toString(sessions))
                  .attribute("adminsNotified", Integer.toString(admins.size()))
                  .build());
        });
    return new Result(reportId, sessions, identity.passwordChangeUrl());
  }
}
