package vn.viettel.okrkpi.org.application.port;

/** Không gửi được lệnh tới integration-gateway; thông điệp là lý do tiếng Việt cho MSG-01-14, không chứa dữ liệu C2. */
public class IntegrationUnavailableException extends IllegalStateException {

  public IntegrationUnavailableException(String reason, Throwable cause) {
    super(reason, cause);
  }
}
