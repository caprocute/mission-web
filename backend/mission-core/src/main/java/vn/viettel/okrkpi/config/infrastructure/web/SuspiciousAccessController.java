package vn.viettel.okrkpi.config.infrastructure.web;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.application.SuspiciousAccessService;
import vn.viettel.okrkpi.contract.config.model.SuspiciousAccessReport;
import vn.viettel.okrkpi.contract.config.model.SuspiciousAccessResult;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code POST /api/v1/config/security/report-suspicious} ({@code reportSuspiciousAccess}, {@code
 * config.yaml}). Mọi người dùng đã đăng nhập tự báo cho chính tài khoản của mình (phạm vi B), nên
 * người bị thu hồi luôn là chủ token. Bean do {@code SuspiciousAccessConfiguration} khai báo, chỉ
 * có khi cell đã cấu hình Admin API Keycloak.
 */
@ResponseBody
@RequestMapping("/api/v1/config/security")
public class SuspiciousAccessController {

  private final SuspiciousAccessService service;
  private final ObjectProvider<AuthzSubjectResolver> subjects;

  public SuspiciousAccessController(
      SuspiciousAccessService service, ObjectProvider<AuthzSubjectResolver> subjects) {
    this.service = Objects.requireNonNull(service, "service");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
  }

  @Idempotent
  @PostMapping("/report-suspicious")
  public SuspiciousAccessResult reportSuspiciousAccess(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody SuspiciousAccessReport report) {
    SuspiciousAccessService.Result r =
        service.report(TenantContext.require(), caller(), report.getLoginEventId());
    SuspiciousAccessResult body =
        new SuspiciousAccessResult(r.reportId(), r.sessionsRevoked(), true);
    body.setPasswordChangeUrl(r.passwordChangeUrl());
    return body;
  }

  private ActorId caller() {
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
    }
    return ActorId.parse(subject.userId());
  }
}
