package vn.viettel.okrkpi.workflow.domain.saga;

import java.time.Duration;
import java.util.Objects;

/**
 * Chính sách thử lại có lùi dần của một bước saga: lần thử thứ {@code n} (đếm từ 1) chờ {@code
 * initialBackoff * multiplier^(n-1)}, không quá {@code maxBackoff}.
 */
public record RetryPolicy(
    int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {

  public RetryPolicy {
    Objects.requireNonNull(initialBackoff, "initialBackoff");
    Objects.requireNonNull(maxBackoff, "maxBackoff");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts phải >= 1");
    }
    if (multiplier < 1.0) {
      throw new IllegalArgumentException("multiplier phải >= 1");
    }
    if (initialBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
      throw new IllegalArgumentException("Khoảng chờ không hợp lệ");
    }
  }

  /** Mặc định: 5 lần, chờ 2 giây, nhân đôi, tối đa 5 phút (suy luận, TL không ghi số). */
  public static RetryPolicy standard() {
    return new RetryPolicy(5, Duration.ofSeconds(2), 2.0, Duration.ofMinutes(5));
  }

  /** Còn được thử thêm sau khi đã thử {@code attemptsSoFar} lần. */
  public boolean allowsAnotherAttempt(int attemptsSoFar) {
    return attemptsSoFar < maxAttempts;
  }

  /** Khoảng chờ trước lần thử kế tiếp sau {@code attemptsSoFar} lần đã thất bại. */
  public Duration backoffAfter(int attemptsSoFar) {
    double factor = Math.pow(multiplier, Math.max(0, attemptsSoFar - 1));
    double millis = initialBackoff.toMillis() * factor;
    if (millis >= maxBackoff.toMillis()) {
      return maxBackoff;
    }
    return Duration.ofMillis((long) millis);
  }
}
