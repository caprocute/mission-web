package vn.viettel.okrkpi.esign.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Token Keycloak (OIDC, ADR-08) kiểm bằng resource server cho {@code /api/**}; quyền theo đối tượng
 * (chỉ người ký thấy yêu cầu của mình) và AAL2 kiểm ở tầng ứng dụng và controller.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

  @Bean
  SecurityFilterChain api(
      HttpSecurity http, @Value("${okrkpi.esign.auth-enabled:true}") boolean authEnabled)
      throws Exception {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(
            a -> {
              if (authEnabled) {
                a.anyRequest().authenticated();
              } else {
                a.anyRequest().permitAll();
              }
            })
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Chỉ nhận bearer token, không phiên cookie, nên không cần mã chống CSRF cho /api/**.
        .csrf(c -> c.ignoringRequestMatchers("/api/**"));
    return http.build();
  }
}
