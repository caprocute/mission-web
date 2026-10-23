package vn.viettel.okrkpi.workflow.infrastructure.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.platform.reconcile.OrgReplicaReconciler;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;

/**
 * Dựng R1 (DL-12) cho bản sao tổ chức của workflow-service; tắt bằng {@code
 * mission.reconcile.org-replica.enabled=false} ({@code WF_RECONCILE_ORG_REPLICA_ENABLED}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "mission.reconcile.org-replica",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrgReplicaReconcileConfiguration {

  @Bean
  OrgReplicaReconciler orgReplicaReconciler(
      JdbcTemplate jdbc, ObjectProvider<MeterRegistry> meters, ObjectMapper json) {
    Clock clock = Clock.systemUTC();
    return new OrgReplicaReconciler(
        jdbc,
        OrgReplicaReconciler.DEFAULT_SCHEMA,
        new ReconcileMetrics(meters.getIfAvailable(SimpleMeterRegistry::new), clock),
        json,
        clock);
  }

  @Bean
  OrgReplicaReconcileListener orgReplicaReconcileListener(OrgReplicaReconciler reconciler) {
    return new OrgReplicaReconcileListener(reconciler);
  }

  @Bean
  OrgReplicaReconcileJob orgReplicaReconcileJob(
      OrgReplicaReconciler reconciler,
      JdbcTemplate jdbc,
      PlatformTransactionManager tm,
      @Value("${mission.reconcile.org-replica.settle:PT15M}") Duration settle) {
    return new OrgReplicaReconcileJob(reconciler, jdbc, tm, settle, Clock.systemUTC());
  }
}
