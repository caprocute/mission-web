package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thao tác quản trị tài khoản trên nhà cung cấp định danh (Keycloak, SVC-02) mà MOD-08 cần. */
public interface IdentityAdmin {

  /** Thu hồi mọi phiên và refresh token của người dùng; trả số phiên đang có trước khi thu hồi. */
  int revokeAllSessions(UUID userId);

  /** Đặt hành động bắt buộc đổi mật khẩu ở lần đăng nhập kế tiếp. */
  void requirePasswordChange(UUID userId);

  /** Người dùng giữ vai trò quản trị tenant của {@code tenant}. */
  List<UUID> tenantAdmins(TenantId tenant);

  /** Đường dẫn luồng đổi mật khẩu của nhà cung cấp định danh. */
  String passwordChangeUrl();
}
