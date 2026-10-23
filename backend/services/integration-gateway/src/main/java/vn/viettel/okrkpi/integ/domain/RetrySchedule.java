package vn.viettel.okrkpi.integ.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Lịch thử lại luỹ thừa của thông điệp ra: lần thứ {@code n} chờ {@code base × multiplier^(n-1)},
 * không quá {@code cap}; hết {@code maxAttempts} thì dừng (chuyển thư chết).
 */
public record RetrySchedule(Duration base, int multiplier, Duration cap, int maxAttempts) {

  /** Mặc định của khung route (TL-01 Lớp chống ô nhiễm): 5 lần, 2 giây, nhân 2. */
  public static final RetrySchedule DEFAULT =
      new RetrySchedule(Duration.ofSeconds(2), 2, Duration.ofHours(1), 5);

  public RetrySchedule {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(cap, "cap");
    if (base.isNegative() || base.isZero() || multiplier < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("Lịch thử lại không hợp lệ");
    }
  }

  /**
   * Thời điểm thử kế tiếp sau khi lần thứ {@code attempts} (đếm từ 1) thất bại; rỗng khi đã hết
   * lượt.
   */
  public Optional<Instant> next(int attempts, Instant now) {
    if (attempts >= maxAttempts) {
      return Optional.empty();
    }
    Duration delay = base;
    for (int i = 1; i < attempts && delay.compareTo(cap) < 0; i++) {
      delay = delay.multipliedBy(multiplier);
    }
    return Optional.of(now.plus(delay.compareTo(cap) > 0 ? cap : delay));
  }
}
