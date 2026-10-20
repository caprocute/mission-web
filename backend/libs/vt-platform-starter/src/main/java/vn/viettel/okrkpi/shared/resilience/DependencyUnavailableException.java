package vn.viettel.okrkpi.shared.resilience;

import java.io.Serial;
import java.time.Duration;
import java.util.Objects;

/**
 * Phụ thuộc ngoài không dùng được (mạch đang mở, bulkhead đầy, quá thời gian, lỗi kết nối sau khi
 * đã thử lại): bộ xử lý lỗi chung trả 503 kèm {@code Retry-After} ({@code common.yaml} response
 * ServiceUnavailable). Thông điệp chỉ mang tên instance, không mang chi tiết kỹ thuật.
 */
public class DependencyUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Lý do, chỉ để ghi nhật ký và đo lường. */
  public enum Reason {
    CIRCUIT_OPEN,
    BULKHEAD_FULL,
    TIMEOUT,
    IO_ERROR
  }

  private final String dependency;
  private final Reason reason;
  private final Duration retryAfter;

  public DependencyUnavailableException(
      String dependency, Reason reason, Duration retryAfter, Throwable cause) {
    super("Phụ thuộc " + dependency + " không dùng được: " + reason, cause);
    this.dependency = Objects.requireNonNull(dependency, "dependency");
    this.reason = Objects.requireNonNull(reason, "reason");
    this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
  }

  public String dependency() {
    return dependency;
  }

  public Reason reason() {
    return reason;
  }

  public Duration retryAfter() {
    return retryAfter;
  }

  /** Số giây cho header {@code Retry-After}, làm tròn lên, tối thiểu 1. */
  public long retryAfterSeconds() {
    long ms = retryAfter.toMillis();
    return Math.max(1, (ms + 999) / 1000);
  }
}
