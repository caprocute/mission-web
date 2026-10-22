package vn.viettel.okrkpi.policy.infrastructure.web;

import java.util.Optional;
import java.util.UUID;

/**
 * Bộ trích người gọi lệnh ghi Policy Pack; không có bean thì dùng chủ thể của {@code
 * AuthzSubjectResolver} (JWT). Bean riêng dùng cho kiểm thử hoặc khi cell đặt bộ trích khác.
 */
public interface PolicyCaller {

  Optional<UUID> currentUserId();

  /** Mức xác thực của phiên ({@code acr}) để kiểm bước lên khi ký phê duyệt (CORE-05-09). */
  default Optional<String> acr() {
    return Optional.empty();
  }

  /** Thời điểm xác thực ({@code auth_time}, giây epoch). */
  default Optional<Long> authTime() {
    return Optional.empty();
  }
}
