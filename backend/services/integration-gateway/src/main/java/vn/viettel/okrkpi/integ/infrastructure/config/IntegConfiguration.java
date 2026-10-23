package vn.viettel.okrkpi.integ.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.camel.RoutesBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.integ.application.DeadLetterService;
import vn.viettel.okrkpi.integ.application.EdxmlTransferService;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.InboundPipeline;
import vn.viettel.okrkpi.integ.application.IndicatorSource;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSearch;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource;
import vn.viettel.okrkpi.integ.application.KrSourceValueService;
import vn.viettel.okrkpi.integ.application.MappingCommands;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.OutboundBinding;
import vn.viettel.okrkpi.integ.application.OutboundPipeline;
import vn.viettel.okrkpi.integ.application.OutboundSender;
import vn.viettel.okrkpi.integ.application.PullService;
import vn.viettel.okrkpi.integ.application.SyncCursorService;
import vn.viettel.okrkpi.integ.application.Translators;
import vn.viettel.okrkpi.integ.application.port.CanonicalEventSink;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.DeadLetters;
import vn.viettel.okrkpi.integ.application.port.InboxMessages;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.integ.application.port.SyncCursors;
import vn.viettel.okrkpi.integ.domain.RetrySchedule;
import vn.viettel.okrkpi.integ.infrastructure.camel.CompositeRoutes;
import vn.viettel.okrkpi.integ.infrastructure.camel.DeadLetterRoute;
import vn.viettel.okrkpi.integ.infrastructure.camel.InboxIdempotentRepository;
import vn.viettel.okrkpi.integ.infrastructure.camel.OutboundDispatchRoute;
import vn.viettel.okrkpi.integ.infrastructure.camel.OutboundEventRoute;
import vn.viettel.okrkpi.integ.infrastructure.camel.PollingConnectorRoute;
import vn.viettel.okrkpi.integ.infrastructure.camel.RouteSettings;
import vn.viettel.okrkpi.integ.infrastructure.crypto.FieldEncryptorPayloadCipher;
import vn.viettel.okrkpi.integ.infrastructure.events.OutboxCanonicalEventSink;
import vn.viettel.okrkpi.integ.infrastructure.hrm.HrmConfiguration.HrmBeans;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcConnectors;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcDeadLetters;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcInboxMessages;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcMappingXrefs;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcOutboundMessages;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcSyncCursors;
import vn.viettel.okrkpi.integ.infrastructure.secrets.FileConnectorSecrets;
import vn.viettel.okrkpi.integ.infrastructure.truc.TrucConfiguration.TrucBeans;
import vn.viettel.okrkpi.integ.infrastructure.truc.TrucEdxmlComposer;
import vn.viettel.okrkpi.integ.infrastructure.voffice.VOfficeConfiguration.VOfficeBeans;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Lắp khung integration-gateway (DV-13-01): cổng JDBC trên schema {@code integ}, dịch vụ ứng dụng,
 * route Camel chung (thư chết, kéo theo lịch cho mỗi {@link InboundConnector}, gửi ra, consumer sự
 * kiện cho mỗi {@link OutboundBinding}). Bộ kết nối cụ thể (DV-13-03..08) chỉ khai báo bean adapter
 * và translator.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RouteSettings.class)
public class IntegConfiguration {

  @Bean
  Connectors integConnectors(JdbcClient jdbc) {
    return new JdbcConnectors(jdbc);
  }

  @Bean
  InboxMessages integInboxMessages(JdbcClient jdbc) {
    return new JdbcInboxMessages(jdbc);
  }

  @Bean
  MappingXrefs integMappingXrefs(JdbcClient jdbc) {
    return new JdbcMappingXrefs(jdbc);
  }

  @Bean
  SyncCursors integSyncCursors(JdbcClient jdbc) {
    return new JdbcSyncCursors(jdbc);
  }

  @Bean
  DeadLetters integDeadLetters(JdbcClient jdbc) {
    return new JdbcDeadLetters(jdbc);
  }

  @Bean
  OutboundMessages integOutboundMessages(JdbcClient jdbc) {
    return new JdbcOutboundMessages(jdbc);
  }

  /** Bộ mã hoá NEN-015 lấy lúc dùng, để cấu hình tắt mã hoá (kiểm thử) vẫn khởi động được. */
  @Bean
  PayloadCipher integPayloadCipher(ObjectProvider<FieldEncryptor> encryptor) {
    return new PayloadCipher() {
      @Override
      public Sealed seal(
          vn.viettel.okrkpi.shared.kernel.TenantId tenant,
          String column,
          java.util.UUID rowId,
          byte[] plain) {
        return new FieldEncryptorPayloadCipher(encryptor.getObject())
            .seal(tenant, column, rowId, plain);
      }

      @Override
      public byte[] open(
          vn.viettel.okrkpi.shared.kernel.TenantId tenant,
          String column,
          java.util.UUID rowId,
          byte[] ciphertext) {
        return new FieldEncryptorPayloadCipher(encryptor.getObject())
            .open(tenant, column, rowId, ciphertext);
      }
    };
  }

  @Bean
  CanonicalEventSink integCanonicalEventSink(OutboxWriter outbox) {
    return new OutboxCanonicalEventSink(outbox);
  }

  @Bean
  TransactionTemplate integTransactions(PlatformTransactionManager transactions) {
    return new TransactionTemplate(transactions);
  }

  @Bean
  MappingXrefService integMappingXrefService(MappingXrefs xrefs) {
    return new MappingXrefService(xrefs);
  }

  @Bean
  SyncCursorService integSyncCursorService(
      SyncCursors cursors, ObjectProvider<Clock> clock, TransactionTemplate integTransactions) {
    return new SyncCursorService(
        cursors, clock.getIfAvailable(Clock::systemUTC), integTransactions);
  }

  @Bean
  Translators integTranslators(
      ObjectProvider<InboundConnector> connectors,
      ObjectProvider<VOfficeBeans> voffice,
      ObjectProvider<HrmBeans> hrm,
      ObjectProvider<TrucBeans> truc) {
    return new Translators(inboundConnectors(connectors, voffice, hrm, truc), Map.of());
  }

  /**
   * Bộ kết nối vào khai báo thẳng là bean cộng với các bộ kiểu vOffice dựng theo danh sách mã và
   * các bộ HRM dựng theo hồ sơ (DV-13-08) và các bộ Trục liên thông (DV-13-05).
   */
  static List<InboundConnector> inboundConnectors(
      ObjectProvider<InboundConnector> connectors,
      ObjectProvider<VOfficeBeans> voffice,
      ObjectProvider<HrmBeans> hrm,
      ObjectProvider<TrucBeans> truc) {
    List<InboundConnector> all = new ArrayList<>(connectors.orderedStream().toList());
    voffice.ifAvailable(v -> all.addAll(v.connectors()));
    hrm.ifAvailable(h -> all.addAll(h.connectors()));
    truc.ifAvailable(t -> all.addAll(t.connectors()));
    return all;
  }

  /** Luồng ảo cho lời gọi nguồn số liệu, để bộ giới hạn thời gian ngắt được lời gọi chậm. */
  @Bean(destroyMethod = "shutdownNow")
  ExecutorService integSourceExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /** Số liệu nguồn của KR (DV-13-08): cắt ở thời hạn, ngắt mạch theo tenant và kết nối. */
  @Bean
  KrSourceValueService integKrSourceValueService(
      Connectors connectors,
      MappingXrefs xrefs,
      ObjectProvider<IndicatorSource> sources,
      ObjectProvider<HrmBeans> hrm,
      TransactionTemplate integTransactions,
      ObjectProvider<Clock> clock,
      ExecutorService integSourceExecutor,
      @Value("${okrkpi.integ.kr-source.timeout:PT2.5S}") Duration timeout,
      @Value("${okrkpi.integ.kr-source.breaker-open:PT30S}") Duration open) {
    List<IndicatorSource> all = new ArrayList<>(sources.orderedStream().toList());
    hrm.ifAvailable(h -> all.addAll(h.indicators()));
    CircuitBreakerRegistry breakers =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(open)
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
    return new KrSourceValueService(
        connectors,
        xrefs,
        all,
        integTransactions,
        clock.getIfAvailable(Clock::systemUTC),
        timeout,
        breakers,
        integSourceExecutor);
  }

  @Bean
  MappingCommands integMappingCommands(
      Connectors connectors,
      MappingXrefService mappings,
      TransactionTemplate integTransactions,
      ObjectProvider<Clock> clock) {
    return new MappingCommands(
        connectors, mappings, integTransactions, clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  ConnectorSecrets integConnectorSecrets(@Value("${okrkpi.integ.secrets-dir:}") String secretsDir) {
    return new FileConnectorSecrets(secretsDir.isBlank() ? null : Path.of(secretsDir));
  }

  @Bean
  IssuedDocumentSearch integIssuedDocumentSearch(
      Connectors connectors,
      MappingXrefService mappings,
      ObjectProvider<IssuedDocumentSource> sources,
      ObjectProvider<VOfficeBeans> voffice,
      TransactionTemplate integTransactions,
      ObjectProvider<Clock> clock) {
    List<IssuedDocumentSource> all = new ArrayList<>(sources.orderedStream().toList());
    voffice.ifAvailable(v -> all.addAll(v.sources()));
    return new IssuedDocumentSearch(
        connectors, mappings, all, integTransactions, clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  InboundPipeline integInboundPipeline(
      Connectors connectors,
      InboxMessages inbox,
      DeadLetters deadLetters,
      PayloadCipher cipher,
      CanonicalEventSink sink,
      MappingXrefService mappings,
      Translators translators,
      TransactionTemplate integTransactions,
      ObjectProvider<Clock> clock,
      @Value("${okrkpi.integ.inbox.dedupe-window-days:7}") int windowDays) {
    return new InboundPipeline(
        connectors,
        inbox,
        deadLetters,
        cipher,
        sink,
        mappings,
        translators,
        integTransactions,
        clock.getIfAvailable(Clock::systemUTC),
        windowDays);
  }

  @Bean
  OutboundPipeline integOutboundPipeline(
      Connectors connectors,
      OutboundMessages messages,
      InboxMessages inbox,
      DeadLetters deadLetters,
      PayloadCipher cipher,
      ObjectProvider<OutboundSender> senders,
      ObjectProvider<TrucBeans> truc,
      RouteSettings settings,
      TransactionTemplate integTransactions,
      ObjectProvider<Clock> clock,
      @Value("${okrkpi.integ.outbound.ack-timeout:PT10M}") Duration ackTimeout) {
    RetrySchedule schedule =
        new RetrySchedule(
            settings.redeliveryDelay(),
            settings.backOffMultiplier().intValue(),
            Duration.ofHours(1),
            settings.maxRedeliveries());
    List<OutboundSender> all = new ArrayList<>(senders.orderedStream().toList());
    truc.ifAvailable(t -> all.addAll(t.senders()));
    return new OutboundPipeline(
        connectors,
        messages,
        inbox,
        deadLetters,
        cipher,
        all,
        schedule,
        ackTimeout,
        integTransactions,
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  DeadLetterService integDeadLetterService(
      DeadLetters letters,
      InboundPipeline inbound,
      OutboundPipeline outbound,
      TransactionTemplate integTransactions,
      ObjectProvider<Clock> clock) {
    return new DeadLetterService(
        letters, inbound, outbound, integTransactions, clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  PullService integPullService(
      Connectors connectors, SyncCursorService cursors, TransactionTemplate integTransactions) {
    return new PullService(connectors, cursors, integTransactions);
  }

  @Bean
  InboxIdempotentRepository integIdempotentRepository(InboundPipeline pipeline) {
    return new InboxIdempotentRepository(pipeline);
  }

  @Bean
  RoutesBuilder integDeadLetterRoute(DeadLetterService deadLetters) {
    return new DeadLetterRoute(deadLetters);
  }

  /** Một route kéo cho mỗi bộ kết nối khai báo {@link InboundConnector}. */
  @Bean
  @ConditionalOnProperty(
      prefix = "okrkpi.integ.pull",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  RoutesBuilder integPollingRoutes(
      RouteSettings settings,
      ObjectProvider<InboundConnector> connectors,
      ObjectProvider<VOfficeBeans> voffice,
      ObjectProvider<HrmBeans> hrm,
      ObjectProvider<TrucBeans> truc,
      PullService pulls,
      InboundPipeline pipeline,
      InboxIdempotentRepository idempotent) {
    return new CompositeRoutes(
        inboundConnectors(connectors, voffice, hrm, truc).stream()
            .map(source -> new PollingConnectorRoute(settings, source, pulls, pipeline, idempotent))
            .toList());
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "okrkpi.integ.outbound.dispatch",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  RoutesBuilder integOutboundDispatchRoute(
      OutboundPipeline pipeline,
      @Value("${okrkpi.integ.outbound.dispatch.period:PT5S}") Duration period,
      @Value("${okrkpi.integ.outbound.dispatch.batch:100}") int batch,
      @Value("${okrkpi.integ.outbound.dispatch.include-codes:}") Set<String> include,
      @Value("${okrkpi.integ.outbound.dispatch.exclude-codes:}") Set<String> exclude) {
    Set<String> in = nonBlank(include);
    return in.isEmpty()
        ? new OutboundDispatchRoute(pipeline, period, batch, nonBlank(exclude), false)
        : new OutboundDispatchRoute(pipeline, period, batch, in, true);
  }

  private static Set<String> nonBlank(Set<String> codes) {
    return codes == null
        ? Set.of()
        : codes.stream()
            .map(String::trim)
            .filter(c -> !c.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /**
   * Gửi văn bản EdXML ra Trục liên thông cho MOD-06 (DV-13-05, hợp đồng thống nhất với CORE-06-08).
   */
  @Bean
  EdxmlTransferService integEdxmlTransferService(
      Connectors connectors,
      OutboundPipeline pipeline,
      OutboundMessages messages,
      DeadLetters deadLetters,
      ObjectProvider<Clock> clock,
      TransactionTemplate integTransactions,
      @Value("${okrkpi.integ.truc.outbound-codes:BTC_TW,VPTW}") Set<String> destinations,
      @Value("${okrkpi.integ.truc.transfer-max-bytes:20971520}") long maxBytes) {
    return new EdxmlTransferService(
        connectors,
        pipeline,
        messages,
        deadLetters,
        new TrucEdxmlComposer(clock.getIfAvailable(Clock::systemUTC)),
        nonBlank(destinations),
        maxBytes,
        integTransactions);
  }

  /** Một consumer Kafka cho mỗi {@link OutboundBinding}. */
  @Bean
  @ConditionalOnProperty(
      prefix = "okrkpi.integ.outbound.consumer",
      name = "enabled",
      havingValue = "true")
  RoutesBuilder integOutboundEventRoutes(
      ObjectProvider<OutboundBinding> bindings,
      OutboundPipeline pipeline,
      @Value("${mission.service.kafka.bootstrap-servers}") String brokers) {
    return new CompositeRoutes(
        bindings
            .orderedStream()
            .map(binding -> new OutboundEventRoute(binding, pipeline, brokers))
            .toList());
  }
}
