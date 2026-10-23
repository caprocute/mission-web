package vn.viettel.okrkpi.shared.site;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * INF-28 · Bọc {@link TaskScheduler} của job {@code @Scheduled}: lịch vẫn được đăng ký nhưng mỗi
 * lần đến hạn chỉ chạy khi TTDL không ở vai trò chờ. Kiểm tra ở từng lần chạy để giữ đúng lịch khi
 * vai trò đổi mà không cần đăng ký lại.
 */
public class StandbyAwareTaskScheduler implements TaskScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(StandbyAwareTaskScheduler.class);

  private final TaskScheduler delegate;
  private final BooleanSupplier standby;

  public StandbyAwareTaskScheduler(TaskScheduler delegate, BooleanSupplier standby) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.standby = Objects.requireNonNull(standby, "standby");
  }

  Runnable gate(Runnable task) {
    return () -> {
      if (standby.getAsBoolean()) {
        LOG.debug("Bỏ qua job định kỳ ở TTDL chờ: {}", task);
        return;
      }
      task.run();
    };
  }

  @Override
  public Clock getClock() {
    return delegate.getClock();
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
    return delegate.schedule(gate(task), trigger);
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
    return delegate.schedule(gate(task), startTime);
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
    return delegate.scheduleAtFixedRate(gate(task), startTime, period);
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
    return delegate.scheduleAtFixedRate(gate(task), period);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable task, Instant startTime, Duration delay) {
    return delegate.scheduleWithFixedDelay(gate(task), startTime, delay);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
    return delegate.scheduleWithFixedDelay(gate(task), delay);
  }
}
