package vn.viettel.okrkpi.shared.datasource;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.MDC;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Theo dõi thời điểm ghi gần nhất của từng người dùng/ngữ cảnh để bảo đảm đọc-những-gì-vừa-ghi
 * (*read-your-own-writes*) trong cửa sổ 2 giây (TL-01 L896–902, TL-03 "Định tuyến đọc/ghi").
 *
 * <p>Khi một giao dịch ghi ({@code readOnly = false}) bắt đầu hoặc cam kết trên bất kỳ mô-đun nào,
 * {@link #recordWrite()} ghi nhận dấu thời gian hiện tại cho khoá ngữ cảnh hiện hành. Trong vòng
 * {@code window} (mặc định 2 giây), mọi giao dịch {@code @Transactional(readOnly = true)} của cùng
 * người dùng đều được ghim về cụm chính ({@link DataSourceRoute#RW}) thay vì sang bản sao đọc
 * ({@link DataSourceRoute#RO}).
 */
public class RecentWriteTracker {

  /** Cửa sổ ghim về RW sau khi ghi theo TL-01 (2 giây). */
  public static final Duration DEFAULT_WRITE_STICKINESS_WINDOW = Duration.ofSeconds(2);

  /** Ngưỡng độ trễ nhân bản tối đa cho phép đọc từ RO (1.000 ms theo TL-01/TL-03). */
  public static final long DEFAULT_MAX_REPLICA_LAG_MS = 1_000L;

  private static final String DEFAULT_ACTOR_KEY = "anonymous";

  private final Clock clock;
  private final Duration stickinessWindow;
  private final Cache<String, Instant> lastWriteByActor;
  private final AtomicLong replicaLagMs = new AtomicLong(0L);
  private final ThreadLocal<String> explicitActorKey = new ThreadLocal<>();

  public RecentWriteTracker() {
    this(Clock.systemUTC(), DEFAULT_WRITE_STICKINESS_WINDOW);
  }

  public RecentWriteTracker(Clock clock, Duration stickinessWindow) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.stickinessWindow = Objects.requireNonNull(stickinessWindow, "stickinessWindow");
    this.lastWriteByActor =
        Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(
                Math.max(stickinessWindow.toMillis() * 5, 10_000L), TimeUnit.MILLISECONDS)
            .build();
  }

  /** Ghi nhận thao tác ghi cho người dùng/ngữ cảnh hiện hành. */
  public void recordWrite() {
    recordWriteFor(resolveCurrentActorKey());
  }

  /** Ghi nhận thao tác ghi cho khoá định danh người dùng chỉ định. */
  public void recordWriteFor(String actorKey) {
    String key = (actorKey == null || actorKey.isBlank()) ? DEFAULT_ACTOR_KEY : actorKey;
    lastWriteByActor.put(key, clock.instant());
  }

  /** Kiểm tra người dùng hiện hành có vừa thực hiện ghi trong cửa sổ 2 giây hay không. */
  public boolean hasRecentWrite() {
    return hasRecentWriteFor(resolveCurrentActorKey());
  }

  /** Kiểm tra khoá người dùng chỉ định có vừa thực hiện ghi trong cửa sổ 2 giây hay không. */
  public boolean hasRecentWriteFor(String actorKey) {
    String key = (actorKey == null || actorKey.isBlank()) ? DEFAULT_ACTOR_KEY : actorKey;
    Instant lastWrite = lastWriteByActor.getIfPresent(key);
    if (lastWrite == null) {
      return false;
    }
    Duration elapsed = Duration.between(lastWrite, clock.instant());
    return !elapsed.isNegative() && elapsed.compareTo(stickinessWindow) < 0;
  }

  /** Xoá trạng thái ghi gần nhất (dùng trong kiểm thử). */
  public void clear() {
    lastWriteByActor.invalidateAll();
    explicitActorKey.remove();
    replicaLagMs.set(0L);
  }

  /** Cập nhật độ trễ nhân bản hiện tại (mili-giây) từ bộ giám sát репlica. */
  public void updateReplicaLagMs(long lagMs) {
    replicaLagMs.set(Math.max(0L, lagMs));
  }

  /** Độ trễ nhân bản hiện tại (mili-giây). */
  public long currentReplicaLagMs() {
    return replicaLagMs.get();
  }

  /** Đặt khoá người dùng tường minh cho luồng hiện tại (hữu ích khi kiểm thử). */
  public void setExplicitActorKey(String actorKey) {
    if (actorKey == null) {
      explicitActorKey.remove();
    } else {
      explicitActorKey.set(actorKey);
    }
  }

  String resolveCurrentActorKey() {
    String explicit = explicitActorKey.get();
    if (explicit != null && !explicit.isBlank()) {
      return explicit;
    }
    String actorFromMdc = MDC.get("actor_id");
    if (actorFromMdc != null && !actorFromMdc.isBlank()) {
      return actorFromMdc;
    }
    return TenantContext.current().map(Object::toString).orElse(DEFAULT_ACTOR_KEY);
  }
}
