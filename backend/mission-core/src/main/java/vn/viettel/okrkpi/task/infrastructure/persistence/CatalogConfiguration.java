package vn.viettel.okrkpi.task.infrastructure.persistence;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.task.application.catalog.CatalogPolicy;
import vn.viettel.okrkpi.task.application.catalog.CatalogService;

/**
 * CORE-02-03: nối danh mục công việc của MOD-02 với {@code taskDataSource}. Tham số Policy Pack
 * dùng bản mặc định khi chưa có bean {@link CatalogPolicy} khác (CORE-05-03).
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
}
