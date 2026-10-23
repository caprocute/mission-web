package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.platform.reconcile.KafkaTopicCounter;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.TopicCounter;
import vn.viettel.okrkpi.shared.datasource.ModuleName;
import vn.viettel.okrkpi.shared.datasource.ReadWriteRoutingDataSource;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/**
 * Dựng job đối soát R7 (DL-12) cho tám schema nghiệp vụ của mission-core; tắt bằng {@code
 * mission.reconcile.outbox.enabled=false}. Kết nối Kafka Admin tạo lười ở lần đếm đầu tiên từ
 * {@code KafkaAdmin} của Spring Kafka; không có {@code KafkaAdmin} thì lần chạy ghi cảnh báo vào
 * nhật ký.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "mission.reconcile.outbox",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxKafkaReconcileConfiguration {

  /** Bộ đếm Kafka tạo {@link Admin} ở lần dùng đầu, đóng khi tắt ứng dụng. */
  static final class LazyKafkaCounter implements TopicCounter, DisposableBean {

    private final ObjectProvider<KafkaAdmin> kafkaAdmin;
    private volatile Admin admin;
    private volatile KafkaTopicCounter counter;

    LazyKafkaCounter(ObjectProvider<KafkaAdmin> kafkaAdmin) {
      this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public long count(String topic, Instant from, Instant to) {
      KafkaTopicCounter c = counter;
      if (c == null) {
        synchronized (this) {
          if (counter == null) {
            KafkaAdmin ka = kafkaAdmin.getIfAvailable();
            if (ka == null) {
              throw new IllegalStateException("Chưa cấu hình Kafka (spring.kafka) cho đối soát R7");
            }
            admin = Admin.create(ka.getConfigurationProperties());
            counter = new KafkaTopicCounter(admin, Duration.ofSeconds(30));
          }
          c = counter;
        }
      }
      return c.count(topic, from, to);
    }

    @Override
    public void destroy() {
      if (admin != null) {
        admin.close(Duration.ofSeconds(5));
      }
    }
  }

  @Bean
  LazyKafkaCounter reconcileKafkaCounter(ObjectProvider<KafkaAdmin> kafkaAdmin) {
    return new LazyKafkaCounter(kafkaAdmin);
  }

  @Bean
  OutboxKafkaReconciler outboxKafkaReconciler(
      @Qualifier("moduleDataSources") Map<ModuleName, ReadWriteRoutingDataSource> dataSources,
      BeanFactory beans,
      LazyKafkaCounter kafka,
      ObjectProvider<MeterRegistry> meters,
      ObjectMapper json,
      @Value("${mission.reconcile.outbox.grace:PT5M}") Duration grace,
      @Value("${mission.reconcile.outbox.auto-replay:true}") boolean autoReplay,
      @Value("${mission.reconcile.outbox.replay-limit:50000}") int replayLimit) {
    List<OutboxKafkaReconciler.OutboxSchema> schemas = new ArrayList<>();
    for (ModuleName m : ModuleName.values()) {
      schemas.add(
          new OutboxKafkaReconciler.OutboxSchema(
              m.schema(),
              new JdbcTemplate(dataSources.get(m)),
              beans.getBean(m.schema() + "TransactionManager", PlatformTransactionManager.class)));
    }
    Clock clock = Clock.systemUTC();
    return new OutboxKafkaReconciler(
        schemas,
        kafka,
        new ReconcileMetrics(meters.getIfAvailable(SimpleMeterRegistry::new), clock),
        json,
        clock,
        grace,
        autoReplay,
        replayLimit);
  }

  @Bean
  OutboxKafkaReconcileJob outboxKafkaReconcileJob(
      OutboxKafkaReconciler reconciler,
      ObjectProvider<TenantDirectory> directory,
      @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
    return new OutboxKafkaReconcileJob(reconciler, directory, cell, Clock.systemUTC());
  }
}
