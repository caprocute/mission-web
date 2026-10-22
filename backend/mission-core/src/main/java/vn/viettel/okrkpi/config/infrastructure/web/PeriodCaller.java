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
}
