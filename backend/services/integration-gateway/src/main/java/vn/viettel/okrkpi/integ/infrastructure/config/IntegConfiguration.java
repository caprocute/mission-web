package vn.viettel.okrkpi.integ.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.InboundPipeline;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSearch;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource;
import vn.viettel.okrkpi.integ.application.IssuedDocumentTransfer;
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
import vn.viettel.okrkpi.integ.application.port.EvidenceObjects;
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
import vn.viettel.okrkpi.integ.infrastructure.evidence.HttpEvidenceObjects;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcConnectors;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcDeadLetters;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcInboxMessages;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcMappingXrefs;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcOutboundMessages;
import vn.viettel.okrkpi.integ.infrastructure.persistence.JdbcSyncCursors;
import vn.viettel.okrkpi.integ.infrastructure.secrets.FileConnectorSecrets;
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
      ObjectProvider<InboundConnector> connectors, ObjectProvider<VOfficeBeans> voffice) {
    return new Translators(inboundConnectors(connectors, voffice), Map.of());
  }

  /** Bộ kết nối vào khai báo thẳng là bean cộng với các bộ kiểu vOffice dựng theo danh sách mã. */
  static List<InboundConnector> inboundConnectors(
      ObjectProvider<InboundConnector> connectors, ObjectProvider<VOfficeBeans> voffice) {
    List<InboundConnector> all = new ArrayList<>(connectors.orderedStream().toList());
    voffice.ifAvailable(v -> all.addAll(v.connectors()));
    return all;
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

  /**
   * CORE-03-04: chuyển tệp gốc văn bản đã ban hành sang SVC-08. Để trống {@code
   * okrkpi.integ.evidence.base-url} thì lệnh chuyển trả 502 để mission-core báo thử lại.
   */
  @Bean
  IssuedDocumentTransfer integIssuedDocumentTransfer(
      IssuedDocumentSearch integIssuedDocumentSearch,
      ObjectProvider<EvidenceObjects> override,
      ObjectProvider<ObjectMapper> mapper,
      @Value("${okrkpi.integ.evidence.base-url:}") String baseUrl,
      @Value("${okrkpi.integ.evidence.timeout:PT30S}") Duration timeout) {
    EvidenceObjects port = override.getIfAvailable();
    if (port == null && !baseUrl.isBlank()) {
      port =
          new HttpEvidenceObjects(
              HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(2))
                  .followRedirects(HttpClient.Redirect.NEVER)
                  .build(),
              mapper.getIfAvailable(ObjectMapper::new),
              baseUrl,
              timeout);
    }
    return new IssuedDocumentTransfer(integIssuedDocumentSearch, port);
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
    return new OutboundPipeline(
        connectors,
        messages,
        inbox,
        deadLetters,
        cipher,
        senders.orderedStream().toList(),
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
      PullService pulls,
      InboundPipeline pipeline,
      InboxIdempotentRepository idempotent) {
    return new CompositeRoutes(
        inboundConnectors(connectors, voffice).stream()
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
      @Value("${okrkpi.integ.outbound.dispatch.batch:100}") int batch) {
    return new OutboundDispatchRoute(pipeline, period, batch);
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
