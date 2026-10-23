package vn.viettel.okrkpi.evidence.infrastructure.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
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
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Dựng job R6 (DL-12); bật bằng {@code mission.reconcile.evidence.enabled=true}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "mission.reconcile.evidence",
    name = "enabled",
    havingValue = "true")
public class EvidenceObjectReconcileConfiguration {

  @Bean
  EvidenceObjectReconciler evidenceObjectReconciler(
      JdbcTemplate jdbc,
      PlatformTransactionManager tm,
      ObjectStorage storage,
      ObjectProvider<MeterRegistry> meters,
      ObjectMapper json,
      @Value("${mission.reconcile.evidence.rate:0.001}") double rate,
      @Value("${mission.reconcile.evidence.max-sample:5000}") int maxSample) {
    Clock clock = Clock.systemUTC();
    return new EvidenceObjectReconciler(
        jdbc,
        tm,
        storage,
        new ReconcileMetrics(meters.getIfAvailable(SimpleMeterRegistry::new), clock),
        json,
        clock,
        rate,
        maxSample);
  }

  @Bean
  EvidenceObjectReconcileJob evidenceObjectReconcileJob(
      EvidenceObjectReconciler reconciler,
      @Value("${mission.reconcile.evidence.tenants:}") String tenants) {
    List<TenantId> ids =
        Arrays.stream(tenants.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> TenantId.of(UUID.fromString(s)))
            .toList();
    return new EvidenceObjectReconcileJob(reconciler, ids);
  }
}
