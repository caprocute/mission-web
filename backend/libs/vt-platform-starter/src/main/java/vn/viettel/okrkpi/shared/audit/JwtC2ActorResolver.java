package vn.viettel.okrkpi.shared.audit;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import vn.viettel.okrkpi.shared.kernel.ActorId;

/**
 * Lấy người xem từ JWT Keycloak của yêu cầu: {@code sub} là mã người dùng; phân công và vai trò
 * đang chọn (BR-00-08) đọc từ claim {@code active_assignment_id} và {@code active_role} (suy luận:
 * TL chưa chốt tên claim, cùng cách đặt tên với {@code active_unit_id} của NEN-020). Token máy
 * (không có {@code sub} dạng UUID) hoặc không có xác thực thì coi là tác vụ hệ thống.
 */
public class JwtC2ActorResolver implements C2ActorResolver {

  static final String ASSIGNMENT_CLAIM = "active_assignment_id";
  static final String ROLE_CLAIM = "active_role";

  @Override
  public C2Actor current() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
      return C2Actor.SYSTEM;
    }
    UUID subject = uuidOrNull(jwt.getSubject());
    if (subject == null) {
      return C2Actor.SYSTEM;
    }
    return new C2Actor(
        new ActorId(subject),
        uuidOrNull(jwt.getClaimAsString(ASSIGNMENT_CLAIM)),
        jwt.getClaimAsString(ROLE_CLAIM),
        false);
  }

  private static UUID uuidOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
