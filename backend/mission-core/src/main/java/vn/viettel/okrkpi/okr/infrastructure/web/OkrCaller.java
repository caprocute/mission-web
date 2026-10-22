package vn.viettel.okrkpi.okr.infrastructure.web;

import java.util.Optional;
import java.util.UUID;

/**
 * Người gọi API của MOD-01 (người dùng và đơn vị của phân công); không có bean thì dùng chủ thể của
 * {@code AuthzSubjectResolver} (JWT). Bean riêng dùng cho kiểm thử hoặc khi cell đặt bộ trích khác.
 */
public interface OkrCaller {

  Optional<UUID> currentUserId();

  Optional<UUID> currentUnitId();
}
