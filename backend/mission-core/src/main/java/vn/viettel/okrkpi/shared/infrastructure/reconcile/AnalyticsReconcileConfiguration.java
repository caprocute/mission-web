package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.shared.datasource.ModuleName;
import vn.viettel.okrkpi.shared.datasource.ReadWriteRoutingDataSource;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/**
 * Dựng job R5 (DL-12); chỉ bật khi cấu hình ClickHouse {@code
 * mission.reconcile.analytics.url|database|username|password}. Tài khoản ClickHouse chỉ cần quyền
 * đọc bảng sự kiện, không gắn row policy theo tenant (đọc mọi tenant của cell, gọi bằng GET nên chỉ
 * đọc).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mission.reconcile.analytics", name = "url")
public class AnalyticsReconcileConfiguration {

  @Bean
  AnalyticsReconciler analyticsReconciler(
      @Qualifier("moduleDataSources") Map<ModuleName, ReadWriteRoutingDataSource> dataSources,
      BeanFactory beans,
      ObjectProvider<MeterRegistry> meters,
      ObjectMapper json,
      @Value("${mission.reconcile.analytics.url}") URI url,
      @Value("${mission.reconcile.analytics.database}") String database,
      @Value("${mission.reconcile.analytics.username:}") String username,
      @Value("${mission.reconcile.analytics.password:}") String password) {
    List<OutboxKafkaReconciler.OutboxSchema> schemas = new ArrayList<>();
    for (ModuleName m : ModuleName.values()) {
      schemas.add(
          new OutboxKafkaReconciler.OutboxSchema(
              m.schema(),
              new JdbcTemplate(dataSources.get(m)),
              beans.getBean(m.schema() + "TransactionManager", PlatformTransactionManager.class)));
    }
    Clock clock = Clock.systemUTC();
    return new AnalyticsReconciler(
        AnalyticsReconciler.DEFAULT_PAIRS,
        schemas,
        new ClickHouseAggregates(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
            url,
            database,
            username,
            password),
        new ReconcileMetrics(meters.getIfAvailable(SimpleMeterRegistry::new), clock),
        json,
        clock);
  }

  @Bean
  AnalyticsReconcileJob analyticsReconcileJob(
      AnalyticsReconciler reconciler,
      ObjectProvider<TenantDirectory> directory,
      @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
    return new AnalyticsReconcileJob(reconciler, directory, cell);
  }
}
