package vn.viettel.okrkpi.esign.infrastructure.sign;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import vn.viettel.okrkpi.esign.application.sign.EsignService;
import vn.viettel.okrkpi.esign.application.sign.ProviderRegistry;
import vn.viettel.okrkpi.esign.application.sign.SignatureEvents;
import vn.viettel.okrkpi.esign.application.sign.SourceDocumentPort;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRepository;
import vn.viettel.okrkpi.esign.domain.sign.ProviderCode;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestRepository;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/** Lắp esign-service (DV-10-01): kho JDBC, nhà cung cấp, cổng module nguồn, EVT-18, worker. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EsignConfiguration.EsignProperties.class)
public class EsignConfiguration {

  /**
   * Cấu hình {@code okrkpi.esign}: mã nhà cung cấp → {@code id()} của bản hiện thực, nhà cung cấp
   * mặc định, hạn xác nhận, địa chỉ cổng nội bộ của từng module nguồn, và nhà cung cấp theo tenant
   * ({@code tenants.[<mã tenant>].enabled}, {@code tenants.[<mã tenant>].default-provider},
   * DV-10-04).
   */
  @ConfigurationProperties(prefix = "okrkpi.esign")
  public record EsignProperties(
      Map<ProviderCode, String> providerIds,
      ProviderCode defaultProvider,
      Duration confirmWindow,
      Map<String, String> sources,
      Map<String, ProviderRegistry.TenantProviders> tenants) {
    public EsignProperties {
      providerIds = providerIds == null ? Map.of() : providerIds;
      defaultProvider = defaultProvider == null ? ProviderCode.MYSIGN : defaultProvider;
      confirmWindow = confirmWindow == null ? Duration.ofSeconds(120) : confirmWindow;
      sources = sources == null ? Map.of() : sources;
      tenants = tenants == null ? Map.of() : tenants;
    }
  }

  @Bean
  @ConditionalOnMissingBean
  Clock esignClock() {
    return Clock.systemUTC();
  }

  @Bean
  SignRequestRepository signRequestRepository(JdbcClient jdbc) {
    return new JdbcSignRequestRepository(jdbc);
  }

  @Bean
  CertificateRepository certificateRepository(JdbcClient jdbc) {
    return new JdbcCertificateRepository(jdbc);
  }

  @Bean
  ProviderRegistry providerRegistry(
      ObjectProvider<SignatureProvider> providers, EsignProperties properties) {
    Map<ProviderCode, String> ids = new EnumMap<>(ProviderCode.class);
    ids.putAll(properties.providerIds());
    Map<java.util.UUID, ProviderRegistry.TenantProviders> tenants = new HashMap<>();
    properties.tenants().forEach((id, t) -> tenants.put(java.util.UUID.fromString(id.trim()), t));
    return new ProviderRegistry(
        providers.orderedStream().toList(), ids, properties.defaultProvider(), tenants);
  }

  @Bean
  @ConditionalOnMissingBean(SourceDocumentPort.class)
  SourceDocumentPort sourceDocumentPort(
      ResilientClientFactory factory, EsignProperties properties) {
    Map<String, ResilientClient> clients = new HashMap<>();
    properties
        .sources()
        .forEach(
            (module, url) -> clients.put(module, factory.create("esign-source-" + module, url)));
    return new HttpSourceDocumentClient(clients);
  }

  @Bean
  SignatureEvents signatureEvents(OutboxWriter outbox) {
    return new OutboxSignatureEvents(outbox);
  }

  @Bean
  EsignService esignService(
      SignRequestRepository requests,
      CertificateRepository certificates,
      SourceDocumentPort sources,
      ProviderRegistry providers,
      SignatureEvents events,
      Clock clock,
      EsignProperties properties,
      @Value("${okrkpi.esign.batch.max-items:1000}") int maxItems,
      @Value("${okrkpi.esign.batch.max-attempts:5}") int maxAttempts,
      @Value("${okrkpi.esign.batch.backoff:2s}") Duration backoff,
      @Value("${okrkpi.esign.batch.max-backoff:5m}") Duration maxBackoff) {
    return new EsignService(
        requests,
        certificates,
        sources,
        providers,
        events,
        clock,
        properties.confirmWindow(),
        new EsignService.BatchSettings(maxItems, maxAttempts, backoff, maxBackoff));
  }

  /**
   * Worker ký lô {@code esign-batch-worker} (DV-10-06): chạy ở bản triển khai riêng bật {@code
   * okrkpi.esign.batch-worker.enabled}, để KEDA co giãn số bản sao theo hàng đợi mà không đụng tới
   * pod phục vụ API (suy luận).
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "okrkpi.esign.batch-worker",
      name = "enabled",
      havingValue = "true")
  static class BatchWorkerConfiguration {

    @Bean
    BatchItemScanner batchItemScanner(JdbcClient jdbc) {
      return new BatchItemScanner(jdbc);
    }

    @Bean
    SignBatchWorker signBatchWorker(
        BatchItemScanner scanner,
        EsignService service,
        io.micrometer.core.instrument.MeterRegistry meters,
        @Value("${okrkpi.esign.batch-worker.scan-size:200}") int scanSize,
        @Value("${okrkpi.esign.batch-worker.parallelism:16}") int parallelism) {
      return new SignBatchWorker(scanner, service, scanSize, parallelism, meters);
    }
  }

  /** Nhà cung cấp giả lập chỉ cho dev/test; từ chối khởi động nếu hồ sơ là production. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.esign.fake-provider", name = "enabled")
  static class FakeProviderConfiguration {

    static final String[] PRODUCTION_PROFILES = {"prod", "production"};

    @Bean
    FakeSignatureProvider fakeSignatureProvider(
        Environment environment,
        Clock clock,
        @Value("${okrkpi.esign.fake-provider.confirm-after:3s}") Duration confirmAfter) {
      requireNotProduction(environment);
      return new FakeSignatureProvider(clock, confirmAfter);
    }

    static void requireNotProduction(Environment environment) {
      boolean production =
          Arrays.stream(environment.getActiveProfiles())
              .map(p -> p.toLowerCase(Locale.ROOT))
              .anyMatch(p -> Arrays.asList(PRODUCTION_PROFILES).contains(p));
      if (production) {
        throw new IllegalStateException(
            "Không được bật nhà cung cấp chữ ký giả lập ở production (okrkpi.esign.fake-provider)");
      }
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "okrkpi.esign.worker",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class WorkerConfiguration {

    @Bean
    PendingRequestScanner pendingRequestScanner(JdbcClient jdbc) {
      return new PendingRequestScanner(jdbc);
    }

    @Bean
    SignPollingWorker signPollingWorker(
        PendingRequestScanner scanner,
        EsignService service,
        @Value("${okrkpi.esign.worker.batch-size:100}") int batchSize) {
      return new SignPollingWorker(scanner, service, batchSize);
    }
  }
}
