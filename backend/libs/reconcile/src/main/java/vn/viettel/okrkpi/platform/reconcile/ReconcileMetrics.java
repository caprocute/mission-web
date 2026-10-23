package vn.viettel.okrkpi.platform.reconcile;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chỉ số Prometheus chung của các job đối soát (TL-04 "Quan sát hệ thống"). Nhãn chỉ có {@code
 * reconcile} (mã job; không dùng {@code job} vì trùng nhãn của Prometheus) và {@code status}, không
 * gắn tenant để giữ số chuỗi thời gian thấp; tenant lệch tra ở bảng {@code reconcile_run}.
 *
 * <ul>
 *   <li>{@code mission_reconcile_runs_total{reconcile,status}}: số lần đối soát đã kết thúc.
 *   <li>{@code mission_reconcile_mismatches_total{reconcile}}: số bucket hoặc nhóm lệch phát hiện.
 *   <li>{@code mission_reconcile_repaired_total{reconcile}}: số dòng đã phát lại hay sửa tự động.
 *   <li>{@code mission_reconcile_duration_seconds{reconcile}}: thời gian một lần đối soát.
 *   <li>{@code mission_reconcile_last_finished_seconds{reconcile}}: thời điểm kết thúc gần nhất
 *       (giây Unix), để cảnh báo job ngừng chạy.
 * </ul>
 */
public final class ReconcileMetrics {

  public static final String RUNS = "mission.reconcile.runs";
  public static final String MISMATCHES = "mission.reconcile.mismatches";
  public static final String REPAIRED = "mission.reconcile.repaired";
  public static final String DURATION = "mission.reconcile.duration";
  public static final String LAST_FINISHED = "mission.reconcile.last.finished";

  private final MeterRegistry registry;
  private final Clock clock;
  private final Map<String, AtomicLong> last = new ConcurrentHashMap<>();

  public ReconcileMetrics(MeterRegistry registry, Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Ghi một lần đối soát đã kết thúc. */
  public void finished(
      String job, RunStatus status, long mismatches, long repaired, Duration took) {
    Counter.builder(RUNS)
        .description("Số lần đối soát đã kết thúc")
        .tag("reconcile", job)
        .tag("status", status.name())
        .register(registry)
        .increment();
    Counter.builder(MISMATCHES)
        .description("Số bucket hoặc nhóm lệch phát hiện")
        .tag("reconcile", job)
        .register(registry)
        .increment(mismatches);
    Counter.builder(REPAIRED)
        .description("Số dòng đã phát lại hoặc sửa tự động")
        .tag("reconcile", job)
        .register(registry)
        .increment(repaired);
    Timer.builder(DURATION)
        .description("Thời gian một lần đối soát")
        .tag("reconcile", job)
        .register(registry)
        .record(took);
    last.computeIfAbsent(
            job,
            j -> {
              AtomicLong v = new AtomicLong();
              Gauge.builder(LAST_FINISHED, v, AtomicLong::get)
                  .description("Thời điểm kết thúc lần đối soát gần nhất (giây Unix)")
                  .baseUnit("seconds")
                  .tag("reconcile", j)
                  .register(registry);
              return v;
            })
        .set(clock.instant().getEpochSecond());
  }
}
