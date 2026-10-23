package vn.viettel.okrkpi.docgen.infrastructure.security;

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
 * Token Keycloak (OIDC, ADR-08) kiểm bằng resource server cho {@code /api/**}. Lệnh sinh biểu mẫu
 * dành cho vai trò {@code appraiser} và client nội bộ {@code service_client} (ma trận thao tác
 * {@code createDossierRenderJob}); xem trước cho {@code manager}, {@code member}, {@code appraiser}
 * ({@code previewDossierForm}); sinh bản nháp cho {@code decider} ({@code
 * createDossierDraftForms}); phạm vi đơn vị của người gọi do MOD-06 kiểm khi trả danh sách biểu
 * mẫu. Actuator chạy ở cổng quản trị riêng.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

  public static final String SERVICE_CLIENT = "service_client";
  public static final String APPRAISER = "appraiser";
  public static final String MANAGER = "manager";
  public static final String MEMBER = "member";
  public static final String DECIDER = "decider";

  @Bean
  SecurityFilterChain api(
      HttpSecurity http, @Value("${okrkpi.docgen.auth-enabled:true}") boolean authEnabled)
      throws Exception {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(
            a -> {
              if (authEnabled) {
                a.requestMatchers(HttpMethod.POST, "/api/v1/dossiers/render-jobs")
                    .hasAnyRole(APPRAISER, SERVICE_CLIENT)
                    .requestMatchers(HttpMethod.POST, "/api/v1/dossiers/forms/preview")
                    .hasAnyRole(MANAGER, MEMBER, APPRAISER)
                    .requestMatchers(HttpMethod.POST, "/api/v1/dossiers/forms")
                    .hasRole(DECIDER)
                    .anyRequest()
                    .authenticated();
              } else {
                a.anyRequest().permitAll();
              }
            })
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(realmRoles())))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Chỉ nhận bearer token, không phiên cookie, nên không cần mã chống CSRF cho /api/**.
        .csrf(c -> c.ignoringRequestMatchers("/api/**"));
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
