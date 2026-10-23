package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierExportService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierTransferService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.CentralTitles;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.EdxmlGateway;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageEvents;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageExportRepository;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageFiles;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageTransferRepository;
import vn.viettel.okrkpi.dossier.infrastructure.client.HttpEdxmlGateway;
import vn.viettel.okrkpi.dossier.infrastructure.client.S3PackageFiles;
import vn.viettel.okrkpi.dossier.infrastructure.scheduling.DossierTransferJobs;
import vn.viettel.okrkpi.dossier.infrastructure.web.PackageExportJobs;
import vn.viettel.okrkpi.dossier.infrastructure.web.PackageTransferJobs;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/**
 * CORE-06-08: xuất gói ZIP và gửi gói con diện TW. Tệp đọc, ghi trên bucket {@code
 * mission.dossier.storage.bucket} (chưa đặt thì API xuất nhận tác vụ nhưng tác vụ thất bại); gói
 * con gửi integration-gateway tại {@code mission.dossier.integ.base-url} (mặc định {@code
 * INTEG_GATEWAY_URL}; chưa đặt thì API gửi trả 503) bằng token dịch vụ. Tác vụ chạy trên một vùng
 * luồng có hàng đợi giới hạn ({@code mission.dossier.export.threads}, {@code .queue}); đầy thì tác
 * vụ định kỳ giao lại. Tác vụ định kỳ tắt cùng tác vụ gói bằng {@code
 * mission.dossier.package.jobs.enabled=false}.
 *
 * <p>Tác vụ xuất và lần gửi ghi trong giao dịch riêng ({@code REQUIRES_NEW}) đã commit trước khi
 * giao cho luồng nền hay gọi SVC-13 (wiki trang 14, mẫu "chốt trước, gọi sau").
 */
@Configuration(proxyBeanMethods = false)
public class DossierTransferConfiguration {

  @Bean
  JdbcPackageExportRepository dossierPackageExportRepository(
      @Qualifier("dossierDataSource") DataSource dataSource) {
    return new JdbcPackageExportRepository(JdbcClient.create(dataSource));
  }

  @Bean
  JdbcPackageTransferRepository dossierPackageTransferRepository(
      @Qualifier("dossierDataSource") DataSource dataSource) {
    return new JdbcPackageTransferRepository(JdbcClient.create(dataSource));
  }

  @Bean
  @ConditionalOnMissingBean(PackageFiles.class)
  PackageFiles dossierPackageFiles(
      @Value("${mission.dossier.storage.bucket:}") String bucket,
      @Value("${mission.dossier.storage.endpoint:}") String endpoint,
      @Value("${mission.dossier.storage.region:us-east-1}") String region,
      @Value("${mission.dossier.storage.access-key:}") String accessKey,
      @Value("${mission.dossier.storage.secret-key:}") String secretKey) {
    if (bucket.isBlank()) {
      return PackageFiles.NONE;
    }
    AwsCredentialsProvider credentials =
        accessKey.isBlank()
            ? DefaultCredentialsProvider.builder().build()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    var client =
        S3Client.builder()
            .region(Region.of(region))
            .httpClient(UrlConnectionHttpClient.create())
            .credentialsProvider(credentials)
            .forcePathStyle(true);
    var presigner =
        S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    if (!endpoint.isBlank()) {
      client.endpointOverride(URI.create(endpoint));
      presigner.endpointOverride(URI.create(endpoint));
    }
    return new S3PackageFiles(client.build(), presigner.build(), bucket);
  }

  @Bean
  @ConditionalOnMissingBean(EdxmlGateway.class)
  EdxmlGateway dossierEdxmlGateway(
      ObjectProvider<ResilientClientFactory> factory,
      ObjectProvider<KeycloakAdminClient> keycloak,
      ObjectProvider<ObjectMapper> json,
      @Value("${mission.dossier.integ.base-url:${INTEG_GATEWAY_URL:}}") String baseUrl) {
    ResilientClientFactory f = factory.getIfAvailable();
    if (baseUrl == null || baseUrl.isBlank() || f == null) {
      return null;
    }
    return new HttpEdxmlGateway(
        f.create(HttpEdxmlGateway.INSTANCE, baseUrl),
        () -> {
          KeycloakAdminClient k = keycloak.getIfAvailable();
          if (k == null) {
            throw new DependencyUnavailableException(
                "keycloak", DependencyUnavailableException.Reason.IO_ERROR, null, null);
          }
          return k.serviceToken();
        },
        json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  @ConditionalOnMissingBean(CentralTitles.class)
  CentralTitles dossierCentralTitles(
      ObjectProvider<OrgDirectoryApi> directory, ObjectProvider<TenantSettingApi> settings) {
    return new ReplicaCentralTitles(directory::getIfAvailable, settings::getIfAvailable);
  }

  /** Vùng luồng xuất và gửi gói con: số luồng cố định, hàng đợi giới hạn, đầy thì từ chối. */
  @Bean(destroyMethod = "shutdown")
  ThreadPoolExecutor dossierPackageRunner(
      @Value("${mission.dossier.export.threads:2}") int threads,
      @Value("${mission.dossier.export.queue:100}") int queue) {
    AtomicInteger seq = new AtomicInteger();
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            threads,
            threads,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queue),
            r -> {
              Thread t = new Thread(r, "dossier-package-" + seq.incrementAndGet());
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }

  @Bean
  DossierExportService dossierExportService(
      JdbcDossierPackageRepository dossierPackageRepository,
      JdbcPackageExportRepository dossierPackageExportRepository,
      PackageFiles dossierPackageFiles,
      PackageEvents dossierPackageEvents,
      @Qualifier("dossierPackageRunner") ThreadPoolExecutor runner,
      @Qualifier("dossierTransactionManager") PlatformTransactionManager tm,
      @Value("${mission.dossier.export.url-ttl:PT15M}") Duration urlTtl,
      @Value("${mission.dossier.export.stale-after:PT30M}") Duration stale) {
    TransactionTemplate tx = new TransactionTemplate(tm);
    return new DossierExportService(
        DossierPackageConfiguration.inTransaction(dossierPackageRepository, tx),
        inTransaction(PackageExportRepository.class, dossierPackageExportRepository, tx),
        dossierPackageFiles,
        dossierPackageEvents,
        committed(tm),
        runner,
        java.time.Clock.systemUTC(),
        urlTtl,
        stale);
  }

  @Bean
  DossierTransferService dossierTransferService(
      JdbcDossierPackageRepository dossierPackageRepository,
      JdbcPackageTransferRepository dossierPackageTransferRepository,
      ObjectProvider<DossierProfilesApi> profiles,
      CentralTitles dossierCentralTitles,
      ObjectProvider<EdxmlGateway> dossierEdxmlGateway,
      PackageFiles dossierPackageFiles,
      PackageEvents dossierPackageEvents,
      @Qualifier("dossierPackageRunner") ThreadPoolExecutor runner,
      @Qualifier("dossierTransactionManager") PlatformTransactionManager tm,
      @Value("${mission.dossier.zone:Asia/Ho_Chi_Minh}") String zone) {
    TransactionTemplate tx = new TransactionTemplate(tm);
    return new DossierTransferService(
        DossierPackageConfiguration.inTransaction(dossierPackageRepository, tx),
        inTransaction(PackageTransferRepository.class, dossierPackageTransferRepository, tx),
        profiles::getIfAvailable,
        dossierCentralTitles,
        dossierEdxmlGateway::getIfAvailable,
        dossierPackageFiles,
        dossierPackageEvents,
        tx,
        committed(tm),
        runner,
        java.time.Clock.systemUTC(),
        ZoneId.of(zone));
  }

  @Bean
  PackageExportJobs packageExportJobs(DossierExportService dossierExportService) {
    return new PackageExportJobs(dossierExportService);
  }

  @Bean
  PackageTransferJobs packageTransferJobs(DossierTransferService dossierTransferService) {
    return new PackageTransferJobs(dossierTransferService);
  }

  /** Tác vụ định kỳ: hỏi kết quả gửi gói con, giao lại tác vụ xuất bị bỏ dở. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.dossier.package.jobs",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Jobs {

    @Bean
    DossierTransferJobs dossierTransferJobs(
        DossierExportService dossierExportService,
        DossierTransferService dossierTransferService,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new DossierTransferJobs(dossierExportService, dossierTransferService, directory, cell);
    }
  }

  private static TransactionOperations committed(PlatformTransactionManager tm) {
    TransactionTemplate committed = new TransactionTemplate(tm);
    committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return committed;
  }

  /** Mỗi lời gọi kho chạy trong giao dịch của {@code tx} (tham gia giao dịch đang mở nếu có). */
  @SuppressWarnings("unchecked")
  static <T> T inTransaction(Class<T> type, T target, TransactionOperations tx) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class) {
                return method.invoke(target, args);
              }
              return tx.execute(
                  s -> {
                    try {
                      return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                      if (e.getCause() instanceof RuntimeException r) {
                        throw r;
                      }
                      throw new IllegalStateException(e.getCause());
                    } catch (IllegalAccessException e) {
                      throw new IllegalStateException(e);
                    }
                  });
            });
  }
}
