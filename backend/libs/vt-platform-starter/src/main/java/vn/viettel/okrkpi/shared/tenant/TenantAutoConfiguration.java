package vn.viettel.okrkpi.shared.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionManager;
import vn.viettel.okrkpi.shared.error.ProblemAutoConfiguration;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.observability.ObservabilityAutoConfiguration;
import vn.viettel.okrkpi.shared.observability.TenantIdProvider;

/**
 * Ngữ cảnh tenant từ JWT tới RLS (NEN-010, ADR-07, ADR-22): bộ lọc {@link TenantContextFilter},
 * nguồn tenant đã kiểm cho nhật ký và sqlcommenter, và trình quản lý giao dịch đặt {@code
 * app.tenant_id} cục bộ giao dịch. Chạy trước cấu hình giao dịch của Spring Boot để thay {@code
 * transactionManager} mặc định; service tự khai báo {@code TransactionManager} thì phải tự gọi
 * {@link TenantSql#apply}.
 */
@AutoConfiguration(
    after = {ProblemAutoConfiguration.class, DataSourceAutoConfiguration.class},
    before = {
      ObservabilityAutoConfiguration.class,
      HibernateJpaAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class
    })
@EnableConfigurationProperties(TenantProperties.class)
public class TenantAutoConfiguration {

  /** Tenant cho nhật ký và comment SQL: chỉ từ {@link TenantContext} (đã kiểm từ token). */
  @Bean
  @ConditionalOnMissingBean
  public TenantIdProvider tenantIdProvider() {
    return request -> TenantContext.current();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(
      name = {
        "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken",
        "org.springframework.security.core.context.SecurityContextHolder"
      })
  static class ServletTenantFilterConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "tenantContextFilter")
    FilterRegistrationBean<TenantContextFilter> tenantContextFilter(
        TenantProperties properties, ProblemFactory problems, ObjectMapper objectMapper) {
      FilterRegistrationBean<TenantContextFilter> reg =
          new FilterRegistrationBean<>(new TenantContextFilter(properties, problems, objectMapper));
      reg.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
      return reg;
    }
  }

  /** 403 Problem Details khi thao tác cần tenant mà ngữ cảnh không có (thay cho 500). */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  static class TenantProblemConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TenantProblemHandler tenantProblemHandler(ProblemFactory problems) {
      return new TenantProblemHandler(problems);
    }
  }

  /** JPA (mission-core): thay transactionManager của HibernateJpaAutoConfiguration. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean",
        "jakarta.persistence.EntityManager",
        "org.hibernate.engine.spi.SessionImplementor"
      })
  @ConditionalOnSingleCandidate(DataSource.class)
  static class JpaTenantTransactionConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransactionManager.class)
    TenantJpaTransactionManager transactionManager(
        EntityManagerFactory entityManagerFactory,
        TenantProperties properties,
        ObjectProvider<TransactionManagerCustomizers> customizers) {
      TenantJpaTransactionManager tm =
          new TenantJpaTransactionManager(entityManagerFactory, properties);
      customizers.ifAvailable(c -> c.customize(tm));
      return tm;
    }
  }

  /** JDBC thuần ({@code JdbcClient}, jOOQ) khi không có JPA. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.jdbc.datasource.DataSourceTransactionManager")
  @ConditionalOnSingleCandidate(DataSource.class)
  static class JdbcTenantTransactionConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransactionManager.class)
    TenantDataSourceTransactionManager transactionManager(
        DataSource dataSource,
        TenantProperties properties,
        ObjectProvider<TransactionManagerCustomizers> customizers) {
      TenantDataSourceTransactionManager tm =
          new TenantDataSourceTransactionManager(dataSource, properties);
      customizers.ifAvailable(c -> c.customize(tm));
      return tm;
    }
  }
}
