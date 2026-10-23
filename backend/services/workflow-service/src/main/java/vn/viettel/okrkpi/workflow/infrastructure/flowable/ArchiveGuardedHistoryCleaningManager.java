package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.flowable.engine.DefaultHistoryCleaningManager;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.workflow.application.history.port.HistoryArchiveWatermark;

/**
 * Job dọn lịch sử của Flowable chỉ xoá tiến trình đã có trong kho phân tích (DV-06-11): mốc xoá là
 * mốc nhỏ hơn giữa hạn giữ 400 ngày và mốc đã chép trừ biên an toàn. Chưa biết mốc thì mốc xoá là
 * thời điểm 0, tức không xoá gì. Mốc đã chép tính theo thời điểm ghi của dòng nguồn nên tiến trình
 * kết thúc trước mốc đã có cả {@code ACT_HI_PROCINST}, {@code ACT_HI_ACTINST}, {@code
 * ACT_HI_TASKINST} trong ClickHouse (suy luận, wiki trang 14).
 */
public class ArchiveGuardedHistoryCleaningManager extends DefaultHistoryCleaningManager {

  public static final String CUTOFF_METRIC = "flowable_history_cleanup_cutoff_timestamp_seconds";

  private static final Logger LOG =
      LoggerFactory.getLogger(ArchiveGuardedHistoryCleaningManager.class);

  private final HistoryArchiveWatermark watermark;
  private final Duration safetyMargin;
  private final AtomicLong cutoffSeconds = new AtomicLong();

  public ArchiveGuardedHistoryCleaningManager(
      ProcessEngineConfigurationImpl configuration,
      HistoryArchiveWatermark watermark,
      Duration safetyMargin,
      MeterRegistry registry) {
    super(configuration);
    this.watermark = Objects.requireNonNull(watermark, "watermark");
    this.safetyMargin = Objects.requireNonNull(safetyMargin, "safetyMargin");
    Gauge.builder(CUTOFF_METRIC, cutoffSeconds, AtomicLong::doubleValue)
        .description("Mốc kết thúc mà job dọn lịch sử Flowable được xoá tới (giây Unix)")
        .register(registry);
  }

  @Override
  protected Date getEndedBefore() {
    Date cutoff = cutoff(super.getEndedBefore().toInstant(), watermark.archivedUpTo());
    cutoffSeconds.set(cutoff.toInstant().getEpochSecond());
    return cutoff;
  }

  Date cutoff(Instant retention, Optional<Instant> archived) {
    if (archived.isEmpty()) {
      LOG.warn("Chưa có mốc chép lịch sử sang kho phân tích, bỏ qua lượt dọn lịch sử Flowable");
      return Date.from(Instant.EPOCH);
    }
    Instant safe = archived.get().minus(safetyMargin);
    return Date.from(safe.isBefore(retention) ? safe : retention);
  }
}
