package vn.viettel.okrkpi.esign.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Chỉ báo sẵn sàng (readiness) của service trên cổng quản trị 8081 (TL-04 WI-20): kiểm tra kết nối
 * pool CSDL qua {@link JdbcClient} và trạng thái sẵn sàng của Kafka producer. Không bao giờ gắn vào
 * nhóm {@code liveness}.
 */
@Component("templateReadiness")
public class TemplateReadinessIndicator implements HealthIndicator {

  private final JdbcClient jdbcClient;
  private final boolean kafkaProducerReady;

  public TemplateReadinessIndicator(
      JdbcClient jdbcClient,
      @Value("${mission.service.kafka.producer-ready:true}") boolean kafkaProducerReady) {
    this.jdbcClient = jdbcClient;
    this.kafkaProducerReady = kafkaProducerReady;
  }

  @Override
  public Health health() {
    Integer probe = jdbcClient.sql("SELECT 1").query(Integer.class).single();
    if (probe == null || probe != 1) {
      return Health.down().withDetail("databasePool", "UNREACHABLE").build();
    }
    if (!kafkaProducerReady) {
      return Health.down()
          .withDetail("databasePool", "UP")
          .withDetail("kafkaProducer", "NOT_READY")
          .build();
    }
    return Health.up()
        .withDetail("databasePool", "UP")
        .withDetail("kafkaProducer", "READY")
        .build();
  }
}
