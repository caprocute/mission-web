package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.impl.event.FlowableEntityEventImpl;
import org.flowable.common.engine.impl.interceptor.CommandInterceptor;
import org.flowable.job.service.impl.persistence.entity.JobEntityImpl;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.infrastructure.history.GatedHistoryArchiveWatermark;

/** DV-06-11: chỉ số theo sự kiện job, đếm giao dịch, bộ đệm định nghĩa và mốc dọn lịch sử. */
class FlowableG2InstrumentationTest {

  private static final Instant NOW = Instant.parse("2026-10-23T02:00:00Z");
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void doTreLayJobTuHanKhoaTruThoiGianKhoaVaKetQuaDonLichSu() {
    FlowableJobMetricsListener listener =
        new FlowableJobMetricsListener(
            registry, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
    JobEntityImpl job = new JobEntityImpl();
    job.setDuedate(Date.from(NOW.minusSeconds(12)));
    job.setLockExpirationTime(Date.from(NOW.plus(Duration.ofMinutes(5))));
    assertThat(listener.lag(job)).contains(Duration.ofSeconds(12));

    listener.onEvent(event(FlowableEngineEventType.JOB_EXECUTION_SUCCESS, job));
    assertThat(registry.get(FlowableJobMetricsListener.ACQUIRE_LAG).timer().count()).isEqualTo(1);

    JobEntityImpl cleanup = new JobEntityImpl();
    cleanup.setJobHandlerType(FlowableJobMetricsListener.HISTORY_CLEANUP_HANDLER);
    listener.onEvent(event(FlowableEngineEventType.JOB_EXECUTION_SUCCESS, cleanup));
    listener.onEvent(event(FlowableEngineEventType.JOB_EXECUTION_FAILURE, cleanup));
    listener.onEvent(event(FlowableEngineEventType.JOB_MOVED_TO_DEADLETTER, cleanup));
    listener.onEvent(event(FlowableEngineEventType.JOB_CANCELED, cleanup));
    listener.onEvent(new FlowableEntityEventImpl("khác", FlowableEngineEventType.ENTITY_CREATED));
    assertThat(registry.get(FlowableJobMetricsListener.CLEANUP_LAST_SUCCESS).gauge().value())
        .isEqualTo(NOW.getEpochSecond());
    assertThat(registry.get(FlowableJobMetricsListener.CLEANUP_FAILURES).counter().count())
        .isEqualTo(1);
    assertThat(registry.get(FlowableJobMetricsListener.DEADLETTER_MOVED).counter().count())
        .isEqualTo(1);
    // Không có hạn khoá hoặc lấy sớm hơn hạn: không âm, không ghi.
    assertThat(listener.lag(cleanup)).isEmpty();
    JobEntityImpl early = new JobEntityImpl();
    early.setCreateTime(Date.from(NOW.plusSeconds(30)));
    early.setLockExpirationTime(Date.from(NOW.plus(Duration.ofMinutes(5))));
    assertThat(listener.lag(early)).contains(Duration.ZERO);
    assertThat(listener.isFailOnException()).isFalse();
    assertThat(listener.isFireOnTransactionLifecycleEvent()).isFalse();
    assertThat(listener.getOnTransaction()).isNull();
  }

  @Test
  void demGiaoDichVaXungDotKhoaLacQuan() {
    CountingCommandInterceptor interceptor = new CountingCommandInterceptor(registry);
    CommandInterceptor next = mock(CommandInterceptor.class);
    interceptor.setNext(next);
    when(next.execute(any(), any(), any())).thenReturn("ok");
    assertThat((String) interceptor.execute(null, c -> "ok", null)).isEqualTo("ok");
    when(next.execute(any(), any(), any()))
        .thenThrow(new FlowableOptimisticLockingException("xung đột"));
    assertThatThrownBy(() -> interceptor.execute(null, c -> "x", null))
        .isInstanceOf(FlowableOptimisticLockingException.class);
    assertThat(registry.get(CountingCommandInterceptor.COMMANDS).counter().count()).isEqualTo(2);
    assertThat(registry.get(CountingCommandInterceptor.OPTIMISTIC_LOCKING).counter().count())
        .isEqualTo(1);
  }

  @Test
  void boDemDinhNghiaDemTrungTruot() {
    CountingDeploymentCache<String> cache = new CountingDeploymentCache<>(2, registry);
    assertThat(cache.get("a")).isNull();
    cache.add("a", "A");
    assertThat(cache.get("a")).isEqualTo("A");
    assertThat(registry.get(CountingDeploymentCache.METRIC).tag("result", "miss").counter().count())
        .isEqualTo(1);
    assertThat(registry.get(CountingDeploymentCache.METRIC).tag("result", "hit").counter().count())
        .isEqualTo(1);
  }

  @Test
  void donLichSuChiXoaTienTrinhDaChepTruBienAnToan() {
    SpringProcessEngineConfiguration cfg = new SpringProcessEngineConfiguration();
    cfg.setCleanInstancesEndedAfter(Duration.ofDays(400));
    cfg.setClock(new org.flowable.common.engine.impl.util.DefaultClockImpl());
    ArchiveGuardedHistoryCleaningManager manager =
        new ArchiveGuardedHistoryCleaningManager(
            cfg, Optional::empty, Duration.ofHours(1), registry);
    Instant retention = NOW.minus(Duration.ofDays(400));

    assertThat(manager.cutoff(retention, Optional.empty())).isEqualTo(Date.from(Instant.EPOCH));
    assertThat(manager.cutoff(retention, Optional.of(NOW))).isEqualTo(Date.from(retention));
    Instant lagging = retention.minus(Duration.ofDays(2));
    assertThat(manager.cutoff(retention, Optional.of(lagging)))
        .isEqualTo(Date.from(lagging.minus(Duration.ofHours(1))));
    assertThat(manager.getEndedBefore()).isEqualTo(Date.from(Instant.EPOCH));
    assertThat(registry.get(ArchiveGuardedHistoryCleaningManager.CUTOFF_METRIC).gauge().value())
        .isZero();

    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    assertThat(
            new GatedHistoryArchiveWatermark(GatedHistoryArchiveWatermark.Mode.GIA_LAP, clock)
                .archivedUpTo())
        .contains(NOW);
    GatedHistoryArchiveWatermark off =
        new GatedHistoryArchiveWatermark(GatedHistoryArchiveWatermark.Mode.CHUA_NOI, clock);
    assertThat(off.archivedUpTo()).isEmpty();
    assertThat(off.mode()).isEqualTo(GatedHistoryArchiveWatermark.Mode.CHUA_NOI);
  }

  private static FlowableEntityEventImpl event(FlowableEngineEventType type, Object entity) {
    return new FlowableEntityEventImpl(entity, type);
  }
}
