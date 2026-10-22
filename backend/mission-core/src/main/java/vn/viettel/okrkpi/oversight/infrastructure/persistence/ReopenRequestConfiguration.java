package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.oversight.application.port.ReopenImpactSimulator;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService;
import vn.viettel.okrkpi.oversight.infrastructure.web.OversightIdempotency;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Yêu cầu mở lại kỳ (CORE-07-07): kho JDBC trên {@code oversightDataSource}, dịch vụ ứng dụng, sổ
 * cái qua {@code oversight.outbox_event} và Idempotency-Key trên {@code oversight.idempotency_key}.
 * Bộ mô phỏng {@link ReopenImpactSimulator} là bean tuỳ chọn (DV-07-08).
 */
@Configuration(proxyBeanMethods = false)
public class ReopenRequestConfiguration {

  @Bean
  JdbcReopenRequestRepository reopenRequestRepository(
      @Qualifier("oversightDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcReopenRequestRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  ReopenRequestService reopenRequestService(
      JdbcReopenRequestRepository repository,
      PeriodApi periods,
      ObjectProvider<WorkingCalendarApi> calendar,
      ObjectProvider<AccessDirectory> directory,
      ObjectProvider<ReopenImpactSimulator> simulator,
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Value("${mission.oversight.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    OutboxWriter outbox =
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "oversight",
            serializer.getIfAvailable(() -> new ApicurioPayloadSerializer(registry, Map.of())),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc());
    return new ReopenRequestService(
        repository,
        periods,
        calendar.getIfAvailable(),
        directory.getIfAvailable(),
        new OutboxOversightAudit(outbox),
        simulator.getIfAvailable(),
        java.time.Clock.systemUTC());
  }

  @Bean
  OversightIdempotency oversightIdempotency(
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Qualifier("oversightTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new OversightIdempotency(dataSource, tm, json.getIfAvailable(ObjectMapper::new));
  }
}
