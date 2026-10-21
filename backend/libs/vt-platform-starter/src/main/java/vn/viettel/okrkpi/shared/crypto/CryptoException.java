package vn.viettel.okrkpi.shared.crypto;

import java.io.Serial;

/**
 * Ngoại lệ mật mã cho các thao tác mã hoá/giải mã trường C2, quản lý DEK và gọi OpenBao Transit.
 *
 * <p>Thông điệp ngoại lệ tuyệt đối không chứa bản rõ C2 hoặc mảng byte khoá (TL-01 "Che dữ liệu
 * nhạy cảm").
 */
public class CryptoException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public CryptoException(String message) {
    super(message);
  }

  public CryptoException(String message, Throwable cause) {
    super(message, cause);
  }
}
