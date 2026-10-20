package vn.viettel.okrkpi.org.infrastructure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Token Keycloak (OIDC, ADR-08) kiểm bằng resource server; mọi đường dẫn {@code /api/**} cần xác thực. Actuator chạy
 * ở cổng quản trị riêng (8081) không qua chuỗi này.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http, ActorFilter actorFilter, ProblemAuthenticationEntryPoint entryPoint)
      throws Exception {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable())
        .addFilterAfter(actorFilter, BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  /** Bộ lọc chỉ chạy trong chuỗi bảo mật, không đăng ký thêm lần nữa ở servlet container. */
  @Bean
  FilterRegistrationBean<ActorFilter> actorFilterRegistration(ActorFilter filter) {
    FilterRegistrationBean<ActorFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
