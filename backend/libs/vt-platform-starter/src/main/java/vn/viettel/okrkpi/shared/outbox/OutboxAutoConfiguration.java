package vn.viettel.okrkpi.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.kernel.Clock;

/**
 * Auto-configuration outbox (NEN-011, ADR-03): bật khi service khai {@code okrkpi.outbox.schema} và
 * có {@link DataSource}. {@link OutboxWriter} ghi bằng {@link JdbcTemplate} trên chính kết nối của
 * giao dịch nghiệp vụ (JDBC hoặc JPA dùng chung DataSource).
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
      "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
      "vn.viettel.okrkpi.shared.audit.AuditAutoConfiguration"
    })
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "okrkpi.outbox", name = "schema")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public OutboxPayloadSerializer outboxPayloadSerializer(OutboxProperties properties) {
    if (properties.registry().url() == null || properties.registry().url().isBlank()) {
      throw new IllegalStateException(
          "Thiếu okrkpi.outbox.registry.url: OutboxWriter cần Apicurio Registry để tuần tự hoá sự"
              + " kiện");
    }
    return new ApicurioPayloadSerializer(
        properties.registry().url(), properties.registry().properties());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DataSource.class)
  public OutboxWriter outboxWriter(
      DataSource dataSource,
      OutboxProperties properties,
      OutboxPayloadSerializer outboxPayloadSerializer,
      ObjectProvider<ObjectMapper> objectMapper,
      ObjectProvider<Clock> clock) {
    return new OutboxWriter(
        new JdbcTemplate(dataSource),
        properties.schema(),
        outboxPayloadSerializer,
        objectMapper.getIfAvailable(ObjectMapper::new),
        clock.getIfAvailable(Clock::systemUtc));
  }
}
