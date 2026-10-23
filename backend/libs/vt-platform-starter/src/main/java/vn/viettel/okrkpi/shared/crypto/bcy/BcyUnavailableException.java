package vn.viettel.okrkpi.shared.crypto.bcy;

import java.io.Serial;

/**
 * Chế độ mật mã {@code bcy} không dùng được (thiếu thư viện, thiếu thiết bị, thuật toán ngoài danh
 * sách được phép). Ném lúc khởi động để dịch vụ không bao giờ sẵn sàng với mật mã tiêu chuẩn thay
 * thế; thông điệp tiếng Việt, không chứa mã PIN hay khoá.
 */
public class BcyUnavailableException extends IllegalStateException {

  @Serial private static final long serialVersionUID = 1L;

  public BcyUnavailableException(String message) {
    super(message);
  }

  public BcyUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
