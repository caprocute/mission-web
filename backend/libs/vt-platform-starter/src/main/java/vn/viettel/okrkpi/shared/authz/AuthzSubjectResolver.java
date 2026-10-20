package vn.viettel.okrkpi.shared.authz;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Trích thông tin chủ thể ({@link OpaInput.Subject}) từ {@link SecurityContextHolder} và {@link
 * TenantContext} (NEN-014, ADR-05, ADR-21).
 */
public class AuthzSubjectResolver {

  private static final List<String> DEFAULT_FLOWS = List.of("DANG", "NHA_NUOC");

  public OpaInput.Subject currentSubject() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String tenantId = TenantContext.current().map(t -> t.value().toString()).orElse(null);
    String unitId = null;
    String userId = null;

    if (auth instanceof JwtAuthenticationToken jwtAuth) {
      return fromJwt(jwtAuth.getToken(), jwtAuth.getAuthorities(), userId, tenantId, unitId);
    }
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      return fromJwt(jwt, auth.getAuthorities(), userId, tenantId, unitId);
    }
    if (auth != null && auth.isAuthenticated()) {
      String sub = userId != null ? userId : auth.getName();
      List<String> roles = extractAuthorities(auth.getAuthorities());
      String acr = null;
      Long authTime = null;
      List<String> flows = DEFAULT_FLOWS;
      String kind = "user";
      String clientId = null;
      if (auth.getDetails() instanceof Map<?, ?> details) {
        acr = normalizeAcr(asString(details.get("acr")));
        authTime = asEpochSeconds(details.get("auth_time"));
        List<String> detailFlows = asStringList(details.get("flows"));
        if (!detailFlows.isEmpty()) {
          flows = detailFlows;
        }
        if (tenantId == null) {
          tenantId = asString(details.get("tenant_id"));
        }
        if (unitId == null) {
          unitId = asString(details.get("unit_id"));
        }
        String detailKind = asString(details.get("kind"));
        if (detailKind != null) {
          kind = detailKind;
        }
        clientId = asString(details.get("client_id"));
      }
      return new OpaInput.Subject(
          kind, sub, clientId, tenantId, unitId, roles, acr, authTime, flows);
    }
    return new OpaInput.Subject(
        "user", userId, null, tenantId, unitId, List.of(), null, null, DEFAULT_FLOWS);
  }

  private OpaInput.Subject fromJwt(
      Jwt jwt,
      Collection<? extends GrantedAuthority> authorities,
      String fallbackUserId,
      String fallbackTenantId,
      String fallbackUnitId) {
    String sub = jwt.getSubject() != null ? jwt.getSubject() : fallbackUserId;
    String tenantId =
        fallbackTenantId != null ? fallbackTenantId : jwt.getClaimAsString("tenant_id");
    String unitId = fallbackUnitId != null ? fallbackUnitId : jwt.getClaimAsString("unit_id");
    if (unitId == null) {
      unitId = jwt.getClaimAsString("active_unit_id");
    }
    String clientId = jwt.getClaimAsString("client_id");
    if (clientId == null) {
      clientId = jwt.getClaimAsString("azp");
    }
    List<String> roles = extractRoles(jwt, authorities);
    String kind =
        (jwt.getClaimAsString("client_id") != null && roles.isEmpty()) ? "client" : "user";
    String acr = normalizeAcr(jwt.getClaimAsString("acr"));
    Long authTime = asEpochSeconds(jwt.getClaims().get("auth_time"));
    List<String> flows = asStringList(jwt.getClaims().get("flows"));
    if (flows.isEmpty()) {
      flows = DEFAULT_FLOWS;
    }
    return new OpaInput.Subject(kind, sub, clientId, tenantId, unitId, roles, acr, authTime, flows);
  }

  private static List<String> extractRoles(
      Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
    List<String> roles = new ArrayList<>();
    Object realmAccess = jwt.getClaims().get("realm_access");
    if (realmAccess instanceof Map<?, ?> map) {
      roles.addAll(asStringList(map.get("roles")));
    }
    roles.addAll(asStringList(jwt.getClaims().get("roles")));
    for (String r : extractAuthorities(authorities)) {
      if (!roles.contains(r)) {
        roles.add(r);
      }
    }
    return List.copyOf(roles);
  }

  private static List<String> extractAuthorities(
      Collection<? extends GrantedAuthority> authorities) {
    if (authorities == null || authorities.isEmpty()) {
      return List.of();
    }
    List<String> list = new ArrayList<>(authorities.size());
    for (GrantedAuthority ga : authorities) {
      String a = ga.getAuthority();
      if (a == null || a.isBlank()) {
        continue;
      }
      if (a.startsWith("ROLE_")) {
        a = a.substring(5);
      } else if (a.startsWith("SCOPE_")) {
        continue;
      }
      list.add(a);
    }
    return list;
  }

  public static String normalizeAcr(String acr) {
    if (acr == null || acr.isBlank()) {
      return null;
    }
    if ("urn:viettel:aal2".equalsIgnoreCase(acr) || "aal2".equalsIgnoreCase(acr)) {
      return "aal2";
    }
    return acr;
  }

  private static String asString(Object val) {
    return val == null ? null : val.toString();
  }

  private static Long asEpochSeconds(Object val) {
    if (val instanceof Number n) {
      return n.longValue();
    }
    if (val instanceof Instant instant) {
      return instant.getEpochSecond();
    }
    if (val instanceof String s && !s.isBlank()) {
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static List<String> asStringList(Object val) {
    if (val instanceof Collection<?> col) {
      List<String> out = new ArrayList<>(col.size());
      for (Object item : col) {
        if (item != null) {
          out.add(item.toString());
        }
      }
      return out;
    }
    return List.of();
  }
}
