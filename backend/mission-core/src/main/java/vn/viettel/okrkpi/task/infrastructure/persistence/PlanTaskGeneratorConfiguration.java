package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.application.fromplan.LeadConfirmationService;
import vn.viettel.okrkpi.task.application.fromplan.PlanTaskGenerator;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTaskNotices;
import vn.viettel.okrkpi.task.application.lifecycle.PolicyBackedTransitionTableProvider;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.infrastructure.listener.PlanApprovedConsumer;

/**
 * CORE-02-11: sinh nhiệm vụ từ kế hoạch đã duyệt (consumer EVT-03) và xác nhận dòng liên đơn vị.
 */
@Configuration(proxyBeanMethods = false)
public class PlanTaskGeneratorConfiguration {

  @Bean
  PlanTaskNotices planTaskNotices(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxPlanTaskNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "task",
            new ApicurioPayloadSerializer(registry, java.util.Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc()));
  }

  @Bean
  PlanTaskGenerator planTaskGenerator(
      @Qualifier("taskDataSource") DataSource dataSource,
      JdbcPlanRepository planRepository,
      JdbcTaskDraftRepository taskDraftRepository,
      JdbcTaskLifecycleRepository taskLifecycleRepository,
      TaskTransitionService taskTransitionService,
      JdbcTaskChangeRequests taskChangeRequests,
      ObjectProvider<TaskDeliverables> deliverables,
      PolicyBackedTransitionTableProvider taskTransitionTableProvider,
      PeriodApi periods,
      PlanTaskNotices planTaskNotices) {
    return new PlanTaskGenerator(
        planRepository,
        new JdbcPlanTasks(JdbcClient.create(dataSource)),
        taskDraftRepository,
        taskLifecycleRepository,
        taskTransitionService,
        taskChangeRequests,
        deliverables.getIfAvailable(),
        taskTransitionTableProvider,
        periods,
        planTaskNotices,
        java.time.Clock.systemUTC());
  }

  @Bean
  LeadConfirmationService leadConfirmationService(
      @Qualifier("taskDataSource") DataSource dataSource) {
    return new LeadConfirmationService(
        new JdbcLeadConfirmations(JdbcClient.create(dataSource)), java.time.Clock.systemUTC());
  }

  /** Inbox riêng của schema {@code task} cho EVT-03, như consumer EVT-15. */
  @Bean
  @ConditionalOnProperty(
      prefix = "mission.task.plan-approved.consumer",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  PlanApprovedConsumer planApprovedConsumer(
      PlanTaskGenerator planTaskGenerator,
      @Qualifier("taskDataSource") DataSource dataSource,
      @Qualifier("taskTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<InboxProperties> properties,
      ObjectProvider<InboxPayloadDeserializer> deserializer,
      ObjectProvider<InboxRetryDlqPublisher> retryDlq,
      ObjectProvider<InboxMetrics> metrics,
      ObjectProvider<MeterRegistry> meters,
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
    return new PlanApprovedConsumer(
        planTaskGenerator,
        processor,
        containers,
        meters.getIfAvailable(),
        props.listenerAutoStartup());
  }
}
