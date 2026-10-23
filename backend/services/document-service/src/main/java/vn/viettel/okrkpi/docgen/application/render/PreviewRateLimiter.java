package vn.viettel.okrkpi.docgen.application.render;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn tốc độ xem trước theo người dùng (DV-11-02): mỗi (tenant, người gọi) được tối đa {@code
 * limit} lượt trong một cửa sổ cố định {@code window}. Bộ đếm nằm trong bộ nhớ của từng bản chạy
 * nên giới hạn thực tế là {@code limit} nhân số bản chạy (suy luận; gateway APISIX giữ giới hạn
 * chung). Số khoá giữ tối đa {@link #MAX_KEYS}; khi đầy, các cửa sổ đã hết hạn được dọn trước.
 */
public class PreviewRateLimiter {

  static final int MAX_KEYS = 50_000;

  private final int limit;
  private final Duration window;
  private final Clock clock;
  private final Map<Key, Window> windows = new ConcurrentHashMap<>();

  public PreviewRateLimiter(int limit, Duration window, Clock clock) {
    if (limit < 1 || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("Giới hạn xem trước phải dương");
    }
    this.limit = limit;
    this.window = window;
    this.clock = clock;
  }

  /** Lượt bị từ chối vì vượt giới hạn; {@code retryAfter} là thời gian còn lại của cửa sổ. */
  public static class Exceeded extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient Duration retryAfter;

    public Exceeded(Duration retryAfter) {
      super("Vượt giới hạn xem trước");
      this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
      return retryAfter;
    }

    public long retryAfterSeconds() {
      return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
  }

  /** Ghi nhận một lượt; ném {@link Exceeded} khi đã hết lượt trong cửa sổ hiện tại. */
  public void acquire(UUID tenantId, UUID caller) {
    Instant now = clock.instant();
    if (windows.size() >= MAX_KEYS) {
      windows.values().removeIf(w -> !w.start().plus(window).isAfter(now));
      if (windows.size() >= MAX_KEYS) {
        windows.clear();
      }
    }
    Window current =
        windows.compute(
            new Key(tenantId, caller),
            (k, w) ->
                w == null || !w.start().plus(window).isAfter(now)
                    ? new Window(now, 1)
                    : new Window(w.start(), w.count() + 1));
    if (current.count() > limit) {
      throw new Exceeded(Duration.between(now, current.start().plus(window)));
    }
  }

  private record Key(UUID tenantId, UUID caller) {}

  private record Window(Instant start, int count) {}
}
