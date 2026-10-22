package vn.viettel.okrkpi.analytics.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.domain.EmbedToken;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.Viewer;

/**
 * DV-15-05 · {@code createAnalyticsEmbedToken} (QT-16, SCR-1601): dựng phạm vi người xem như mọi
 * truy vấn khác (BR-16-01), rồi xin Superset guest token xem đúng dashboard đã đăng ký, sống tối đa
 * 5 phút (BR-16-04). Mã dashboard không có trong bảng đăng ký thì không cấp.
 */
public class EmbedService {

  /** Hợp đồng: guest token tối đa 5 phút. */
  public static final Duration MAX_LIFETIME = Duration.ofMinutes(5);

  private final ScopeService scopes;
  private final EmbedPort superset;
  private final Map<String, UUID> dashboards;
  private final Duration lifetime;
  private final Clock clock;

  public EmbedService(
      ScopeService scopes,
      EmbedPort superset,
      Map<String, UUID> dashboards,
      Duration lifetime,
      Clock clock) {
    this.scopes = scopes;
    this.superset = superset;
    this.dashboards = Map.copyOf(dashboards);
    this.lifetime =
        lifetime == null || lifetime.compareTo(MAX_LIFETIME) > 0 ? MAX_LIFETIME : lifetime;
    this.clock = clock;
  }

  /**
   * Kỳ ({@code periodId}) không khoá trong token: dashboard nhúng tự lọc kỳ trên giao diện, phạm vi
   * dữ liệu chỉ khoá theo tenant và nhánh tổ chức (suy luận, wiki/14).
   */
  public Optional<EmbedToken> issue(Viewer viewer, String dashboardCode, UUID unitId) {
    UUID embedded = dashboards.get(dashboardCode);
    if (embedded == null) {
      return Optional.empty();
    }
    Scope scope = scopes.resolve(viewer, unitId);
    Instant now = clock.instant();
    String token = superset.guestToken(scope, embedded, lifetime);
    return Optional.of(
        new EmbedToken(token, superset.embedUrl(embedded), now.plus(lifetime), scope.orgPath()));
  }
}
