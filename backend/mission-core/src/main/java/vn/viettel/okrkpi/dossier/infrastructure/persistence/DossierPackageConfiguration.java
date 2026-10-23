package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.RenderRequests;
import vn.viettel.okrkpi.dossier.infrastructure.client.HttpRenderRequests;
import vn.viettel.okrkpi.dossier.infrastructure.listener.DossierPackageConsumer;
import vn.viettel.okrkpi.dossier.infrastructure.scheduling.DossierPackageJobs;
import vn.viettel.okrkpi.dossier.infrastructure.web.PackageRenderJobs;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;

/**
 * CORE-06-06: gói hồ sơ số trên {@code dossierDataSource}. Lệnh sinh đi tới SVC-11 tại {@code
 * mission.dossier.render.docgen-url} (mặc định {@code DOCGEN_URL}) bằng token dịch vụ; chưa cấu
 * hình thì lệnh sinh không gửi được và biểu mẫu chờ sang lỗi để người dùng sinh lại khi SVC-11 sẵn
 * sàng. Consumer EVT-12, EVT-17 ghi inbox {@code dossier} (tắt bằng {@code
 * mission.dossier.package.consumer.enabled=false}); tác vụ định kỳ tắt bằng {@code
 * mission.dossier.package.jobs.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
public class DossierPackageConfiguration {

  @Bean
  JdbcDossierPackageRepository dossierPackageRepository(
      @Qualifier("dossierDataSource") DataSource dataSource) {
    return new JdbcDossierPackageRepository(JdbcClient.create(dataSource));
  }

  @Bean
  @ConditionalOnMissingBean(RenderRequests.class)
  RenderRequests dossierRenderRequests(
      ObjectProvider<ResilientClientFactory> factory,
      ObjectProvider<KeycloakAdminClient> keycloak,
      @Value("${mission.dossier.render.docgen-url:${DOCGEN_URL:}}") String baseUrl) {
    ResilientClientFactory f = factory.getIfAvailable();
    if (baseUrl == null || baseUrl.isBlank() || f == null) {
      return new Unconfigured();
    }
    return new HttpRenderRequests(
        f.create(HttpRenderRequests.INSTANCE, baseUrl),
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
  DossierPackageService dossierPackageService(
      JdbcDossierPackageRepository dossierPackageRepository,
      ObjectProvider<DossierProfilesApi> profiles,
      RenderRequests dossierRenderRequests,
      ObjectProvider<PeriodApi> periods,
      ObjectProvider<OrgDirectoryApi> directory,
      @Qualifier("dossierTransactionManager") PlatformTransactionManager tm,
      @Value("${mission.dossier.package.failure-grace:PT2M}") Duration failureGrace) {
    return new DossierPackageService(
        inTransaction(dossierPackageRepository, new TransactionTemplate(tm)),
        profiles::getIfAvailable,
        dossierRenderRequests,
        periods::getIfAvailable,
        directory::getIfAvailable,
        new TransactionTemplate(tm),
        () -> bound(tm),
        java.time.Clock.systemUTC(),
        failureGrace);
  }

  @Bean
  PackageRenderJobs packageRenderJobs(DossierPackageService dossierPackageService) {
    return new PackageRenderJobs(dossierPackageService);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "mission.dossier.package.consumer",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  DossierPackageConsumer dossierPackageConsumer(
      DossierPackageService dossierPackageService,
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
            new JdbcTemplate(dataSource),
            tm,
            "dossier",
            d,
            r,
            m,
            props.retry(),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
    return new DossierPackageConsumer(
        dossierPackageService, processor, containers, props.listenerAutoStartup());
  }

  /** Tác vụ định kỳ của gói hồ sơ số. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.dossier.package.jobs",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Jobs {

    @Bean
    DossierPackageJobs dossierPackageJobs(
        DossierPackageService dossierPackageService,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new DossierPackageJobs(dossierPackageService, directory, cell);
    }
  }

  /** Luồng hiện tại đang giữ kết nối của giao dịch {@code dossier}. */
  static boolean bound(PlatformTransactionManager tm) {
    Object key =
        tm instanceof JpaTransactionManager jpa
            ? jpa.getEntityManagerFactory()
            : tm instanceof DataSourceTransactionManager ds ? ds.getDataSource() : null;
    return key == null
        ? TransactionSynchronizationManager.isActualTransactionActive()
        : TransactionSynchronizationManager.hasResource(key);
  }

  /**
   * Mỗi lời gọi kho chạy trong giao dịch của {@code dossierTransactionManager} (nhập giao dịch đang
   * có): RLS của {@code dossier} chỉ thấy dòng khi {@code app.tenant_id} đã đặt cục bộ trong giao
   * dịch, kể cả câu lệnh chỉ đọc của API và tác vụ định kỳ.
   */
  static DossierPackageRepository inTransaction(
      DossierPackageRepository target, TransactionOperations tx) {
    return (DossierPackageRepository)
        Proxy.newProxyInstance(
            DossierPackageRepository.class.getClassLoader(),
            new Class<?>[] {DossierPackageRepository.class},
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

  /** SVC-11 chưa cấu hình: lệnh sinh không gửi được, không có trạng thái lệnh. */
  static final class Unconfigured implements RenderRequests {

    @Override
    public UUID request(TenantId tenant, UUID packageId, String idempotencyKey) {
      throw new RenderUnavailableException("Chưa cấu hình địa chỉ document-service", null);
    }

    @Override
    public Optional<RequestState> state(TenantId tenant, UUID requestId) {
      return Optional.empty();
    }
  }
}
