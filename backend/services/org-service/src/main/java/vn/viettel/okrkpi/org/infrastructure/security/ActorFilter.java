package vn.viettel.okrkpi.org.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;

/**
 * Dựng {@link Actor} từ token đã xác thực (claim của client scope {@code mission-context}: {@code realm_access.roles},
 * {@code tenant}/{@code tenant_id}, {@code unit_id}, {@code acr}, {@code auth_time}) và đặt ngữ cảnh tenant cho giao
 * dịch. Token thiếu tenant dạng UUID bị từ chối 403.
 */
@Component
public class ActorFilter extends OncePerRequestFilter {

  public static final String ATTRIBUTE = ActorFilter.class.getName() + ".actor";

  private final boolean authEnabled;
  private final UUID devTenantId;
  private final UUID devUserId;

  public ActorFilter() {
    this(true, UUID.fromString("01960000-0000-7000-8000-000000000001"),
        UUID.fromString("01960000-0000-7000-8000-000000000101"));
  }

  @Autowired
  public ActorFilter(
      @Value("${okrkpi.org.auth-enabled:true}") boolean authEnabled,
      @Value("${okrkpi.org.dev-tenant-id:01960000-0000-7000-8000-000000000001}") UUID devTenantId,
      @Value("${okrkpi.org.dev-user-id:01960000-0000-7000-8000-000000000101}") UUID devUserId) {
    this.authEnabled = authEnabled;
    this.devTenantId = devTenantId;
    this.devUserId = devUserId;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken token)) {
      if (!authEnabled) {
        UUID headerTenant = uuid(request.getHeader("X-Tenant-Id"));
        UUID tenant = headerTenant != null ? headerTenant : devTenantId;
        Actor devActor = new Actor(tenant, devUserId, Set.of("platform_admin", "tenant_admin", "unit_admin"),
            "aal2", Instant.now(), null, request.getHeader("traceparent"));
        request.setAttribute(ATTRIBUTE, devActor);
        TenantContextHolder.set(tenant);
        try {
          chain.doFilter(request, response);
        } finally {
          TenantContextHolder.clear();
        }
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    Jwt jwt = token.getToken();
    UUID tenant = uuid(jwt.getClaimAsString("tenant_id"));
    if (tenant == null) {
      tenant = uuid(jwt.getClaimAsString("tenant"));
    }
    UUID subject = uuid(jwt.getSubject());
    if (tenant == null || subject == null) {
      response.setStatus(403);
      response.setContentType("application/problem+json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write("{\"type\":\"https://okrkpi.vn/problems/forbidden\","
          + "\"title\":\"Không có quyền thực hiện thao tác này\",\"status\":403,\"code\":\"COMMON-403-OUT-OF-SCOPE\"}");
      return;
    }
    Instant authTime = jwt.getClaim("auth_time") instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
    Actor actor = new Actor(tenant, subject, roles(jwt), jwt.getClaimAsString("acr"), authTime,
        uuid(jwt.getClaimAsString("unit_id")), request.getHeader("traceparent"));
    request.setAttribute(ATTRIBUTE, actor);
    TenantContextHolder.set(tenant);
    try {
      chain.doFilter(request, response);
    } finally {
      TenantContextHolder.clear();
    }
  }

  private static Set<String> roles(Jwt jwt) {
    Set<String> roles = new HashSet<>();
    Object realm = jwt.getClaim("realm_access");
    if (realm instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> list) {
      list.forEach(r -> roles.add(String.valueOf(r)));
    }
    return roles;
  }

  private static UUID uuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
