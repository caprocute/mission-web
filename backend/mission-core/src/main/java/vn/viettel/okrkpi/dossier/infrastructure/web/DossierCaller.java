package vn.viettel.okrkpi.dossier.infrastructure.web;

import java.util.Optional;
import java.util.UUID;

/**
 * Người gọi API của MOD-06 (người dùng và đơn vị của phân công đang chọn); không có bean thì dùng
 * chủ thể của {@code AuthzSubjectResolver} (JWT). Bean riêng dùng cho kiểm thử.
 */
public interface DossierCaller {

  Optional<UUID> currentUserId();

  Optional<UUID> currentUnitId();
}
