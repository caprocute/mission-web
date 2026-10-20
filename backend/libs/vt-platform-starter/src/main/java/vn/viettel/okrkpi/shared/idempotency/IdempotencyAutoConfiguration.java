package vn.viettel.okrkpi.shared.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.observability.TenantIdProvider;

/**
 * Auto-configuration quy ước {@code Idempotency-Key} (baseline mục 13, BS-02 HD-02): kho JDBC trên
 * {@code <schema>.idempotency_key}, advisor cho {@link Idempotent} và bộ xử lý lỗi. Chỉ nạp khi
 * service có {@link JdbcTemplate} và {@link PlatformTransactionManager}.
 *
 * <p>Thuộc tính {@code okrkpi.idempotency.schema}: schema mặc định khi controller không nằm trong
 * gói module ({@code vn.viettel.okrkpi.<module>..}), ví dụ microservice một schema.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
      "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration",
      "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
      "vn.viettel.okrkpi.shared.error.ProblemAutoConfiguration",
      "vn.viettel.okrkpi.shared.observability.ObservabilityAutoConfiguration"
    })
@ConditionalOnClass({JdbcTemplate.class, PlatformTransactionManager.class})
@ConditionalOnBean({JdbcTemplate.class, PlatformTransactionManager.class})
public class IdempotencyAutoConfiguration {

  /** Giới hạn giao dịch mặc định của TL-01 "Quản lý giao dịch" (5 giây). */
  static final int TX_TIMEOUT_SECONDS = 5;

  @Bean
  @ConditionalOnMissingBean
  public IdempotencyStore idempotencyStore(JdbcTemplate jdbcTemplate) {
    return new JdbcIdempotencyStore(jdbcTemplate);
  }

  @Bean
  @ConditionalOnMissingBean
  public IdempotencyCallerResolver idempotencyCallerResolver(
      ObjectProvider<TenantIdProvider> tenants) {
    return IdempotencyCallerResolver.fromPrincipal(tenants.getIfAvailable(TenantIdProvider::none));
  }

  /**
   * Advisor hạ tầng (được mọi auto-proxy creator nhận), phụ thuộc lấy lười để không khởi tạo sớm
   * bean JDBC.
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor idempotentAdvisor(
      ObjectProvider<IdempotencyStore> store,
      ObjectProvider<PlatformTransactionManager> transactionManager,
      ObjectProvider<ObjectMapper> mapper,
      ObjectProvider<IdempotencyCallerResolver> callers,
      Environment environment) {
    IdempotencyInterceptor interceptor =
        new IdempotencyInterceptor(
            store::getObject,
            () -> {
              TransactionTemplate tx = new TransactionTemplate(transactionManager.getObject());
              tx.setTimeout(TX_TIMEOUT_SECONDS);
              return tx;
            },
            () -> mapper.getIfAvailable(ObjectMapper::new),
            callers::getObject,
            environment.getProperty("okrkpi.idempotency.schema"));
    DefaultPointcutAdvisor advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, Idempotent.class, true), interceptor);
    advisor.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
    return advisor;
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnBean(ProblemFactory.class)
  public IdempotencyProblemHandler idempotencyProblemHandler(ProblemFactory problemFactory) {
    return new IdempotencyProblemHandler(problemFactory);
  }
}
