package vn.viettel.okrkpi.shared.observability;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import vn.viettel.okrkpi.shared.error.ProblemAutoConfiguration;
import vn.viettel.okrkpi.shared.error.TraceIdResolver;

/**
 * Auto-configuration quan sát (ADR-12): ngữ cảnh nhật ký theo yêu cầu, thẻ metric chung {@code
 * service}/{@code cell}, metric số dòng nhật ký bị bỏ, sqlcommenter cho Hibernate. Trace và metric
 * HTTP do OpenTelemetry Java agent 2.x và Micrometer đảm nhận.
 */
@AutoConfiguration(after = ProblemAutoConfiguration.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

  /** Tên metric đếm dòng nhật ký bị bỏ khi hàng đợi AsyncLogger đầy. */
  public static final String DISCARDED_METRIC = "log4j2.events.discarded";

  @Bean
  @ConditionalOnMissingBean
  public TenantIdProvider tenantIdProvider() {
    return TenantIdProvider.none();
  }

  @Bean
  public MeterBinder logDiscardMetrics() {
    return registry ->
        FunctionCounter.builder(
                DISCARDED_METRIC,
                CountingDiscardPolicy.class,
                c -> CountingDiscardPolicy.discardedCount())
            .description("Số dòng nhật ký bị bỏ khi hàng đợi bất đồng bộ đầy")
            .register(registry);
  }

  @Bean
  public MeterRegistryCustomizer<MeterRegistry> platformCommonTags(
      Environment environment, ObservabilityProperties properties) {
    String service = environment.getProperty("spring.application.name", "unknown");
    String cell =
        properties.cell() == null || properties.cell().isBlank() ? "unknown" : properties.cell();
    return registry -> registry.config().commonTags("service", service, "cell", cell);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  static class ServletLoggingContextConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "loggingContextFilter")
    FilterRegistrationBean<LoggingContextFilter> loggingContextFilter(
        TraceIdResolver traceIdResolver, TenantIdProvider tenantIdProvider) {
      FilterRegistrationBean<LoggingContextFilter> reg =
          new FilterRegistrationBean<>(new LoggingContextFilter(traceIdResolver, tenantIdProvider));
      // Chạy sớm, sau bộ lọc của OpenTelemetry agent (đặt ở tầng servlet container).
      reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
      return reg;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "org.hibernate.resource.jdbc.spi.StatementInspector",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer"
      })
  static class SqlCommenterConfiguration {

    static final String STATEMENT_INSPECTOR = "hibernate.session_factory.statement_inspector";

    @Bean
    HibernatePropertiesCustomizer sqlCommenterHibernateCustomizer() {
      return properties ->
          properties.putIfAbsent(STATEMENT_INSPECTOR, new SqlCommenterStatementInspector());
    }
  }
}
