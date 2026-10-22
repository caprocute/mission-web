package vn.viettel.okrkpi.oversight.infrastructure.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Người gọi API giám sát. Khi không có bean nào, lớp REST lấy chủ thể từ {@code
 * AuthzSubjectResolver} (JWT); bean riêng dùng cho kiểm thử hoặc khi cell đặt bộ trích khác.
 */
public interface OversightCaller {

  Optional<UUID> currentUserId();

  /** Vai trò trong ngữ cảnh phân công đang chọn. */
  List<String> roles();

  /** Mức xác thực ({@code acr}) của phiên. */
  Optional<String> acr();
}
