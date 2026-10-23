package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.job.api.Job;
import org.flowable.job.api.JobInfo;
import org.flowable.job.service.impl.persistence.entity.JobInfoEntity;

/**
 * Chỉ số theo sự kiện job của engine (TL-01 G2): độ trễ lấy job từ lúc đến hạn tới lúc executor
 * khoá job, số job chuyển sang thư chết, kết quả lượt dọn lịch sử. Thời điểm lấy job suy từ hạn
 * khoá trừ thời gian khoá của async executor (suy luận: Flowable không phát sự kiện lúc lấy job).
 */
public class FlowableJobMetricsListener implements FlowableEventListener {

  public static final String ACQUIRE_LAG = "flowable.job.acquire.lag";
  public static final String DEADLETTER_MOVED = "flowable.jobs.deadletter.moved";
  public static final String CLEANUP_LAST_SUCCESS =
      "flowable_history_cleanup_last_success_timestamp_seconds";
  public static final String CLEANUP_FAILURES = "flowable.history.cleanup.failures";

  /** {@code BpmnHistoryCleanupJobHandler.TYPE}. */
  static final String HISTORY_CLEANUP_HANDLER = "bpmn-history-cleanup";

  private final Duration asyncJobLockTime;
  private final Clock clock;
  private final Timer acquireLag;
  private final Counter deadletter;
  private final Counter cleanupFailures;
  private final AtomicLong cleanupSuccess = new AtomicLong();

  public FlowableJobMetricsListener(
      MeterRegistry registry, Duration asyncJobLockTime, Clock clock) {
    this.asyncJobLockTime = Objects.requireNonNull(asyncJobLockTime, "asyncJobLockTime");
    this.clock = Objects.requireNonNull(clock, "clock");
    acquireLag =
        Timer.builder(ACQUIRE_LAG)
            .description("Độ trễ từ lúc job đến hạn tới lúc async executor lấy job")
            .publishPercentileHistogram()
            .serviceLevelObjectives(Duration.ofSeconds(10), Duration.ofSeconds(60))
            .register(registry);
    deadletter =
        Counter.builder(DEADLETTER_MOVED)
            .description("Số job hết lần thử, chuyển sang ACT_RU_DEADLETTER_JOB")
            .register(registry);
    cleanupFailures =
        Counter.builder(CLEANUP_FAILURES)
            .description("Số lượt dọn lịch sử Flowable thất bại")
            .register(registry);
    Gauge.builder(CLEANUP_LAST_SUCCESS, cleanupSuccess, AtomicLong::doubleValue)
        .description("Thời điểm lượt dọn lịch sử Flowable gần nhất thành công (giây Unix)")
        .register(registry);
  }

  @Override
  public void onEvent(FlowableEvent event) {
    if (!(event.getType() instanceof FlowableEngineEventType type)
        || !(event instanceof FlowableEntityEvent entityEvent)
        || !(entityEvent.getEntity() instanceof JobInfo job)) {
      return;
    }
    boolean cleanup = HISTORY_CLEANUP_HANDLER.equals(job.getJobHandlerType());
    switch (type) {
      case JOB_EXECUTION_SUCCESS -> {
        lag(job).ifPresent(acquireLag::record);
        if (cleanup) {
          cleanupSuccess.set(clock.instant().getEpochSecond());
        }
      }
      case JOB_EXECUTION_FAILURE -> {
        lag(job).ifPresent(acquireLag::record);
        if (cleanup) {
          cleanupFailures.increment();
        }
      }
      case JOB_MOVED_TO_DEADLETTER -> deadletter.increment();
      default -> {
        // Sự kiện khác không phải chỉ số G2.
      }
    }
  }

  /** Hạn khoá trừ thời gian khoá là lúc lấy job; mốc đến hạn là hạn job, không có thì lúc tạo. */
  Optional<Duration> lag(JobInfo job) {
    if (!(job instanceof JobInfoEntity locked) || locked.getLockExpirationTime() == null) {
      return Optional.empty();
    }
    Date due =
        job instanceof Job j ? (j.getDuedate() != null ? j.getDuedate() : j.getCreateTime()) : null;
    if (due == null) {
      return Optional.empty();
    }
    Instant acquired = locked.getLockExpirationTime().toInstant().minus(asyncJobLockTime);
    Duration lag = Duration.between(due.toInstant(), acquired);
    return Optional.of(lag.isNegative() ? Duration.ZERO : lag);
  }

  @Override
  public boolean isFailOnException() {
    return false;
  }

  @Override
  public boolean isFireOnTransactionLifecycleEvent() {
    return false;
  }

  @Override
  public String getOnTransaction() {
    return null;
  }
}
