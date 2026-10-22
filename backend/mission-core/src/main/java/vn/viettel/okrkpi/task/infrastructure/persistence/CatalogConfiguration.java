package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.task.application.catalog.CatalogImportService;
import vn.viettel.okrkpi.task.application.catalog.CatalogPolicy;
import vn.viettel.okrkpi.task.application.catalog.CatalogService;

/**
 * CORE-02-03, CORE-02-04: nối danh mục công việc của MOD-02 với {@code taskDataSource}. Tham số
 * Policy Pack dùng bản mặc định khi chưa có bean {@link CatalogPolicy} khác (CORE-05-03).
 */
@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {

  @Bean
  JdbcCatalogRepository catalogRepository(@Qualifier("taskDataSource") DataSource dataSource) {
    return new JdbcCatalogRepository(JdbcClient.create(dataSource));
  }

  @Bean
  CatalogService catalogService(
      JdbcCatalogRepository catalogRepository,
      ObjectProvider<CatalogPolicy> policy,
      PeriodApi periodApi,
      OrgDirectoryApi orgDirectory) {
    return new CatalogService(
        catalogRepository, policy.getIfAvailable(CatalogPolicy::defaults), periodApi, orgDirectory);
  }

  /**
   * Hàng đợi nhập Excel (CORE-02-04): 2 luồng, tối đa 20 tệp chờ mỗi pod; đầy thì lần nhập được
   * chốt lỗi "hệ thống đang bận" thay vì chặn yêu cầu.
   */
  @Bean(destroyMethod = "shutdown")
  ThreadPoolExecutor catalogImportExecutor() {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20));
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  @Bean
  CatalogImportService catalogImportService(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Qualifier("taskTransactionManager") PlatformTransactionManager txManager,
      JdbcCatalogRepository catalogRepository,
      CatalogService catalogService,
      ObjectProvider<CatalogPolicy> policy,
      @Qualifier("catalogImportExecutor") ThreadPoolExecutor catalogImportExecutor) {
    return new CatalogImportService(
        catalogRepository,
        new JdbcCatalogImportJobs(JdbcClient.create(dataSource)),
        catalogService,
        policy.getIfAvailable(CatalogPolicy::defaults),
        new FastExcelSpreadsheets(),
        catalogImportExecutor,
        new TransactionTemplate(txManager),
        Clock.systemUTC());
  }
}
