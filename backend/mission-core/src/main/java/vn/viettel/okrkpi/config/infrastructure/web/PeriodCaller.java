package vn.viettel.okrkpi.config.infrastructure.web;

import java.util.Optional;
import java.util.UUID;

/**
 * Người gọi API kỳ đánh giá. Khi không có bean nào, {@link PeriodController} lấy chủ thể từ {@code
 * AuthzSubjectResolver} (JWT); bean riêng dùng cho kiểm thử hoặc khi cell đặt bộ trích khác.
 */
@FunctionalInterface
public interface PeriodCaller {

  Optional<UUID> currentUserId();

  /** Mức xác thực ({@code acr}) của phiên, để kiểm MFA bước lên (BR-08-02). */
  default Optional<String> acr() {
    return Optional.empty();
  }

  /** Thời điểm xác thực ({@code auth_time}, giây epoch). */
  default Optional<Long> authTime() {
    return Optional.empty();
  }
}
