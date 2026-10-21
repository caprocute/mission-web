package vn.viettel.okrkpi.shared.authz;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;

/**
 * Tự động cấu hình uỷ quyền tập trung OPA, kiểm step-up AAL2, chặn {@link RequiresPermission} và bộ
 * dịch đánh giá từng phần {@link OpaSqlFilter} (NEN-014, ADR-21).
 */
@AutoConfiguration
@EnableConfigurationProperties(OpaProperties.class)
@ConditionalOnProperty(
    prefix = "okrkpi.opa",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AuthzAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public OperationPolicyCatalog operationPolicyCatalog() {
    return OperationPolicyCatalog.getDefault();
  }

  @Bean
  @ConditionalOnMissingBean
  public OpaClient opaClient(
      OpaProperties properties,
      ObjectProvider<ObjectMapper> objectMapperProvider,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      ObjectProvider<Clock> clockProvider) {
    return new HttpOpaClient(
        properties,
        objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules()),
        meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new),
        clockProvider.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  public StepUpValidator stepUpValidator(
      OpaProperties properties, ObjectProvider<Clock> clockProvider) {
    return new StepUpValidator(properties, clockProvider.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  public AuthzSubjectResolver authzSubjectResolver() {
    return new AuthzSubjectResolver();
  }

  @Bean
  @ConditionalOnMissingBean
  public OpaSqlFilter opaSqlFilter(
      OpaClient opaClient, OpaProperties properties, ObjectProvider<Clock> clockProvider) {
    return new OpaSqlFilter(opaClient, properties, clockProvider.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  public PermissionInterceptor permissionInterceptor(
      OpaClient opaClient,
      OperationPolicyCatalog policyCatalog,
      StepUpValidator stepUpValidator,
      AuthzSubjectResolver subjectResolver,
      ErrorCatalog errorCatalog,
      AuditLogger auditLogger,
      ObjectProvider<Clock> clockProvider) {
    return new PermissionInterceptor(
        opaClient,
        policyCatalog,
        stepUpValidator,
        subjectResolver,
        errorCatalog,
        auditLogger,
        clockProvider.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean(name = "requiresPermissionAdvisor")
  public Advisor requiresPermissionAdvisor(PermissionInterceptor interceptor) {
    AnnotationMatchingPointcut pointcut =
        new AnnotationMatchingPointcut(null, RequiresPermission.class, true);
    DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, interceptor);
    advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    return advisor;
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnMissingBean
  public AuthzProblemHandler authzProblemHandler() {
    return new AuthzProblemHandler();
  }
}
