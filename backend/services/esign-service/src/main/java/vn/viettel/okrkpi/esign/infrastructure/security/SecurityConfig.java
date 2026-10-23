package vn.viettel.okrkpi.esign.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
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

  /**
   * DV-13-10: cổng nội bộ {@code /internal/**} (ký tổ chức) chỉ nhận token dịch vụ có vai trò realm
   * trong {@code okrkpi.esign.org-seal.caller-roles}.
   */
  @Bean
  SecurityFilterChain internal(
      HttpSecurity http,
      @Value("${okrkpi.esign.auth-enabled:true}") boolean authEnabled,
      @Value("${okrkpi.esign.org-seal.caller-roles:svc_integration_gateway}") String[] callers)
      throws Exception {
    JwtAuthenticationConverter roles = new JwtAuthenticationConverter();
    roles.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Object access = jwt.getClaims().get("realm_access");
          java.util.List<org.springframework.security.core.GrantedAuthority> out =
              new java.util.ArrayList<>();
          if (access instanceof java.util.Map<?, ?> m
              && m.get("roles") instanceof java.util.List<?> l) {
            for (Object r : l) {
              out.add(
                  new org.springframework.security.core.authority.SimpleGrantedAuthority(
                      "ROLE_" + r));
            }
          }
          return out;
        });
    http.securityMatcher("/internal/**")
        .authorizeHttpRequests(
            a -> {
              if (authEnabled) {
                a.anyRequest().hasAnyRole(callers);
              } else {
                a.anyRequest().permitAll();
              }
            })
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(roles)))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.ignoringRequestMatchers("/internal/**"));
    return http.build();
  }
}
