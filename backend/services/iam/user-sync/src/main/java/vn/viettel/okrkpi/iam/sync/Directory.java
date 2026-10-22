package vn.viettel.okrkpi.iam.sync;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Thao tác trên người dùng Keycloak mà bộ đồng bộ cần; người dùng biểu diễn bằng UserRepresentation dạng Map. */
public interface Directory {

  Optional<Map<String, Object>> findUser(UUID id);

  /** Tạo người dùng giữ nguyên {@code id} (để {@code sub} bằng {@code org.person.id}). */
  void createUser(Map<String, Object> user);

  void updateUser(UUID id, Map<String, Object> user);

  /** Người dùng có thuộc tính {@code name} bằng {@code value} (đủ biểu diễn, mọi trang). */
  List<Map<String, Object>> findUsersByAttribute(String name, String value);

  /** Đăng xuất mọi phiên và refresh token. */
  void logout(UUID id);

  /** Organization đại diện tenant; rỗng khi tenant chưa được khởi tạo ở cell. */
  Optional<String> organizationOf(UUID tenant);

  /** Thêm thành viên; đã là thành viên thì không làm gì. */
  void addMember(String organizationId, UUID userId);

  /** Lỗi tạm thời (mạng, Keycloak quá tải, tenant chưa có Organization): thử lại rồi vào thư chết. */
  class TransientException extends RuntimeException {
    public TransientException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Lỗi không thử lại được (dữ liệu mâu thuẫn): vào thẳng thư chết. */
  class PermanentException extends RuntimeException {
    public PermanentException(String message) {
      super(message);
    }
  }
}
