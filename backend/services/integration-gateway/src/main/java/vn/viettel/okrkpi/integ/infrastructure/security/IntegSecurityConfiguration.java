package vn.viettel.okrkpi.integ.infrastructure.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import vn.viettel.okrkpi.integ.infrastructure.web.InternalIssuedDocumentController;
import vn.viettel.okrkpi.integ.infrastructure.web.InternalSyncRunController;

/**
 * Bảo vệ cổng nội bộ {@code /internal/**} bằng token dịch vụ Keycloak có vai trò realm {@code
 * svc_mission_core} (zero trust, TL-04 SEC), riêng lần đồng bộ và ánh xạ mã ngoài nhận thêm vai trò
 * {@code svc_org_service} của org-service (DV-13-06); các đường còn lại chỉ là probe và chỉ số của
 * actuator. {@code okrkpi.integ.auth-enabled=false} chỉ dùng cho kiểm thử và môi trường giả lập.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class IntegSecurityConfiguration {

  @Bean
  @Order(1)
  SecurityFilterChain integInternal(
      HttpSecurity http, @Value("${okrkpi.integ.auth-enabled:true}") boolean authEnabled)
      throws Exception {
    http.securityMatcher("/internal/**")
        .authorizeHttpRequests(
            a -> {
              if (authEnabled) {
                // org-service (DV-04-07) chỉ gọi lần đồng bộ và ghi ánh xạ mã ngoài; mọi cổng
                // còn lại chỉ cho mission-core.
                a.requestMatchers(
                        "/internal/v1/integ/sync-runs",
                        "/internal/v1/integ/sync-runs/**",
                        "/internal/v1/integ/mapping-xref")
                    .hasAnyRole(
                        InternalSyncRunController.SERVICE_ROLE,
                        InternalIssuedDocumentController.SERVICE_ROLE);
                a.anyRequest().hasRole(InternalIssuedDocumentController.SERVICE_ROLE);
              } else {
                a.anyRequest().permitAll();
              }
            })
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Gọi dịch vụ bằng token Bearer, không dùng cookie phiên.
        .csrf(c -> c.ignoringRequestMatchers("/internal/**"));
    if (authEnabled) {
      http.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(realmRoles())));
    }
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain integManagement(HttpSecurity http) throws Exception {
    http.securityMatcher("/actuator/**")
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain integDenyRest(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(a -> a.anyRequest().denyAll())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }

  private static JwtAuthenticationConverter realmRoles() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Object realm = jwt.getClaim("realm_access");
          List<GrantedAuthority> out = new ArrayList<>();
          if (realm instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
            for (Object r : roles) {
              out.add(new SimpleGrantedAuthority("ROLE_" + r));
            }
          }
          return out;
        });
    return converter;
  }
}
