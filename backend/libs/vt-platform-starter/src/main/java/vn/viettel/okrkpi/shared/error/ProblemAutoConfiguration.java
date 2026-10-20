package vn.viettel.okrkpi.shared.error;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import vn.viettel.okrkpi.shared.observability.OtelTraceIdResolver;

/**
 * Auto-configuration lỗi RFC 9457 của vt-platform-starter (ADR-24). Chạy trước {@code
 * WebMvcAutoConfiguration} và {@code ErrorMvcAutoConfiguration} để thay bộ xử lý ProblemDetail và
 * {@code BasicErrorController} mặc định của Spring Boot. Mọi bean lùi lại khi service tự khai báo
 * bean cùng kiểu.
 */
@AutoConfiguration(before = {WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, ProblemDetail.class})
@EnableConfigurationProperties(ProblemProperties.class)
public class ProblemAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ErrorCatalog errorCatalog() {
    return ErrorCatalog.standard();
  }

  @Bean
  @ConditionalOnMissingBean
  public TraceIdResolver traceIdResolver() {
    // NEN-016: ưu tiên span OpenTelemetry hiện hành, lùi về header traceparent.
    return new OtelTraceIdResolver(new W3cTraceIdResolver());
  }

  @Bean
  @ConditionalOnMissingBean
  public ProblemFactory problemFactory(TraceIdResolver traceIdResolver, ErrorCatalog errorCatalog) {
    return new ProblemFactory(traceIdResolver, errorCatalog);
  }

  @Bean
  @ConditionalOnMissingBean
  public ErrorModuleResolver errorModuleResolver(
      ErrorCatalog errorCatalog, ProblemProperties properties) {
    return new ErrorModuleResolver(errorCatalog, properties.module());
  }

  @Bean
  @ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)
  public ProblemExceptionHandler problemExceptionHandler(
      ProblemFactory problemFactory, ErrorModuleResolver errorModuleResolver) {
    return new ProblemExceptionHandler(problemFactory, errorModuleResolver);
  }

  @Bean
  @ConditionalOnMissingBean(ErrorController.class)
  public ProblemErrorController problemErrorController(ProblemFactory problemFactory) {
    return new ProblemErrorController(problemFactory);
  }

  /** Chỉ nạp khi service có Spring Security trên classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
  static class SecurityProblemConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SecurityProblemHandler securityProblemHandler(ProblemFactory problemFactory) {
      return new SecurityProblemHandler(problemFactory);
    }
  }
}
