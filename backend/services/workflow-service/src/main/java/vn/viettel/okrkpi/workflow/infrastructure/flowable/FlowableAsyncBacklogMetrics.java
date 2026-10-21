package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Xuất chỉ số Micrometer {@code flowable_async_jobs_due_unlocked} đo độ dồn của hàng đợi async
 * executor trong bảng {@code flowable.act_ru_job} (TL-01 chương 8 mục 8.8 & 8.9 · DV-06-01).
 */
@Component
public class FlowableAsyncBacklogMetrics {

  public static final String METRIC_NAME = "flowable_async_jobs_due_unlocked";

  private static final String DUE_UNLOCKED_JOBS_SQL =
      "SELECT count(*) FROM flowable.act_ru_job "
          + "WHERE lock_owner_ IS NULL AND (duedate_ IS NULL OR duedate_ <= now())";

  private final JdbcTemplate jdbcTemplate;

  public FlowableAsyncBacklogMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
    this.jdbcTemplate = jdbcTemplate;
    Gauge.builder(METRIC_NAME, this, FlowableAsyncBacklogMetrics::countDueUnlockedJobs)
        .description("Số job bất đồng bộ đến hạn chưa khoá trong bảng flowable.act_ru_job")
        .register(meterRegistry);
  }

  public double countDueUnlockedJobs() {
    try {
      Long count = jdbcTemplate.queryForObject(DUE_UNLOCKED_JOBS_SQL, Long.class);
      return count != null ? count.doubleValue() : 0.0;
    } catch (DataAccessException ex) {
      return 0.0;
    }
  }
}
