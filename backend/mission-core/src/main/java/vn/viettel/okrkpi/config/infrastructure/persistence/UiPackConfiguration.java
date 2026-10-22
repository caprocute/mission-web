package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.config.api.PolicySchemaPort;
import vn.viettel.okrkpi.config.application.UiPackCompiler;
import vn.viettel.okrkpi.config.application.UiPackPublishService;
import vn.viettel.okrkpi.config.application.UiPackService;
import vn.viettel.okrkpi.config.application.UiPackStaticChecks;
import vn.viettel.okrkpi.config.application.port.CdnInvalidator;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.UiPackAssetStore;
import vn.viettel.okrkpi.config.application.port.UiPackBundleStore;
import vn.viettel.okrkpi.config.application.port.UiPackChecker;
import vn.viettel.okrkpi.config.application.port.UiPackEvents;
import vn.viettel.okrkpi.config.application.port.UiPackNotices;
import vn.viettel.okrkpi.config.application.port.UiPackSigner;
import vn.viettel.okrkpi.config.infrastructure.job.InMemoryUiPackJobs;
import vn.viettel.okrkpi.config.infrastructure.listener.UiPackPublishedListener;
import vn.viettel.okrkpi.config.infrastructure.storage.InMemoryUiPackBundleStore;
import vn.viettel.okrkpi.config.infrastructure.storage.KeyPairUiPackSigner;
import vn.viettel.okrkpi.config.infrastructure.storage.LoggingCdnInvalidator;
import vn.viettel.okrkpi.config.infrastructure.storage.S3UiPackBundleStore;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.config.v1.UiPackDaPhatHanh;

/**
 * CORE-08-08: UI Pack và vòng duyệt phiên bản của MOD-08 trên {@code configDataSource}. Bộ kiểm tra
 * tự động ({@link UiPackChecker}) do CORE-08-09 (bố cục), CORE-08-10 đăng ký, chạy theo
 * {@code @Order}.
 */
@Configuration(proxyBeanMethods = false)
public class UiPackConfiguration {

  /**
   * Hàng đợi giới hạn của bước kiểm tra (TL-01 cấm executor không giới hạn); đầy thì chạy tại chỗ.
   */
  static final int QUEUE = 500;

  @Bean
  JdbcUiPackRepository uiPackRepository(@Qualifier("configDataSource") DataSource dataSource) {
    return new JdbcUiPackRepository(JdbcClient.create(dataSource));
  }

  /** EVT-21 của vòng duyệt UI Pack qua {@code config.outbox_event}. */
  @Bean
  UiPackNotices uiPackNotices(
      @Qualifier("configDataSource") DataSource dataSource,
      @Value("${mission.config.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxUiPackNotices(outbox(dataSource, registry, json));
  }

  /** EVT-23 khi phát hành UI Pack (CORE-08-11). */
  @Bean
  UiPackEvents uiPackEvents(
      @Qualifier("configDataSource") DataSource dataSource,
      @Value("${mission.config.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxUiPackEvents(outbox(dataSource, registry, json));
  }

  private static OutboxWriter outbox(
      DataSource dataSource, String registry, ObjectProvider<ObjectMapper> json) {
    return new OutboxWriter(
        new JdbcTemplate(dataSource),
        "config",
        new ApicurioPayloadSerializer(registry, Map.of()),
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc());
  }

  /**
   * Kho gốc CDN của UI Pack: S3 (Ceph RGW) khi có {@code mission.config.ui-pack.store.bucket}, nếu
   * không thì giữ trong bộ nhớ (máy phát triển).
   */
  @Bean
  UiPackBundleStore uiPackBundleStore(
      @Value("${mission.config.ui-pack.store.bucket:}") String bucket,
      @Value("${mission.config.ui-pack.store.endpoint:}") String endpoint,
      @Value("${mission.config.ui-pack.store.region:us-east-1}") String region,
      @Value("${mission.config.ui-pack.store.access-key:}") String accessKey,
      @Value("${mission.config.ui-pack.store.secret-key:}") String secretKey) {
    if (bucket.isBlank()) {
      return new InMemoryUiPackBundleStore();
    }
    var builder =
        S3Client.builder()
            .region(Region.of(region))
            .httpClient(UrlConnectionHttpClient.create())
            .forcePathStyle(true);
    if (!endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    if (!accessKey.isBlank()) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
    }
    return new S3UiPackBundleStore(builder.build(), bucket);
  }

  /** Khoá nền tảng ký UI Pack (ES256), nạp từ bí mật. */
  @Bean
  UiPackSigner uiPackSigner(
      @Value("${mission.config.ui-pack.signing.key-id:vt-ui-pack-1}") String keyId,
      @Value("${mission.config.ui-pack.signing.private-key:}") String privateKey,
      @Value("${mission.config.ui-pack.signing.public-key:}") String publicKey) {
    return KeyPairUiPackSigner.of(keyId, privateKey, publicKey);
  }

  @Bean
  UiPackPublishService uiPackPublishService(
      UiPackService uiPackService,
      JdbcUiPackRepository uiPackRepository,
      UiPackSigner uiPackSigner,
      UiPackBundleStore uiPackBundleStore,
      UiPackEvents uiPackEvents,
      ObjectProvider<CdnInvalidator> cdn,
      ObjectProvider<PolicySchemaPort> schemas,
      InMemoryUiPackJobs uiPackJobs,
      ConfigAudit configAudit,
      CheckThreads uiPackCheckThreads,
      @Qualifier("configTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    ObjectMapper mapper = json.getIfAvailable(ObjectMapper::new);
    UiPackPublishService service =
        new UiPackPublishService(
            uiPackService,
            uiPackRepository,
            new UiPackCompiler(mapper),
            uiPackSigner,
            uiPackBundleStore,
            uiPackEvents,
            cdn.getIfAvailable(LoggingCdnInvalidator::new),
            uiPackJobs,
            configAudit,
            mapper,
            Clock.systemUtc(),
            uiPackCheckThreads.pool(),
            new TransactionTemplate(tm));
    service.setPolicySchemas(schemas.getIfAvailable());
    return service;
  }

  /**
   * Consumer EVT-23 qua inbox của schema config: ghi {@code current.json}, vô hiệu CDN. Tắt bằng
   * {@code mission.config.ui-pack.published-listener.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
  @ConditionalOnProperty(
      prefix = "mission.config.ui-pack.published-listener",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class PublishedInbox {

    @Bean
    UiPackPublishedListener uiPackPublishedListener(UiPackPublishService uiPackPublishService) {
      return new UiPackPublishedListener(uiPackPublishService);
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> uiPackPublishedContainer(
        UiPackPublishedListener listener,
        @Qualifier("configDataSource") DataSource dataSource,
        @Qualifier("configTransactionManager") PlatformTransactionManager tm,
        InboxProperties properties,
        InboxPayloadDeserializer deserializer,
        InboxRetryDlqPublisher retryDlqPublisher,
        InboxMetrics metrics,
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        @Value("${okrkpi.inbox.listener-auto-startup:true}") boolean autoStartup) {
      InboxMessageProcessor processor =
          PeriodConfiguration.configInboxProcessor(
              dataSource, tm, properties, deserializer, retryDlqPublisher, metrics);
      InboxEndpoint endpoint = publishedEndpoint(listener);
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + UiPackPublishedListener.CONSUMER);
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container =
          new ContainerProperties(EventTopics.EVT_23_UI_PACK_DA_PHAT_HANH);
      container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
      container.setMessageListener(
          (AcknowledgingMessageListener<String, byte[]>)
              (record, ack) -> processor.process(endpoint, record, ack));
      ConcurrentMessageListenerContainer<String, byte[]> c =
          new ConcurrentMessageListenerContainer<>(
              new DefaultKafkaConsumerFactory<>(props), container);
      c.setAutoStartup(autoStartup);
      return c;
    }
  }

  /** Điểm nhận EVT-23 của {@link UiPackPublishedListener}. */
  public static InboxEndpoint publishedEndpoint(UiPackPublishedListener listener) {
    try {
      return new InboxEndpoint(
          EventTopics.EVT_23_UI_PACK_DA_PHAT_HANH,
          UiPackPublishedListener.CONSUMER,
          OutboxUiPackEvents.AGGREGATE_TYPE,
          listener,
          UiPackPublishedListener.class.getMethod(
              "on", UiPackDaPhatHanh.class, InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Kiểm tra tĩnh (CORE-08-10): tương phản, chế độ cao tuổi, kích thước, tài nguyên, biểu mẫu. */
  @Bean
  @Order(1)
  UiPackStaticChecks uiPackStaticChecks(
      ObjectProvider<ObjectMapper> json,
      ObjectProvider<UiPackAssetStore> assets,
      ObjectProvider<PolicySchemaPort> schemas) {
    return new UiPackStaticChecks(
        json.getIfAvailable(ObjectMapper::new),
        assets.getIfAvailable(() -> UiPackAssetStore.NONE),
        schemas.getIfAvailable());
  }

  @Bean
  InMemoryUiPackJobs uiPackJobs() {
    return new InMemoryUiPackJobs();
  }

  /**
   * Luồng chạy kiểm tra tự động sau khi gửi duyệt. Bọc trong {@link CheckThreads} (không phải
   * {@code Executor}) để không làm cấu hình tự động {@code applicationTaskExecutor} của Spring lùi
   * bước.
   */
  @Bean(destroyMethod = "close")
  CheckThreads uiPackCheckThreads(@Value("${mission.config.ui-pack.check-threads:2}") int threads) {
    return new CheckThreads(
        new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE),
            Thread.ofVirtual().name("ui-pack-check-", 0).factory(),
            new ThreadPoolExecutor.CallerRunsPolicy()));
  }

  /** Bộ luồng riêng của bước kiểm tra UI Pack. */
  public record CheckThreads(ExecutorService pool) implements AutoCloseable {
    @Override
    public void close() {
      pool.shutdown();
    }
  }

  @Bean
  UiPackService uiPackService(
      JdbcUiPackRepository uiPackRepository,
      UiPackNotices uiPackNotices,
      ObjectProvider<UiPackChecker> checker,
      InMemoryUiPackJobs uiPackJobs,
      ConfigAudit configAudit,
      CheckThreads uiPackCheckThreads,
      @Qualifier("configDataSource") DataSource dataSource,
      @Qualifier("configTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new UiPackService(
        uiPackRepository,
        uiPackNotices,
        composite(checker.orderedStream().toList()),
        uiPackJobs,
        new JdbcRoleHolders(JdbcClient.create(dataSource)),
        configAudit,
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc(),
        uiPackCheckThreads.pool(),
        new TransactionTemplate(tm));
  }

  /** Gộp các bộ kiểm theo thứ tự {@code @Order}; kết quả nối tiếp nhau. */
  static UiPackChecker composite(List<UiPackChecker> checkers) {
    if (checkers.isEmpty()) {
      return UiPackChecker.NONE;
    }
    return (tenant, document) ->
        checkers.stream().flatMap(c -> c.check(tenant, document).stream()).toList();
  }
}
