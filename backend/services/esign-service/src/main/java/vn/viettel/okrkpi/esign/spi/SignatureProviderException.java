package vn.viettel.okrkpi.esign.spi;

/**
 * Lỗi nhà cung cấp chữ ký. {@code permanent} là lỗi không thể khắc phục bằng thử lại (chứng thư
 * không hợp lệ, tài liệu không khớp băm, nhà cung cấp từ chối tham số): yêu cầu chuyển {@code LOI}.
 * Lỗi tạm thời (mạng, 5xx, ngắt mạch) để worker thử lại ở vòng sau tới khi hết hạn.
 */
public class SignatureProviderException extends RuntimeException {

  private final boolean permanent;

  public SignatureProviderException(String message, boolean permanent, Throwable cause) {
    super(message, cause);
    this.permanent = permanent;
  }

  public SignatureProviderException(String message, boolean permanent) {
    this(message, permanent, null);
  }

  public boolean permanent() {
    return permanent;
  }
}
