package vn.viettel.okrkpi.replica.infrastructure.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.replica.infrastructure.persistence.OrgReplicaJdbc;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/** Dựng job đối soát R1 (DL-12); tắt bằng {@code mission.reconcile.org-replica.enabled=false}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "mission.reconcile.org-replica",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrgReplicaReconcileConfiguration {

  @Bean
  OrgReplicaReconciler orgReplicaReconciler(
      OrgReplicaJdbc jdbc, ObjectProvider<MeterRegistry> meters, ObjectMapper json) {
    Clock clock = Clock.systemUTC();
    return new OrgReplicaReconciler(
        jdbc.template(),
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
      @Qualifier("orgReplicaTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<TenantDirectory> directory,
      @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell,
      @Value("${mission.reconcile.org-replica.settle:PT15M}") Duration settle) {
    return new OrgReplicaReconcileJob(reconciler, tm, directory, cell, settle, Clock.systemUTC());
  }
}
