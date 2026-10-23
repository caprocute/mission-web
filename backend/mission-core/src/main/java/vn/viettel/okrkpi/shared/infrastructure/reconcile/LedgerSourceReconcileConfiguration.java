package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/**
 * Đối soát R2 sổ cái ↔ bảng nguồn (DL-12), bật khi khai {@code mission.reconcile.ledger.base-url}
 * (địa chỉ ledger-service). Các module đăng ký {@link LedgerSource} của mình; token dịch vụ lấy từ
 * client Keycloak của cell như lời gọi saga (wiki trang 14 mục 42).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mission.reconcile.ledger", name = "base-url")
public class LedgerSourceReconcileConfiguration {

  @Bean
  LedgerEntryLookup ledgerEntryLookup(
      ObjectProvider<RestClient.Builder> builder,
      ObjectProvider<KeycloakAdminClient> keycloak,
      @Value("${mission.reconcile.ledger.base-url}") String baseUrl) {
    return new HttpLedgerEntryLookup(
        builder.getIfAvailable(RestClient::builder),
        baseUrl,
        () -> {
          KeycloakAdminClient k = keycloak.getIfAvailable();
          if (k == null) {
            throw new DependencyUnavailableException(
                "keycloak", DependencyUnavailableException.Reason.IO_ERROR, null, null);
          }
          return k.serviceToken();
        });
  }

  @Bean
  LedgerSourceReconciler ledgerSourceReconciler(
      ObjectProvider<LedgerSource> sources,
      LedgerEntryLookup ledger,
      ObjectProvider<MeterRegistry> meters,
      ObjectMapper json,
      @Value("${mission.reconcile.ledger.sample-rate:0.01}") double sampleRate,
      @Value("${mission.reconcile.ledger.max-per-tenant:500}") int maxPerTenant,
      @Value("${mission.reconcile.ledger.grace:PT1H}") Duration grace) {
    Clock clock = Clock.systemUTC();
    return new LedgerSourceReconciler(
        sources.orderedStream().toList(),
        ledger,
        new ReconcileMetrics(meters.getIfAvailable(SimpleMeterRegistry::new), clock),
        json,
        clock,
        sampleRate,
        maxPerTenant,
        grace);
  }

  @Bean
  LedgerSourceReconcileJob ledgerSourceReconcileJob(
      LedgerSourceReconciler reconciler,
      ObjectProvider<TenantDirectory> directory,
      @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell,
      @Value("${mission.reconcile.ledger.zone:Asia/Ho_Chi_Minh}") String zone) {
    return new LedgerSourceReconcileJob(
        reconciler, directory, cell, ZoneId.of(zone), Clock.systemUTC());
  }
}
