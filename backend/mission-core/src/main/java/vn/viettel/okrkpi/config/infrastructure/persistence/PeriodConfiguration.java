package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.PeriodCalendarPort;
import vn.viettel.okrkpi.config.application.DashboardDefinitionService;
import vn.viettel.okrkpi.config.application.FeatureFlagService;
import vn.viettel.okrkpi.config.application.HolidayCalendarService;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.application.PeriodLockService;
import vn.viettel.okrkpi.config.application.PeriodReopenService;
import vn.viettel.okrkpi.config.application.PeriodStateCache;
import vn.viettel.okrkpi.config.application.TenantSettingService;
import vn.viettel.okrkpi.config.application.UserPreferenceService;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.LockOrderNotices;
import vn.viettel.okrkpi.config.application.port.PeriodLockSaga;
import vn.viettel.okrkpi.config.infrastructure.client.WorkflowPeriodLockSaga;
import vn.viettel.okrkpi.config.infrastructure.listener.PeriodCacheEvictionListener;
import vn.viettel.okrkpi.config.infrastructure.listener.PeriodReopenedListener;
import vn.viettel.okrkpi.config.infrastructure.scheduling.PeriodOpeningJob;
import vn.viettel.okrkpi.config.infrastructure.scheduling.PeriodReopenExpiryJob;
import vn.viettel.okrkpi.config.infrastructure.web.ConfigIdempotency;
import vn.viettel.okrkpi.config.infrastructure.web.RequestModule;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.authz.AuthzProblemHandler;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.infrastructure.workflow.WorkflowSagaClient;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/**
 * CORE-08-02: nối kỳ đánh giá của MOD-08 với {@code configDataSource}; outbox ghi {@code
 * config.outbox_event} cùng giao dịch {@code configTransactionManager}.
 */
@Configuration(proxyBeanMethods = false)
public class PeriodConfiguration {

  @Bean
  JdbcPeriodRepository periodRepository(
      @Qualifier("configDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcPeriodRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  OutboxPeriodEvents periodEvents(
      @Qualifier("configDataSource") DataSource dataSource,
      @Value("${mission.config.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxPeriodEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "config",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  PeriodStateCache periodStateCache(@Value("${mission.config.period-cache.ttl:30s}") Duration ttl) {
    return new PeriodStateCache(ttl);
  }

  @Bean
  PeriodConfigService periodConfigService(
      JdbcPeriodRepository periodRepository,
      OutboxPeriodEvents periodEvents,
      ApplicationEventPublisher publisher,
      ObjectProvider<PeriodCalendarPort> calendar,
      PeriodStateCache periodStateCache,
      JdbcPeriodReopenRepository periodReopenRepository,
      @Qualifier("configTransactionManager") PlatformTransactionManager tm) {
    TransactionTemplate reads = new TransactionTemplate(tm);
    reads.setReadOnly(true);
    PeriodConfigService service =
        new PeriodConfigService(
            periodRepository,
            periodEvents,
            publisher,
            calendar.getIfAvailable(),
            Clock.systemUtc(),
            periodStateCache,
            reads);
    service.setCallerModule(new RequestModule());
    service.setReopens(periodReopenRepository);
    return service;
  }

  @Bean
  JdbcPeriodReopenRepository periodReopenRepository(
      @Qualifier("configDataSource") DataSource dataSource) {
    return new JdbcPeriodReopenRepository(JdbcClient.create(dataSource));
  }

  /**
   * Mở lại hậu kiểm và khoá lại (CORE-08-06). Hợp đồng SVC-06 chưa có saga khoá lại phạm vi hẹp nên
   * MOD-08 khoá lại ngay trong giao dịch (wiki trang 14).
   */
  @Bean
  PeriodReopenService periodReopenService(
      JdbcPeriodReopenRepository periodReopenRepository,
      JdbcPeriodRepository periodRepository,
      PeriodConfigService periodConfigService,
      HolidayCalendarService holidayCalendarService,
      ApplicationEventPublisher publisher,
      @Value("${mission.config.period-reopen.open-working-days:10}") int openWorkingDays) {
    return new PeriodReopenService(
        periodReopenRepository,
        periodRepository,
        periodConfigService,
        holidayCalendarService,
        null,
        publisher,
        Clock.systemUtc(),
        openWorkingDays);
  }

  /**
   * Consumer EVT-22 qua inbox của schema config (cùng giao dịch với ghi kỳ). Tắt bằng {@code
   * mission.config.period-reopen.listener.enabled=false}; khởi động theo {@code
   * okrkpi.inbox.listener-auto-startup}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
  @ConditionalOnProperty(
      prefix = "mission.config.period-reopen.listener",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class ReopenInbox {

    @Bean
    PeriodReopenedListener periodReopenedListener(PeriodReopenService periodReopenService) {
      return new PeriodReopenedListener(periodReopenService);
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> periodReopenedContainer(
        PeriodReopenedListener listener,
        @Qualifier("configDataSource") DataSource dataSource,
        @Qualifier("configTransactionManager") PlatformTransactionManager tm,
        InboxProperties properties,
        InboxPayloadDeserializer deserializer,
        InboxRetryDlqPublisher retryDlqPublisher,
        InboxMetrics metrics,
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        @Value("${okrkpi.inbox.listener-auto-startup:true}") boolean autoStartup) {
      InboxMessageProcessor processor =
          configInboxProcessor(
              dataSource, tm, properties, deserializer, retryDlqPublisher, metrics);
      InboxEndpoint endpoint = reopenEndpoint(listener);
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + PeriodReopenedListener.CONSUMER);
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container =
          new ContainerProperties(EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM);
      container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
      container.setMessageListener(
          (AcknowledgingMessageListener<String, byte[]>)
              (record, ack) -> processor.process(endpoint, record, ack));
      ConcurrentMessageListenerContainer<String, byte[]> c =
          new ConcurrentMessageListenerContainer<>(
              new DefaultKafkaConsumerFactory<>(props), container);
      c.setAutoStartup(autoStartup);
      return c;
    }
  }

  /** Bộ xử lý inbox ghi {@code config.inbox_*} bằng nguồn dữ liệu và giao dịch của config. */
  public static InboxMessageProcessor configInboxProcessor(
      DataSource dataSource,
      PlatformTransactionManager tm,
      InboxProperties properties,
      InboxPayloadDeserializer deserializer,
      InboxRetryDlqPublisher retryDlqPublisher,
      InboxMetrics metrics) {
    return new InboxMessageProcessor(
        new JdbcTemplate(dataSource),
        tm,
        "config",
        deserializer,
        retryDlqPublisher,
        metrics,
        properties == null ? null : properties.retry(),
        Clock.systemUtc());
  }

  /** Điểm nhận EVT-22 của {@link PeriodReopenedListener}. */
  public static InboxEndpoint reopenEndpoint(PeriodReopenedListener listener) {
    try {
      return new InboxEndpoint(
          EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
          PeriodReopenedListener.CONSUMER,
          PeriodReopenedListener.AGGREGATE_TYPE,
          listener,
          PeriodReopenedListener.class.getMethod(
              "on", KyDaMoLaiHauKiem.class, InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Consumer xoá đệm trạng thái kỳ khi pod khác đổi trạng thái kỳ (EVT-09, EVT-26). Mỗi pod một
   * consumer group, đọc từ offset mới nhất; tắt bằng {@code
   * mission.config.period-cache.listener.enabled=false}.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "mission.config.period-cache.listener",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  ConcurrentMessageListenerContainer<String, byte[]> periodCacheEvictionContainer(
      PeriodConfigService periodConfigService,
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
      @Value("${HOSTNAME:local}") String host) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(
        ConsumerConfig.GROUP_ID_CONFIG,
        "mission-core-period-cache-" + host + "-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    ContainerProperties container =
        new ContainerProperties(EventTopics.EVT_09_KY_DA_KHOA, EventTopics.EVT_26_KY_DA_HUY_KHOA);
    container.setMessageListener(new PeriodCacheEvictionListener(periodConfigService));
    return new ConcurrentMessageListenerContainer<>(
        new DefaultKafkaConsumerFactory<>(props), container);
  }

  @Bean
  JdbcHolidayCalendarRepository holidayCalendarRepository(
      @Qualifier("configDataSource") DataSource dataSource) {
    return new JdbcHolidayCalendarRepository(JdbcClient.create(dataSource));
  }

  /** CORE-08-04: lịch nghỉ, làm bù và thư viện ngày làm việc ({@code WorkingCalendarApi}). */
  @Bean
  HolidayCalendarService holidayCalendarService(
      JdbcHolidayCalendarRepository holidayCalendarRepository,
      ApplicationEventPublisher publisher,
      @Qualifier("configTransactionManager") PlatformTransactionManager tm,
      @Value("${mission.config.working-calendar.ttl:60s}") Duration ttl) {
    TransactionTemplate reads = new TransactionTemplate(tm);
    reads.setReadOnly(true);
    return new HolidayCalendarService(holidayCalendarRepository, publisher, reads, ttl);
  }

  @Bean
  JdbcLockOrderRepository lockOrderRepository(
      @Qualifier("configDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcLockOrderRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  PeriodLockSaga periodLockSaga(ObjectProvider<WorkflowSagaClient> client) {
    return new WorkflowPeriodLockSaga(client);
  }

  /** EVT-21 của lệnh khoá kỳ qua {@code config.outbox_event}. */
  @Bean
  LockOrderNotices lockOrderNotices(
      @Qualifier("configDataSource") DataSource dataSource,
      @Value("${mission.config.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxLockOrderNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "config",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  /** CORE-08-05: lệnh khoá kỳ. */
  @Bean
  PeriodLockService periodLockService(
      JdbcLockOrderRepository lockOrderRepository,
      JdbcPeriodRepository periodRepository,
      PeriodLockSaga periodLockSaga,
      LockOrderNotices lockOrderNotices,
      HolidayCalendarService holidayCalendarService,
      @Qualifier("configDataSource") DataSource dataSource) {
    return new PeriodLockService(
        lockOrderRepository,
        periodRepository,
        periodLockSaga,
        lockOrderNotices,
        new JdbcRoleHolders(JdbcClient.create(dataSource)),
        holidayCalendarService,
        Clock.systemUtc());
  }

  /** CORE-08-07: sổ cái thay đổi cờ tính năng và ngưỡng bất thường ({@code sys.audit.v1}). */
  @Bean
  OutboxConfigAudit configAudit(
      @Qualifier("configDataSource") DataSource dataSource,
      @Value("${mission.config.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxConfigAudit(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "config",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  JdbcFeatureFlagRepository featureFlagRepository(
      @Qualifier("configDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcFeatureFlagRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  JdbcTenantSettingRepository tenantSettingRepository(
      @Qualifier("configDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcTenantSettingRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  /** CORE-08-07: cờ tính năng ({@code FeatureFlagApi}), đệm theo tenant. */
  @Bean
  FeatureFlagService featureFlagService(
      JdbcFeatureFlagRepository featureFlagRepository,
      ConfigAudit configAudit,
      @Qualifier("configTransactionManager") PlatformTransactionManager tm,
      @Value("${mission.config.feature-flag-cache.ttl:30s}") Duration ttl) {
    TransactionTemplate reads = new TransactionTemplate(tm);
    reads.setReadOnly(true);
    return new FeatureFlagService(
        featureFlagRepository, configAudit, reads, Clock.systemUtc(), ttl);
  }

  /** CORE-08-07: cấu hình tenant ({@code TenantSettingApi}) và ngưỡng bất thường. */
  @Bean
  TenantSettingService tenantSettingService(
      JdbcTenantSettingRepository tenantSettingRepository,
      ConfigAudit configAudit,
      ObjectProvider<ObjectMapper> json) {
    return new TenantSettingService(
        tenantSettingRepository,
        configAudit,
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc());
  }

  /** CORE-08-17: kho định nghĩa dashboard tự dựng cho analytics-api. */
  @Bean
  DashboardDefinitionService dashboardDefinitionService(
      @Qualifier("configDataSource") DataSource dataSource,
      ConfigAudit configAudit,
      ObjectProvider<ObjectMapper> json) {
    return new DashboardDefinitionService(
        new JdbcDashboardDefinitionRepository(JdbcClient.create(dataSource)),
        configAudit,
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc());
  }

  /** CNH-02: tuỳ chọn cá nhân và bộ lọc đã lưu của người dùng. */
  @Bean
  UserPreferenceService userPreferenceService(
      @Qualifier("configDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new UserPreferenceService(
        new JdbcUserPreferenceRepository(JdbcClient.create(dataSource)),
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc());
  }

  @Bean
  ConfigIdempotency configIdempotency(
      @Qualifier("configDataSource") DataSource dataSource,
      @Qualifier("configTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new ConfigIdempotency(dataSource, tm, json.getIfAvailable(ObjectMapper::new));
  }

  /**
   * Bộ chuyển {@code StepUpRequiredException} thành 401 kèm {@code WWW-Authenticate} (RFC 9470) của
   * thư viện nền. Cấu hình tự động của nó cần lớp JWT của Spring Security, mà mission-core chưa có,
   * nên lệnh khoá kỳ (BR-08-02) đăng ký lại ở đây; có bản tự động thì bỏ qua bản này.
   */
  @Bean
  @ConditionalOnMissingBean
  AuthzProblemHandler authzProblemHandler() {
    return new AuthzProblemHandler();
  }

  /**
   * Timer hết hạn mở lại hậu kiểm; tắt bằng {@code
   * mission.config.period-reopen.expiry.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.config.period-reopen.expiry",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class ReopenExpiry {

    @Bean
    PeriodReopenExpiryJob periodReopenExpiryJob(
        PeriodReopenService periodReopenService,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new PeriodReopenExpiryJob(periodReopenService, directory, cell);
    }
  }

  /** Tác vụ mở kỳ; tắt bằng {@code mission.config.period-opening.enabled=false}. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.config.period-opening",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Opening {

    @Bean
    PeriodOpeningJob periodOpeningJob(
        PeriodConfigService periodConfigService,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new PeriodOpeningJob(periodConfigService, directory, cell);
    }
  }
}
