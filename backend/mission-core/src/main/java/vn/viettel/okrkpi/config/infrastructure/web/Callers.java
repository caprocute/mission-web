package vn.viettel.okrkpi.config.infrastructure.web;

import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;

/**
 * Người gọi của lệnh ghi config: bộ trích {@link PeriodCaller} nếu có (kiểm thử), ngược lại chủ thể
 * của {@link AuthzSubjectResolver}; không có thì 401.
 */
final class Callers {

  private final ObjectProvider<AuthzSubjectResolver> subjects;
  private final ObjectProvider<PeriodCaller> custom;

  Callers(ObjectProvider<AuthzSubjectResolver> subjects, ObjectProvider<PeriodCaller> custom) {
    this.subjects = Objects.requireNonNull(subjects, "subjects");
    this.custom = Objects.requireNonNull(custom, "custom");
  }

  UUID current() {
    PeriodCaller caller = custom.getIfAvailable();
    if (caller != null) {
      return caller.currentUserId().orElseThrow(Callers::unauthorized);
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    if (subject == null || subject.userId() == null || subject.userId().isBlank()) {
      throw unauthorized();
    }
    try {
      return UUID.fromString(subject.userId());
    } catch (IllegalArgumentException e) {
      throw unauthorized();
    }
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
  }
}
