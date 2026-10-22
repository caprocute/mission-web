package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.viettel.okrkpi.task.application.direct.TaskDirectionService;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.infrastructure.listener.LeadMovedConsumer;

/** CORE-02-25: nhắc việc, giao lại và consumer EVT-15 người chủ trì chuyển công tác. */
@Configuration(proxyBeanMethods = false)
public class TaskDirectionConfiguration {

  @Bean
  JdbcTaskDirections taskDirections(@Qualifier("taskDataSource") DataSource dataSource) {
    return new JdbcTaskDirections(JdbcClient.create(dataSource));
  }

  @Bean
  TaskDirectionService taskDirectionService(
      JdbcTaskLifecycleRepository taskLifecycleRepository,
      JdbcTaskDirections taskDirections,
      OrgDirectoryApi org,
      PeriodApi periods,
      TaskReminderNotices taskReminderNotices,
      TaskAssignedEvents taskAssignedEvents,
      @Value("${mission.task.reminder.window:PT24H}") Duration window) {
    return new TaskDirectionService(
        taskLifecycleRepository,
        taskDirections,
        org,
        periods,
        taskReminderNotices,
        taskAssignedEvents,
        window,
        java.time.Clock.systemUTC());
  }

  /** Inbox riêng của schema {@code task} cho EVT-15, như consumer EVT-18 của kế hoạch. */
  @Bean
  @ConditionalOnProperty(
      prefix = "mission.task.lead-moved.consumer",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  LeadMovedConsumer leadMovedConsumer(
      TaskDirectionService taskDirectionService,
      @Qualifier("taskDataSource") DataSource dataSource,
      @Qualifier("taskTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<InboxProperties> properties,
      ObjectProvider<InboxPayloadDeserializer> deserializer,
      ObjectProvider<InboxRetryDlqPublisher> retryDlq,
      ObjectProvider<InboxMetrics> metrics,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers) {
    InboxProperties props = properties.getIfAvailable();
    InboxPayloadDeserializer d = deserializer.getIfAvailable();
    InboxRetryDlqPublisher r = retryDlq.getIfAvailable();
    InboxMetrics m = metrics.getIfAvailable();
    if (props == null || d == null || r == null || m == null) {
      return null;
    }
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            new JdbcTemplate(dataSource),
            tm,
            "task",
            d,
            r,
            m,
            props.retry(),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
    return new LeadMovedConsumer(
        taskDirectionService, processor, containers, props.listenerAutoStartup());
  }
}
