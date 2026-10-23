package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

/** HSM không phục vụ được lệnh ký (hết phiên trong thời gian chờ, lỗi thư viện PKCS#11). */
public class HsmUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public HsmUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
