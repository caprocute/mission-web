package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;

/**
 * Người gọi của các API MOD-06: bean {@link DossierCaller} nếu có, nếu không thì chủ thể của {@code
 * AuthzSubjectResolver} (JWT).
 */
final class DossierCallers {

  private final ObjectProvider<DossierCaller> callers;
  private final ObjectProvider<AuthzSubjectResolver> subjects;

  DossierCallers(
      ObjectProvider<DossierCaller> callers, ObjectProvider<AuthzSubjectResolver> subjects) {
    this.callers = Objects.requireNonNull(callers, "callers");
    this.subjects = Objects.requireNonNull(subjects, "subjects");
  }

  /** Người dùng đã xác thực; thiếu thì 401. */
  UUID actor() {
    return caller(DossierCaller::currentUserId, OpaInput.Subject::userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }

  /** Đơn vị của phân công đang chọn. */
  Optional<UUID> unit() {
    return caller(DossierCaller::currentUnitId, OpaInput.Subject::unitId);
  }

  private Optional<UUID> caller(
      Function<DossierCaller, Optional<UUID>> fromCaller,
      Function<OpaInput.Subject, String> fromSubject) {
    DossierCaller c = callers.getIfAvailable();
    if (c != null) {
      return fromCaller.apply(c);
    }
    AuthzSubjectResolver resolver = subjects.getIfAvailable();
    OpaInput.Subject subject = resolver == null ? null : resolver.currentSubject();
    String raw = subject == null ? null : fromSubject.apply(subject);
    if (raw == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
