package vn.viettel.okrkpi.org.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Chủ thể gọi API đã xác thực: tenant, mã người dùng ({@code sub}), vai trò realm, mức xác thực và ngữ cảnh đơn vị
 * lấy từ token Keycloak; {@code traceparent} để gắn vào sự kiện.
 */
public record Actor(
    UUID tenantId,
    UUID actorId,
    Set<String> roles,
    String acr,
    Instant authTime,
    UUID unitId,
    String traceparent) {

  public Actor {
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }
}
