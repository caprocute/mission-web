package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.task.application.overdue.OverdueSweeper;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.infrastructure.scheduling.OverdueJob;

/** CORE-02-23: cờ quá hạn hằng ngày và nhắc quá hạn EVT-21. */
@Configuration(proxyBeanMethods = false)
public class OverdueConfiguration {

  @Bean
  JdbcOverdueTasks overdueTasks(@Qualifier("taskDataSource") DataSource dataSource) {
    return new JdbcOverdueTasks(JdbcClient.create(dataSource));
  }

  @Bean
  TaskReminderNotices taskReminderNotices(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    ObjectMapper mapper = json.getIfAvailable(ObjectMapper::new);
    return new OutboxTaskReminderNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "task",
            new ApicurioPayloadSerializer(registry, Map.of()),
            mapper,
            Clock.systemUtc()),
        mapper);
  }

  @Bean
  OverdueSweeper overdueSweeper(
      JdbcOverdueTasks overdueTasks,
      TaskReminderNotices taskReminderNotices,
      ObjectProvider<WorkingCalendarApi> calendar,
      @Qualifier("taskTransactionManager") PlatformTransactionManager taskTransactionManager,
      @Value("${mission.task.change.coefficient-step:0.05}") BigDecimal step,
      @Value("${mission.task.overdue.batch-size:1000}") int batchSize) {
    return new OverdueSweeper(
        overdueTasks,
        taskReminderNotices,
        calendar.getIfAvailable(),
        new TransactionTemplate(taskTransactionManager),
        step,
        batchSize);
  }

  /** Tác vụ đặt cờ; tắt bằng {@code mission.task.overdue.enabled=false}. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.task.overdue",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Job {

    @Bean
    OverdueJob overdueJob(
        OverdueSweeper overdueSweeper,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new OverdueJob(overdueSweeper, directory, cell, java.time.Clock.systemUTC());
    }
  }
}
