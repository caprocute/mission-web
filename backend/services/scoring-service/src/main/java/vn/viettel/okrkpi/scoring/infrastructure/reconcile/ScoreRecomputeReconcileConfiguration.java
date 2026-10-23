package vn.viettel.okrkpi.scoring.infrastructure.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dựng job R4 (DL-12); bật bằng {@code mission.reconcile.recompute.enabled=true} (chart bật khi bộ
 * máy tính DV-07-06 đã nhận tác vụ {@code KIEM_TRA_TAI_LAP}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "mission.reconcile.recompute",
    name = "enabled",
    havingValue = "true")
public class ScoreRecomputeReconcileConfiguration {

  @Bean
  ScoreRecomputeReconciler scoreRecomputeReconciler(
      JdbcTemplate jdbc,
      PlatformTransactionManager tm,
      ScoreJobStore jobs,
      ObjectProvider<MeterRegistry> meters,
      ObjectMapper json,
      @Value("${mission.reconcile.recompute.rate:0.005}") double rate,
      @Value("${mission.reconcile.recompute.max-per-job:2000}") int maxPerJob,
      @Value("${mission.reconcile.recompute.lookback:P400D}") Duration lookback,
      @Value("${mission.reconcile.recompute.timeout:P2D}") Duration timeout) {
    Clock clock = Clock.systemUTC();
    return new ScoreRecomputeReconciler(
        jdbc,
        tm,
        jobs,
        new ReconcileMetrics(meters.getIfAvailable(SimpleMeterRegistry::new), clock),
        json,
        clock,
        rate,
        maxPerJob,
        lookback,
        timeout);
  }

  @Bean
  ScoreRecomputeReconcileJob scoreRecomputeReconcileJob(
      ScoreRecomputeReconciler reconciler,
      @Value("${mission.reconcile.recompute.tenants:}") String tenants) {
    List<TenantId> ids =
        Arrays.stream(tenants.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> TenantId.of(UUID.fromString(s)))
            .toList();
    return new ScoreRecomputeReconcileJob(reconciler, ids);
  }
}
