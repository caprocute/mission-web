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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.PeriodCalendarPort;
import vn.viettel.okrkpi.config.application.HolidayCalendarService;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.application.PeriodStateCache;
import vn.viettel.okrkpi.config.infrastructure.listener.PeriodCacheEvictionListener;
import vn.viettel.okrkpi.config.infrastructure.scheduling.PeriodOpeningJob;
import vn.viettel.okrkpi.config.infrastructure.web.ConfigIdempotency;
import vn.viettel.okrkpi.config.infrastructure.web.RequestModule;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

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
    return service;
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
  ConfigIdempotency configIdempotency(
      @Qualifier("configDataSource") DataSource dataSource,
      @Qualifier("configTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new ConfigIdempotency(dataSource, tm, json.getIfAvailable(ObjectMapper::new));
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
