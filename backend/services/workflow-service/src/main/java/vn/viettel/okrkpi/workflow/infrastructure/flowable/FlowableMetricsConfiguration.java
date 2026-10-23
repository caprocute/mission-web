package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;
import vn.viettel.okrkpi.workflow.application.history.port.HistoryArchiveWatermark;
import vn.viettel.okrkpi.workflow.infrastructure.history.GatedHistoryArchiveWatermark;

/**
 * Lắp chỉ số giám sát Flowable và cổng mốc chép lịch sử (DV-06-11). Truy vấn lấy mẫu chạy trên bản
 * sao đọc {@code okrkpi.workflow.metrics.replica.url} khi có (TL-01 G2), không có thì trên CSDL
 * chính, mỗi truy vấn tối đa {@code okrkpi.workflow.metrics.query-timeout}.
 */
@Configuration
@EnableScheduling
public class FlowableMetricsConfiguration {

  @Bean
  FlowableEngineMetrics flowableEngineMetrics(
      JdbcTemplate primary,
      MeterRegistry registry,
      @Value("${okrkpi.workflow.metrics.replica.url:}") String replicaUrl,
      @Value("${okrkpi.workflow.metrics.replica.username:}") String replicaUser,
      @Value("${okrkpi.workflow.metrics.replica.password:}") String replicaPassword,
      @Value("${okrkpi.workflow.metrics.query-timeout:5s}") Duration timeout) {
    JdbcTemplate jdbc = primary;
    if (StringUtils.hasText(replicaUrl)) {
      HikariDataSource replica =
          DataSourceBuilder.create()
              .type(HikariDataSource.class)
              .url(replicaUrl)
              .username(replicaUser)
              .password(replicaPassword)
              .build();
      replica.setPoolName("workflow-service-metrics-replica");
      replica.setMaximumPoolSize(2);
      replica.setReadOnly(true);
      jdbc = new JdbcTemplate(replica);
    } else {
      jdbc = new JdbcTemplate(primary.getDataSource());
    }
    jdbc.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
    return new FlowableEngineMetrics(jdbc, registry);
  }

  @Bean
  FlowableJobMetricsListener flowableJobMetricsListener(
      MeterRegistry registry,
      @Value("${flowable.process.async.executor.async-job-lock-time:5m}") Duration lockTime) {
    return new FlowableJobMetricsListener(registry, lockTime, Clock.systemUTC());
  }

  @Bean
  HistoryArchiveWatermark historyArchiveWatermark(
      @Value("${okrkpi.workflow.history-archive.mode:CHUA_NOI}")
          GatedHistoryArchiveWatermark.Mode mode) {
    return new GatedHistoryArchiveWatermark(mode, Clock.systemUTC());
  }
}
