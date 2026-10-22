package vn.viettel.okrkpi.analytics.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Người xem đã xác thực: tenant, người dùng và đơn vị công tác lấy từ token Keycloak, không bao giờ
 * từ tham số của yêu cầu (BR-16-01).
 */
public record Viewer(UUID tenant, UUID userId, UUID unitId) {

  public Viewer {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(userId, "userId");
  }
}
