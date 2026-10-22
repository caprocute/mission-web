package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.PeriodCalendarPort;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.infrastructure.scheduling.PeriodOpeningJob;
import vn.viettel.okrkpi.config.infrastructure.web.ConfigIdempotency;
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
  PeriodConfigService periodConfigService(
      JdbcPeriodRepository periodRepository,
      OutboxPeriodEvents periodEvents,
      ApplicationEventPublisher publisher,
      ObjectProvider<PeriodCalendarPort> calendar) {
    return new PeriodConfigService(
        periodRepository, periodEvents, publisher, calendar.getIfAvailable(), Clock.systemUtc());
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
