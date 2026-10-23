package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierSigningService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.FileHashes;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageEvents;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageSigner;
import vn.viettel.okrkpi.dossier.infrastructure.client.EsignPackageSigner;
import vn.viettel.okrkpi.dossier.infrastructure.client.S3FileHashes;
import vn.viettel.okrkpi.dossier.infrastructure.listener.DossierSigningConsumer;
import vn.viettel.okrkpi.dossier.infrastructure.web.PackageSignJobs;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.infrastructure.esign.EsignRequestClient;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-06-07: ký và khoá gói hồ sơ số. Yêu cầu ký đi tới SVC-10 tại {@code
 * mission.dossier.esign.base-url} (chưa đặt thì API ký trả 503); băm tệp tính lại từ bucket {@code
 * mission.dossier.storage.bucket} (chưa đặt thì dựa vào băm đã ghi và manifest đã chốt). Sổ cái và
 * EVT-21 qua {@code dossier.outbox_event}. Consumer EVT-18, EVT-22 ghi inbox {@code dossier}, tắt
 * cùng consumer gói bằng {@code mission.dossier.package.consumer.enabled=false}.
 *
 * <p>SVC-10 hỏi lại tài liệu cần ký của MOD-06 ngay trong lời gọi tạo yêu cầu ký, trong khi API ký
 * còn giữ kết nối {@code dossier} của giao dịch khoá lặp: manifest và mã yêu cầu ký ghi trong giao
 * dịch riêng ({@code REQUIRES_NEW}) đã commit trước khi gọi SVC-10, nên vùng kết nối {@code
 * dossier} cần ít nhất hai kết nối ({@code DB_DOSSIER_POOL_MAX}) (wiki trang 14, mẫu "chốt trước,
 * gọi sau").
 */
@Configuration(proxyBeanMethods = false)
public class DossierSigningConfiguration {

  @Bean
  @ConditionalOnMissingBean(PackageSigner.class)
  PackageSigner dossierPackageSigner(
      @Value("${mission.dossier.esign.base-url:${ESIGN_URL:}}") String baseUrl,
      ObjectProvider<ObjectMapper> json) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return null;
    }
    return new EsignPackageSigner(
        new EsignRequestClient(baseUrl, json.getIfAvailable(ObjectMapper::new)));
  }

  @Bean
  @ConditionalOnMissingBean(FileHashes.class)
  FileHashes dossierFileHashes(
      @Value("${mission.dossier.storage.bucket:}") String bucket,
      @Value("${mission.dossier.storage.endpoint:}") String endpoint,
      @Value("${mission.dossier.storage.region:us-east-1}") String region,
      @Value("${mission.dossier.storage.access-key:}") String accessKey,
      @Value("${mission.dossier.storage.secret-key:}") String secretKey) {
    if (bucket.isBlank()) {
      return FileHashes.NONE;
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
    return new S3FileHashes(builder.build(), bucket);
  }

  @Bean
  PackageEvents dossierPackageEvents(
      @Qualifier("dossierDataSource") DataSource dataSource,
      @Value("${mission.dossier.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    ObjectMapper mapper = json.getIfAvailable(ObjectMapper::new);
    return new OutboxPackageEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "dossier",
            new ApicurioPayloadSerializer(registry, Map.of()),
            mapper,
            Clock.systemUtc()),
        mapper);
  }

  @Bean
  DossierSigningService dossierSigningService(
      JdbcDossierPackageRepository dossierPackageRepository,
      ObjectProvider<DossierProfilesApi> profiles,
      ObjectProvider<PackageSigner> dossierPackageSigner,
      FileHashes dossierFileHashes,
      PackageEvents dossierPackageEvents,
      ObjectProvider<TenantSettingApi> settings,
      @Qualifier("dossierTransactionManager") PlatformTransactionManager tm) {
    TransactionTemplate tx = new TransactionTemplate(tm);
    TransactionTemplate committed = new TransactionTemplate(tm);
    committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new DossierSigningService(
        DossierPackageConfiguration.inTransaction(dossierPackageRepository, tx),
        profiles::getIfAvailable,
        dossierPackageSigner::getIfAvailable,
        dossierFileHashes,
        dossierPackageEvents,
        settings::getIfAvailable,
        tx,
        committed,
        java.time.Clock.systemUTC());
  }

  @Bean
  PackageSignJobs packageSignJobs(DossierSigningService dossierSigningService) {
    return new PackageSignJobs(dossierSigningService);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "mission.dossier.package.consumer",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  DossierSigningConsumer dossierSigningConsumer(
      DossierSigningService dossierSigningService,
      @Qualifier("dossierDataSource") DataSource dataSource,
      @Qualifier("dossierTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<InboxProperties> properties,
      ObjectProvider<InboxPayloadDeserializer> deserializer,
      ObjectProvider<InboxRetryDlqPublisher> retryDlq,
      ObjectProvider<InboxMetrics> metrics,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers) {
    InboxProperties props = properties.getIfAvailable();
    InboxPayloadDeserializer d = deserializer.getIfAvailable();
    InboxRetryDlqPublisher r = retryDlq.getIfAvailable();
    InboxMetrics m = metrics.getIfAvailable();
    if (props == null || d == null || r == null || m == null) {
      return null;
    }
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            new JdbcTemplate(dataSource), tm, "dossier", d, r, m, props.retry(), Clock.systemUtc());
    return new DossierSigningConsumer(
        dossierSigningService, processor, containers, props.listenerAutoStartup());
  }
}
