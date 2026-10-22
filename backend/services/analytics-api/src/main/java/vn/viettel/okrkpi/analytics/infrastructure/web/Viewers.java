package vn.viettel.okrkpi.analytics.infrastructure.web;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Người xem từ token: tenant từ {@link TenantContext} (TenantContextFilter đọc claim {@code
 * tenant_id}), người dùng từ {@code sub}, đơn vị công tác từ {@code unit_id} hoặc {@code
 * active_unit_id} như {@code AuthzSubjectResolver}.
 */
final class Viewers {

  private Viewers() {}

  static Viewer current() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
      throw new AccessDeniedException("viewer");
    }
    UUID user = uuid(jwt.getSubject());
    if (user == null) {
      throw new AccessDeniedException("viewer");
    }
    String unit = jwt.getClaimAsString("unit_id");
    if (unit == null) {
      unit = jwt.getClaimAsString("active_unit_id");
    }
    return new Viewer(TenantContext.require().value(), user, uuid(unit));
  }

  private static UUID uuid(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
