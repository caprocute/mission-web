package vn.viettel.okrkpi.workflow.infrastructure.security;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import vn.viettel.okrkpi.shared.authz.PermissionInterceptor;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;

/**
 * Thay advisor {@code requiresPermissionAdvisor} của thư viện nền bằng bản hạ tầng, lấy {@link
 * PermissionInterceptor} lười.
 *
 * <p>Lý do (ghi ở wiki trang 14): advisor gốc là bean thường, nên khi service không có AspectJ thì
 * {@code InfrastructureAdvisorAutoProxyCreator} bỏ qua nó và {@code @RequiresPermission} không được
 * kiểm; còn khi có AspectJ thì advisor kéo {@code OpaClient} và {@code MeterRegistry} khởi tạo sớm
 * trong pha BeanPostProcessor, làm mất thẻ chung của chỉ số. Bản ở đây mang vai trò hạ tầng (mọi
 * auto-proxy creator đều áp) và không khởi tạo phụ thuộc cho tới lần gọi đầu.
 */
@Configuration(proxyBeanMethods = false)
public class PermissionAdvisorConfig {

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor requiresPermissionAdvisor(ObjectProvider<PermissionInterceptor> interceptor) {
    MethodInterceptor lazy = invocation -> interceptor.getObject().invoke(invocation);
    DefaultPointcutAdvisor advisor =
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, RequiresPermission.class, true), lazy);
    advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    return advisor;
  }
}
