package vn.viettel.okrkpi.iam.vneid;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Bộ ngắt mạch quanh lời gọi VNeID (TL-02a QT-00 "VNeID không phản hồi hoặc trả lỗi"): sau {@code threshold} lần lỗi
 * liên tiếp thì mở mạch trong {@code openMillis}, trong lúc mở không chuyển hướng người dùng sang VNeID; hết hạn thì
 * cho một lượt thử (nửa mở), thành công thì đóng lại, lỗi thì mở tiếp. Trạng thái giữ trong bộ nhớ của từng pod
 * Keycloak theo realm và alias nhà cung cấp.
 */
final class CircuitBreaker {

  enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private static final Map<String, CircuitBreaker> REGISTRY = new ConcurrentHashMap<>();

  private final int threshold;
  private final long openMillis;
  private final LongSupplier clock;
  private int failures;
  private long openedAt;
  private State state = State.CLOSED;

  CircuitBreaker(int threshold, long openMillis, LongSupplier clock) {
    this.threshold = Math.max(1, threshold);
    this.openMillis = Math.max(0, openMillis);
    this.clock = clock;
  }

  /** Bộ ngắt dùng chung của một nhà cung cấp; cấu hình đổi thì tạo bộ mới. */
  static CircuitBreaker of(String key, int threshold, long openMillis) {
    return REGISTRY.compute(key, (k, old) -> old != null && old.threshold == Math.max(1, threshold)
        && old.openMillis == Math.max(0, openMillis) ? old
        : new CircuitBreaker(threshold, openMillis, System::currentTimeMillis));
  }

  /** {@code true} khi được gọi VNeID; mạch mở đã hết hạn thì chuyển nửa mở và cho một lượt thử. */
  synchronized boolean allow() {
    if (state == State.OPEN && clock.getAsLong() - openedAt >= openMillis) {
      state = State.HALF_OPEN;
    }
    return state != State.OPEN;
  }

  synchronized void success() {
    failures = 0;
    state = State.CLOSED;
  }

  synchronized void failure() {
    failures++;
    if (state == State.HALF_OPEN || failures >= threshold) {
      state = State.OPEN;
      openedAt = clock.getAsLong();
    }
  }

  synchronized State state() {
    return state;
  }
}
