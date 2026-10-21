package vn.viettel.okrkpi.shared.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.tenant.TenantAutoConfiguration;

/**
 * Tự động cấu hình danh bạ định tuyến cell ({@link TenantDirectory}), bộ lọc kiểm tra cell ({@link
 * TenantCellRoutingFilter}) và {@link TenantRoutingDataSource} khi bật {@code
 * vt.platform.routing.enabled=true}.
 */
@AutoConfiguration(after = TenantAutoConfiguration.class)
@EnableConfigurationProperties(TenantRoutingProperties.class)
@ConditionalOnProperty(prefix = "vt.platform.routing", name = "enabled", havingValue = "true")
public class TenantRoutingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public TenantRegistryClient tenantRegistryClient(
      TenantRoutingProperties properties, ObjectMapper objectMapper) {
    return new HttpTenantRegistryClient(
        properties.registryBaseUrl(), properties.registryTimeout(), objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public TenantDirectory tenantDirectory(
      TenantRegistryClient registryClient, TenantRoutingProperties properties) {
    TenantDirectory directory =
        new TenantDirectory(registryClient, java.time.Clock.systemUTC(), properties.cacheTtl());
    directory.warmUp();
    return directory;
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  public FilterRegistrationBean<TenantCellRoutingFilter> tenantCellRoutingFilterRegistration(
      TenantDirectory directory,
      TenantRoutingProperties properties,
      ProblemFactory problemFactory,
      ObjectMapper objectMapper) {
    FilterRegistrationBean<TenantCellRoutingFilter> registration =
        new FilterRegistrationBean<>(
            new TenantCellRoutingFilter(
                directory, properties.cellCode(), problemFactory, objectMapper));
    registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 2);
    return registration;
  }

  @Bean
  @ConditionalOnMissingBean(TenantRoutingDataSource.class)
  @ConditionalOnProperty(prefix = "vt.platform.routing", name = "jdbc-url-template")
  public TenantRoutingDataSource tenantRoutingDataSource(
      TenantDirectory directory, TenantRoutingProperties properties) {
    return new TenantRoutingDataSource(
        directory, properties.cellCode(), null, dbName -> createDataSource(properties, dbName));
  }

  private static DataSource createDataSource(TenantRoutingProperties properties, String dbName) {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setUrl(properties.jdbcUrlTemplate().replace("{db}", dbName));
    if (properties.username() != null) {
      ds.setUsername(properties.username());
    }
    if (properties.password() != null) {
      ds.setPassword(properties.password());
    }
    return ds;
  }
}
