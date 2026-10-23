package vn.viettel.okrkpi.evidence.infrastructure.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Token Keycloak (OIDC, ADR-08) kiểm bằng resource server cho {@code /api/**}: mọi thao tác tệp cần
 * người gọi đã xác thực; quyền trên từng tệp (chủ sở hữu, phạm vi đơn vị, mức dữ liệu) do các task
 * DV-08 sau kiểm khi hiện thực handler. Actuator chạy ở cổng quản trị riêng.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

  @Bean
  SecurityFilterChain api(
      HttpSecurity http, @Value("${okrkpi.evidence.auth-enabled:true}") boolean authEnabled)
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
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(realmRoles())))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Chỉ nhận bearer token, không phiên cookie, nên không cần mã chống CSRF cho /api/**.
        .csrf(c -> c.ignoringRequestMatchers("/api/**"));
    return http.build();
  }

  /**
   * DV-08-05: cổng nội bộ {@code /internal/**} cho ai-gateway đọc tệp cần OCR. ai-gateway gọi không
   * kèm token nên xác thực dịch vụ dựa vào mTLS của lưới: AuthorizationPolicy của chart chỉ cho
   * danh tính ai-gateway; ứng dụng chỉ nhận GET và tự kiểm tệp sạch, không C3. CORE-03-04: POST
   * {@code /internal/v1/ingested-objects} cho integration-gateway chuyển tệp gốc văn bản đã ban
   * hành, AuthorizationPolicy chỉ cho danh tính integration-gateway.
   */
  @Bean
  SecurityFilterChain internal(HttpSecurity http) throws Exception {
    http.securityMatcher("/internal/**")
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        org.springframework.http.HttpMethod.GET, "/internal/v1/ocr-objects/**")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST, "/internal/v1/ingested-objects")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Gọi giữa dịch vụ, không phiên cookie.
        .csrf(c -> c.ignoringRequestMatchers("/internal/**"));
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
