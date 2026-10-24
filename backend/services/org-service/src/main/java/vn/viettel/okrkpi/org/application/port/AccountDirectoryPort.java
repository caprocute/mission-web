package vn.viettel.okrkpi.org.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Tài khoản đăng nhập ở nhà cung cấp định danh (Keycloak) cho quản trị người dùng trong ứng dụng (DV-04-09, BS-04
 * ADR-34 mục 2). Mọi thao tác đồng bộ trong yêu cầu nên có hiệu lực ngay; lỗi kết nối ném
 * {@link IntegrationUnavailableException}.
 */
public interface AccountDirectoryPort {

  /** Thông tin tài khoản đọc từ nhà cung cấp định danh. */
  record DirectoryAccount(UUID id, String username, String email, boolean enabled, long createdTimestamp) {
  }

  /** Tài khoản mới; mật khẩu tạm buộc đổi khi đăng nhập lần đầu (BR-01-20). */
  record NewAccount(String username, String fullName, String email, UUID tenantId, UUID unitId,
      String temporaryPassword) {
  }

  /**
   * Tạo tài khoản kèm vai trò người dùng cơ bản, trả id do nhà cung cấp cấp (dùng làm mã cán bộ). Tên đăng nhập đã có
   * thì ném {@link UsernameTakenException}.
   */
  UUID create(NewAccount account);

  /** Xoá tài khoản vừa tạo khi phần ghi CSDL thất bại (bù trừ). */
  void delete(UUID id);

  Optional<DirectoryAccount> find(UUID id);

  /** Đổi họ tên hoặc thư điện tử (null là giữ nguyên). */
  void updateProfile(UUID id, String fullName, String email);

  /** Bật hoặc tắt đăng nhập; tắt thì thu hồi mọi phiên (BR-01-21). */
  void setEnabled(UUID id, boolean enabled);

  /** Đặt mật khẩu tạm buộc đổi ở lần đăng nhập kế tiếp và thu hồi mọi phiên. */
  void resetPassword(UUID id, String temporaryPassword);

  /** Tên đăng nhập đã được dùng (MSG-01-20). */
  final class UsernameTakenException extends RuntimeException {

    public UsernameTakenException() {
      super("Tên đăng nhập này đã được sử dụng.");
    }
  }
}
