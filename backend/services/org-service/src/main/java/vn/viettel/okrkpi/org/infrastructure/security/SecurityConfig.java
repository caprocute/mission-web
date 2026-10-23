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
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import vn.viettel.okrkpi.org.infrastructure.web.InternalIdentityController;
import vn.viettel.okrkpi.org.infrastructure.web.InternalSyncController;
import vn.viettel.okrkpi.org.infrastructure.web.ScimController;

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
   * không dựng {@code Actor} người dùng, controller tự đặt tenant theo thân yêu cầu. DV-02-04: riêng cổng tra người theo
   * số định danh chỉ nhận vai trò {@code svc_iam} của môi giới danh tính Keycloak.
   */
  @Bean
  SecurityFilterChain internal(HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint,
      @Value("${okrkpi.org.auth-enabled:true}") boolean authEnabled) throws Exception {
    JwtAuthenticationConverter converter = realmRoles();
    http.securityMatcher("/internal/**")
        .authorizeHttpRequests(a -> {
          if (authEnabled) {
            a.requestMatchers(HttpMethod.POST, "/internal/v1/org/tenants/*/persons/national-id-match")
                .hasRole(InternalIdentityController.SERVICE_ROLE);
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

  /**
   * DV-04-06: SCIM 2.0 {@code /scim/v2/**} chỉ nhận token client credentials có vai trò realm {@code service_client}
   * (AC-11); tenant và nguồn lấy từ token ở controller. Lỗi xác thực, phân quyền trả theo định dạng lỗi SCIM.
   */
  @Bean
  SecurityFilterChain scim(HttpSecurity http, @Value("${okrkpi.org.auth-enabled:true}") boolean authEnabled)
      throws Exception {
    org.springframework.security.web.AuthenticationEntryPoint unauthorized = (req, res, ex) ->
        scimError(res, 401, "Chưa xác thực.");
    http.securityMatcher("/scim/v2/**")
        .authorizeHttpRequests(a -> {
          if (authEnabled) {
            a.anyRequest().hasRole(ScimController.CLIENT_ROLE);
          } else {
            a.anyRequest().permitAll();
          }
        })
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(realmRoles()))
            .authenticationEntryPoint(unauthorized))
        .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized)
            .accessDeniedHandler((req, res, ex) -> scimError(res, 403, "Client không có quyền SCIM.")))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable());
    return http.build();
  }

  private static JwtAuthenticationConverter realmRoles() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      Object realm = jwt.getClaim("realm_access");
      List<GrantedAuthority> out = new ArrayList<>();
      if (realm instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
        roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
      }
      return out;
    });
    return converter;
  }

  private static void scimError(jakarta.servlet.http.HttpServletResponse res, int status, String detail)
      throws java.io.IOException {
    res.setStatus(status);
    res.setContentType("application/scim+json");
    res.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
    res.getWriter().write("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:Error\"],\"status\":\""
        + status + "\",\"detail\":\"" + detail + "\"}");
  }

  /** Bộ lọc chỉ chạy trong chuỗi bảo mật, không đăng ký thêm lần nữa ở servlet container. */
  @Bean
  FilterRegistrationBean<ActorFilter> actorFilterRegistration(ActorFilter filter) {
    FilterRegistrationBean<ActorFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
