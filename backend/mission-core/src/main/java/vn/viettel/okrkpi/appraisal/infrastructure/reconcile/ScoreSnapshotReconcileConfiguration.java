package vn.viettel.okrkpi.appraisal.infrastructure.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.shared.datasource.ModuleName;
import vn.viettel.okrkpi.shared.datasource.ReadWriteRoutingDataSource;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/**
 * Dựng R3 (DL-12). Cổng chặn ký số luôn bật (tắt bằng {@code
 * mission.reconcile.score.gate.enabled=false}); job chỉ bật khi cấu hình nguồn scoring chỉ đọc
 * {@code mission.reconcile.score.source.url|username|password|schema}.
 */
@Configuration(proxyBeanMethods = false)
public class ScoreSnapshotReconcileConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "mission.reconcile.score.gate",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  ScoreReconcileGate scoreReconcileGate(
      @Qualifier("moduleDataSources") Map<ModuleName, ReadWriteRoutingDataSource> dataSources,
      @Qualifier("appraisalTransactionManager") PlatformTransactionManager tm,
      ObjectMapper json) {
    return new ScoreReconcileGate(
        new JdbcTemplate(dataSources.get(ModuleName.APPRAISAL)), tm, json);
  }

  /** Job R3, cần nguồn scoring. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "mission.reconcile.score.source", name = "url")
  static class Job {

    /**
     * Pool chỉ đọc phía scoring; không khai báo là {@code DataSource} để không lẫn với nguồn chính.
     */
    static final class ScoringPool implements AutoCloseable {
      final HikariDataSource ds;

      ScoringPool(HikariDataSource ds) {
        this.ds = ds;
      }

      @Override
      public void close() {
        ds.close();
      }
    }

    @Bean
    ScoringPool scoreReconcileSource(
        @Value("${mission.reconcile.score.source.url}") String url,
        @Value("${mission.reconcile.score.source.username:}") String username,
        @Value("${mission.reconcile.score.source.password:}") String password) {
      HikariDataSource ds = new HikariDataSource();
      ds.setPoolName("reconcile-scoring");
      ds.setJdbcUrl(url);
      ds.setUsername(username);
      ds.setPassword(password);
      ds.setMaximumPoolSize(2);
      ds.setReadOnly(true);
      return new ScoringPool(ds);
    }

    @Bean
    ScoreSnapshotReconciler scoreSnapshotReconciler(
        @Qualifier("moduleDataSources") Map<ModuleName, ReadWriteRoutingDataSource> dataSources,
        @Qualifier("appraisalTransactionManager") PlatformTransactionManager tm,
        ScoringPool pool,
        @Value("${mission.reconcile.score.source.schema:scoring}") String schema,
        ObjectProvider<MeterRegistry> meters,
        ObjectMapper json) {
      Clock clock = Clock.systemUTC();
      return new ScoreSnapshotReconciler(
          new JdbcTemplate(dataSources.get(ModuleName.APPRAISAL)),
          tm,
          new ScoreSnapshotReconciler.ScoringSource(
              new JdbcTemplate(pool.ds), new DataSourceTransactionManager(pool.ds), schema),
          new ReconcileMetrics(meters.getIfAvailable(SimpleMeterRegistry::new), clock),
          json,
          clock);
    }

    @Bean
    ScoreSnapshotReconcileJob scoreSnapshotReconcileJob(
        ScoreSnapshotReconciler reconciler,
        @Qualifier("moduleDataSources") Map<ModuleName, ReadWriteRoutingDataSource> dataSources,
        @Qualifier("appraisalTransactionManager") PlatformTransactionManager tm,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell,
        @Value("${mission.reconcile.score.lookback:P35D}") Duration lookback) {
      return new ScoreSnapshotReconcileJob(
          reconciler,
          new JdbcTemplate(dataSources.get(ModuleName.APPRAISAL)),
          tm,
          directory,
          cell,
          lookback,
          Clock.systemUTC());
    }
  }
}
