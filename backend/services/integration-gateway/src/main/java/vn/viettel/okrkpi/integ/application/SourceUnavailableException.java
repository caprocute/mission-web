package vn.viettel.okrkpi.integ.application;

import java.util.Objects;

/**
 * Hệ nguồn không trả được số liệu trong thời hạn (DV-13-08): MOD-01 nhận lỗi này qua cổng nội bộ và
 * trả {@code 502 OKR-502-SOURCE-UNAVAILABLE} để người dùng nhập tay. Thông điệp không chứa giá trị.
 */
public class SourceUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Lý do ghi vào chỉ số và nhật ký. */
  public enum Reason {
    TIMEOUT,
    CIRCUIT_OPEN,
    ERROR
  }

  private final Reason reason;

  public SourceUnavailableException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
