package vn.viettel.okrkpi.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import vn.viettel.okrkpi.shared.error.ProblemFactory;

/**
 * Resource server JWT của mission-core (ADR-08, TL-01 "Lan truyền ngữ cảnh tenant từ JWT tới RLS"):
 * token Keycloak realm {@code mission} kiểm chữ ký theo JWKS và {@code iss} theo cấu hình {@code
 * spring.security.oauth2.resourceserver.jwt.*}. Sau chuỗi này, {@code TenantContextFilter} của
 * {@code vt-platform-starter} đặt tenant từ claim {@code tenant_id}; {@code AuthzSubjectResolver}
 * đọc {@code sub}, {@code unit_id} và vai trò {@code realm_access.roles}. Thiếu hoặc sai token trả
 * 401, token hợp lệ mà không gắn tenant trả 403, đều là Problem Details (RFC 9457). Quyền trên từng
 * đối tượng do OPA và tầng ứng dụng quyết định; actuator chạy ở cổng quản trị riêng.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CoreSecurityConfiguration {

  static final String UNIT_CLAIM = "unit_id";

  @Bean
  SecurityFilterChain coreApiSecurity(
      HttpSecurity http,
      @Value("${okrkpi.security.auth-enabled:true}") boolean authEnabled,
      ProblemFactory problems,
      ObjectMapper json)
      throws Exception {
    ProblemSecurityHandler handler = new ProblemSecurityHandler(problems, json);
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(
            a -> {
              if (authEnabled) {
                a.anyRequest().authenticated();
              } else {
                a.anyRequest().permitAll();
              }
            })
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> j.jwtAuthenticationConverter(realmRoles()))
                    .authenticationEntryPoint(handler)
                    .accessDeniedHandler(handler))
        .exceptionHandling(e -> e.authenticationEntryPoint(handler).accessDeniedHandler(handler))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Chỉ nhận bearer token, không có phiên cookie, nên không cần mã chống CSRF.
        .csrf(c -> c.ignoringRequestMatchers("/api/**"));
    return http.build();
  }

  /**
   * {@code unit_id} có mặt thì phải là UUID (mã đơn vị trong {@code org.org_unit}); Spring Boot gắn
   * bean này vào bộ giải mã JWT tự cấu hình cùng kiểm hạn và {@code iss}.
   */
  @Bean
  OAuth2TokenValidator<Jwt> unitIdValidator() {
    return jwt -> {
      String unit = jwt.getClaimAsString(UNIT_CLAIM);
      if (unit == null || isUuid(unit)) {
        return OAuth2TokenValidatorResult.success();
      }
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "unit_id không phải UUID", null));
    };
  }

  static JwtAuthenticationConverter realmRoles() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          List<GrantedAuthority> out = new ArrayList<>();
          if (jwt.getClaim("realm_access") instanceof Map<?, ?> realm
              && realm.get("roles") instanceof Collection<?> roles) {
            roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
          }
          return out;
        });
    return converter;
  }

  private static boolean isUuid(String value) {
    if (value.length() != 36) {
      return false;
    }
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
