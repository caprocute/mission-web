package vn.viettel.okrkpi.oversight.application.port;

/** Chỉ mục kiểm toán tạm thời không truy cập được (503 hoặc thử lại ở consumer). */
public class AuditIndexUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AuditIndexUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
