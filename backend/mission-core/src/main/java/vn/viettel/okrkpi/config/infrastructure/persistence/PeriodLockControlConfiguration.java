package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.application.PeriodCloseParticipantService;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.application.PeriodLockControlService;
import vn.viettel.okrkpi.config.application.PeriodLockProgressService;
import vn.viettel.okrkpi.config.application.PeriodLockService;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.LockOrderNotices;
import vn.viettel.okrkpi.config.application.port.PeriodLockSagaControl;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.infrastructure.client.PeriodLockCommandRelay;
import vn.viettel.okrkpi.config.infrastructure.job.PeriodLockCommandJobs;
import vn.viettel.okrkpi.config.infrastructure.job.PeriodLockSagaJobs;
import vn.viettel.okrkpi.config.infrastructure.listener.PeriodLockSagaListener;
import vn.viettel.okrkpi.config.infrastructure.scheduling.PeriodLockCommandRelayJob;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowSagaClient;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * CORE-08-05 (phần điều khiển): bản chiếu lô, lỗi, nhật ký và hộp lệnh của saga chốt kỳ, cổng lệnh
 * xuống SVC-06 và dịch vụ điều khiển, trên {@code configDataSource} cùng giao dịch {@code
 * configTransactionManager}. Cổng ghi hộp lệnh trong giao dịch, rồi bộ chuyển tiếp gửi dòng tới
 * {@code controlPeriodCloseSaga} của SVC-06 sau khi commit.
 */
@Configuration(proxyBeanMethods = false)
public class PeriodLockControlConfiguration {

  @Bean
  JdbcLockProjectionRepository lockProjectionRepository(
      @Qualifier("configDataSource") DataSource dataSource) {
    return new JdbcLockProjectionRepository(JdbcClient.create(dataSource));
  }

  /**
   * Bộ chuyển tiếp hộp lệnh tới {@code controlPeriodCloseSaga}; chưa khai {@code
   * mission.config.workflow.base-url} thì không gửi, dòng giữ trạng thái chờ. Giao dịch {@code
   * REQUIRES_NEW} vì có thể chạy trong {@code afterCommit} của giao dịch ghi dòng.
   */
  @Bean
  PeriodLockCommandRelay periodLockCommandRelay(
      @Qualifier("configDataSource") DataSource dataSource,
      @Qualifier("configTransactionManager") PlatformTransactionManager transactions,
      ObjectProvider<WorkflowSagaClient> workflow,
      ObjectProvider<ObjectMapper> json) {
    TransactionTemplate tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new PeriodLockCommandRelay(
        JdbcClient.create(dataSource),
        tx,
        new PeriodLockCommandRelay.Sender() {
          @Override
          public boolean available() {
            return workflow.getIfAvailable() != null;
          }

          @Override
          public WorkflowSagaClient.ControlOutcome send(
              TenantId tenant, UUID sagaId, UUID commandId, Map<String, Object> body) {
            return workflow.getObject().controlPeriodClose(tenant, sagaId, commandId, body);
          }
        },
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc());
  }

  @Bean
  PeriodLockSagaControl periodLockSagaControl(
      @Qualifier("configDataSource") DataSource dataSource,
      ObjectProvider<ObjectMapper> json,
      PeriodLockCommandRelay periodLockCommandRelay) {
    return new QueuedPeriodLockSagaControl(
        JdbcClient.create(dataSource),
        json.getIfAvailable(ObjectMapper::new),
        periodLockCommandRelay);
  }

  /**
   * Chuyển lại hộp lệnh định kỳ; tắt bằng {@code mission.config.period-lock.relay.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.config.period-lock.relay",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Relay {

    @Bean
    PeriodLockCommandRelayJob periodLockCommandRelayJob(
        PeriodLockCommandRelay periodLockCommandRelay,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new PeriodLockCommandRelayJob(periodLockCommandRelay, directory, cell);
    }
  }

  @Bean
  PeriodLockControlService periodLockControlService(
      JdbcLockOrderRepository lockOrderRepository,
      JdbcLockProjectionRepository lockProjectionRepository,
      JdbcPeriodRepository periodRepository,
      PeriodLockSagaControl periodLockSagaControl,
      LockOrderNotices lockOrderNotices,
      ConfigAudit configAudit,
      PeriodConfigService periodConfigService,
      @Qualifier("configDataSource") DataSource dataSource) {
    return new PeriodLockControlService(
        lockOrderRepository,
        lockProjectionRepository,
        periodRepository,
        periodLockSagaControl,
        lockOrderNotices,
        new JdbcRoleHolders(JdbcClient.create(dataSource)),
        configAudit,
        (tenant, period, saga, actor, order, reason) ->
            periodConfigService.transition(
                tenant, period, PeriodState.MO, saga, actor, order, reason),
        Clock.systemUtc());
  }

  /** MOD-08 là bên tham gia saga chốt kỳ (cổng nội bộ cho SVC-06, QA-06). */
  @Bean
  PeriodCloseParticipantService periodCloseParticipantService(
      JdbcLockOrderRepository lockOrderRepository,
      JdbcPeriodRepository periodRepository,
      PeriodConfigService periodConfigService) {
    return new PeriodCloseParticipantService(
        lockOrderRepository, periodRepository, periodConfigService);
  }

  /** Bản chiếu tiến độ saga chốt kỳ (QA-06). */
  @Bean
  PeriodLockProgressService periodLockProgressService(
      JdbcLockOrderRepository lockOrderRepository,
      PeriodLockControlService periodLockControlService) {
    return new PeriodLockProgressService(lockOrderRepository, periodLockControlService);
  }

  /**
   * Consumer vết saga {@code sys.audit.v1} qua inbox của schema config; bản ghi không phải vết saga
   * chốt kỳ được xác nhận ngay mà không qua inbox. Tắt bằng {@code
   * mission.config.period-lock.saga-listener.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
  @ConditionalOnProperty(
      prefix = "mission.config.period-lock.saga-listener",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class SagaInbox {

    @Bean
    PeriodLockSagaListener periodLockSagaListener(PeriodLockProgressService progress) {
      return new PeriodLockSagaListener(progress);
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> periodLockSagaContainer(
        PeriodLockSagaListener listener,
        @Qualifier("configDataSource") DataSource dataSource,
        @Qualifier("configTransactionManager") PlatformTransactionManager tm,
        InboxProperties properties,
        InboxPayloadDeserializer deserializer,
        InboxRetryDlqPublisher retryDlqPublisher,
        InboxMetrics metrics,
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        @Value("${okrkpi.inbox.listener-auto-startup:true}") boolean autoStartup) {
      InboxMessageProcessor processor =
          PeriodConfiguration.configInboxProcessor(
              dataSource, tm, properties, deserializer, retryDlqPublisher, metrics);
      InboxEndpoint endpoint = sagaEndpoint(listener);
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + PeriodLockSagaListener.CONSUMER);
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container = new ContainerProperties(EventTopics.SYS_AUDIT_V1);
      container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
      container.setMessageListener(
          (AcknowledgingMessageListener<String, byte[]>)
              (record, ack) -> {
                Object payload;
                try {
                  payload =
                      deserializer.deserialize(record.topic(), record.headers(), record.value());
                } catch (RuntimeException e) {
                  // Thân hỏng hay thiếu lược đồ: để inbox ghi lỗi và chuyển thư chết như mọi
                  // consumer.
                  processor.process(endpoint, record, ack);
                  return;
                }
                if (PeriodLockSagaListener.relevant(payload)) {
                  processor.process(endpoint, record, ack);
                } else if (ack != null) {
                  ack.acknowledge();
                }
              });
      ConcurrentMessageListenerContainer<String, byte[]> c =
          new ConcurrentMessageListenerContainer<>(
              new DefaultKafkaConsumerFactory<>(props), container);
      c.setAutoStartup(autoStartup);
      return c;
    }
  }

  /** Điểm nhận vết saga của {@link PeriodLockSagaListener}; không kiểm thứ tự phiên bản. */
  public static InboxEndpoint sagaEndpoint(PeriodLockSagaListener listener) {
    try {
      return new InboxEndpoint(
          EventTopics.SYS_AUDIT_V1,
          PeriodLockSagaListener.CONSUMER,
          null,
          listener,
          PeriodLockSagaListener.class.getMethod("on", AuditEvent.class, InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Nguồn {@code /api/v1/jobs/{id}} cho tác vụ khoá kỳ (mã saga). */
  @Bean
  PeriodLockSagaJobs periodLockSagaJobs(PeriodLockService periodLockService) {
    return new PeriodLockSagaJobs(periodLockService);
  }

  /** Nguồn {@code /api/v1/jobs/{id}} cho tác vụ chạy lại hồ sơ lỗi và bù trừ. */
  @Bean
  PeriodLockCommandJobs periodLockCommandJobs(PeriodLockControlService periodLockControlService) {
    return new PeriodLockCommandJobs(periodLockControlService);
  }
}
