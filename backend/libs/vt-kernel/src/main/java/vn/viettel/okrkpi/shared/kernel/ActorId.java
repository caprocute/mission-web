package vn.viettel.okrkpi.shared.kernel;

import java.util.Objects;
import java.util.UUID;

/**
 * Định danh người thực hiện thao tác (tài khoản người dùng hoặc client nội bộ). Giá trị là UUID của
 * tài khoản; vai trò AC đi kèm lấy từ token, không lưu trong kiểu này.
 *
 * @param value giá trị UUID, không rỗng
 */
public record ActorId(UUID value) {

  public ActorId {
    Objects.requireNonNull(value, "ActorId.value");
  }

  public static ActorId of(UUID value) {
    return new ActorId(value);
  }

  /** Đọc từ chuỗi UUID (ví dụ claim {@code sub} của token Keycloak). */
  public static ActorId parse(String value) {
    return new ActorId(Ids.parse(value));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
