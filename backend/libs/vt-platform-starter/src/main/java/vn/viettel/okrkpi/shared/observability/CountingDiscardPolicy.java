package vn.viettel.okrkpi.shared.observability;

import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.async.DiscardingAsyncQueueFullPolicy;
import org.apache.logging.log4j.core.async.EventRoute;
import org.apache.logging.log4j.util.PropertiesUtil;

/**
 * Chính sách hàng đợi đầy của AsyncLogger (TL-01 "Log có cấu trúc"): bỏ dòng từ mức {@code
 * log4j2.discardThreshold} (mặc định INFO) trở xuống, giữ WARN và ERROR, và đếm số dòng bị bỏ để
 * xuất metric {@code log4j2.events.discarded} (G4). Khai báo trong {@code
 * log4j2.component.properties} bằng {@code log4j2.asyncQueueFullPolicy}.
 */
public class CountingDiscardPolicy extends DiscardingAsyncQueueFullPolicy {

  private static final AtomicLong DISCARDED = new AtomicLong();

  public CountingDiscardPolicy() {
    this(
        Level.toLevel(
            PropertiesUtil.getProperties().getStringProperty("log4j2.discardThreshold", "INFO"),
            Level.INFO));
  }

  public CountingDiscardPolicy(Level threshold) {
    super(threshold);
  }

  @Override
  public EventRoute getRoute(long backgroundThreadId, Level level) {
    EventRoute route = super.getRoute(backgroundThreadId, level);
    if (route == EventRoute.DISCARD) {
      DISCARDED.incrementAndGet();
    }
    return route;
  }

  /** Tổng số dòng nhật ký bị bỏ từ khi tiến trình chạy. */
  public static long discardedCount() {
    return DISCARDED.get();
  }
}
