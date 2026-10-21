package vn.viettel.okrkpi.org.infrastructure.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import vn.viettel.okrkpi.org.infrastructure.web.InternalSyncController;

/**
 * Token Keycloak (OIDC, ADR-08) kiểm bằng resource server; mọi đường dẫn {@code /api/**} cần xác thực. Actuator chạy
 * ở cổng quản trị riêng (8081) không qua chuỗi này.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http, ActorFilter actorFilter, ProblemAuthenticationEntryPoint entryPoint,
      @Value("${okrkpi.org.auth-enabled:true}") boolean authEnabled)
      throws Exception {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(a -> {
          if (authEnabled) {
            a.anyRequest().authenticated();
          } else {
            a.anyRequest().permitAll();
          }
        })
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable())
        .addFilterAfter(actorFilter, BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  /**
   * DV-04-07: cổng nội bộ {@code /internal/**} chỉ nhận token dịch vụ có vai trò realm {@code svc_integration_gateway};
   * không dựng {@code Actor} người dùng, controller tự đặt tenant theo thân yêu cầu.
   */
  @Bean
  SecurityFilterChain internal(HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint,
      @Value("${okrkpi.org.auth-enabled:true}") boolean authEnabled) throws Exception {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      Object realm = jwt.getClaim("realm_access");
      List<GrantedAuthority> out = new ArrayList<>();
      if (realm instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
        roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
      }
      return out;
    });
    http.securityMatcher("/internal/**")
        .authorizeHttpRequests(a -> {
          if (authEnabled) {
            a.anyRequest().hasRole(InternalSyncController.SERVICE_ROLE);
          } else {
            a.anyRequest().permitAll();
          }
        })
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(entryPoint))
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable());
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
