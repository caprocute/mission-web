package vn.viettel.okrkpi.policy.infrastructure.web;

import java.util.Optional;
import java.util.UUID;

/**
 * Bộ trích người gọi lệnh ghi Policy Pack; không có bean thì dùng chủ thể của {@code
 * AuthzSubjectResolver} (JWT). Bean riêng dùng cho kiểm thử hoặc khi cell đặt bộ trích khác.
 */
public interface PolicyCaller {

  Optional<UUID> currentUserId();
}
