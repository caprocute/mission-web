package vn.viettel.okrkpi.workflow.infrastructure.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Token Keycloak (OIDC, ADR-08) kiểm bằng resource server cho {@code /api/**}. Khởi động saga chỉ
 * dành cho client nội bộ có vai trò realm {@code service_client} (hợp đồng {@code
 * startPeriodCloseSaga}), API nội bộ {@code /internal/**} (tham số luồng của DV-06-08) cũng vậy;
 * actuator chạy ở cổng quản trị riêng.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

  public static final String SERVICE_CLIENT = "service_client";

  @Bean
  SecurityFilterChain api(
      HttpSecurity http, @Value("${okrkpi.workflow.auth-enabled:true}") boolean authEnabled)
      throws Exception {
    http.securityMatcher("/api/**", "/internal/**")
        .authorizeHttpRequests(
            a -> {
              if (authEnabled) {
                a.requestMatchers(HttpMethod.POST, "/api/v1/workflow/sagas/**")
                    .hasRole(SERVICE_CLIENT)
                    .requestMatchers("/internal/**")
                    .hasRole(SERVICE_CLIENT)
                    .anyRequest()
                    .authenticated();
              } else {
                a.anyRequest().permitAll();
              }
            })
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(realmRoles())))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Chỉ nhận bearer token, không phiên cookie, nên không cần mã chống CSRF cho /api/**.
        .csrf(c -> c.ignoringRequestMatchers("/api/**", "/internal/**"));
    return http.build();
  }

  static JwtAuthenticationConverter realmRoles() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Object realm = jwt.getClaim("realm_access");
          List<GrantedAuthority> out = new ArrayList<>();
          if (realm instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
          }
          return out;
        });
    return converter;
  }
}
