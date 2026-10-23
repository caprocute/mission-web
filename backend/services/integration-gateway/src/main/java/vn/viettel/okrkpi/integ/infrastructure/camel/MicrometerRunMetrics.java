package vn.viettel.okrkpi.integ.infrastructure.camel;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import vn.viettel.okrkpi.integ.application.SyncRunService.RunMetrics;
import vn.viettel.okrkpi.integ.application.port.SyncRuns.Status;

/**
 * Chỉ số lần đồng bộ: {@code integ.sync.cursor.lag} (giây, theo mã kết nối) là độ trễ của trang mới
 * nhất so với thời điểm cập nhật ở nguồn; {@code integ.sync.run} đo thời gian lần chạy theo kết
 * quả; {@code integ.sync.run.messages} đếm thông điệp đã xử lý.
 */
public class MicrometerRunMetrics implements RunMetrics {

  public static final String LAG = "integ.sync.cursor.lag";
  public static final String RUN = "integ.sync.run";

  private final MeterRegistry meters;
  private final Map<String, AtomicLong> lags = new ConcurrentHashMap<>();

  public MicrometerRunMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void cursorLag(String connectorCode, Duration lag) {
    lags.computeIfAbsent(
            connectorCode,
            c ->
                meters.gauge(
                    LAG,
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("connector", c)),
                    new AtomicLong()))
        .set(lag.toSeconds());
  }

  @Override
  public void finished(String connectorCode, Status status, Duration elapsed, int messages) {
    Timer.builder(RUN)
        .tag("connector", connectorCode)
        .tag("status", status.name())
        .register(meters)
        .record(elapsed);
    meters.counter(RUN + ".messages", "connector", connectorCode).increment(messages);
  }
}
