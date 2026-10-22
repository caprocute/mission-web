package vn.viettel.okrkpi.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.viettel.okrkpi.analytics.application.Fakes.TENANT;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_HUYEN;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_KHAC;
import static vn.viettel.okrkpi.analytics.application.Fakes.UNIT_TINH;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.analytics.domain.EmbedToken;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.Viewer;
import vn.viettel.okrkpi.shared.error.BusinessException;

/** DV-15-05: guest token nhúng khoá phạm vi người xem, tối đa 5 phút, chỉ dashboard đã đăng ký. */
class EmbedServiceTest {

  static final UUID EMBEDDED = UUID.fromString("cda0f042-a44d-5667-a5ca-6ea85a36d3d1");
  final Instant now = Instant.parse("2026-10-22T03:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  final List<Object[]> grants = new ArrayList<>();
  final EmbedPort superset =
      new EmbedPort() {
        @Override
        public String guestToken(Scope scope, UUID embeddedId, Duration lifetime) {
          grants.add(new Object[] {scope, embeddedId, lifetime});
          return "khach";
        }

        @Override
        public String embedUrl(UUID embeddedId) {
          return "https://bi/embedded/" + embeddedId;
        }
      };

  EmbedService service(Duration ttl) {
    return new EmbedService(
        new ScopeService(new Fakes.Org()),
        superset,
        Map.of("BANG_DIEU_HANH_DON_VI", EMBEDDED),
        ttl,
        clock);
  }

  @Test
  void locksNarrowedBranchAndCapsLifetime() {
    Viewer leader = new Viewer(TENANT, UUID.randomUUID(), UNIT_TINH);
    EmbedToken t =
        service(Duration.ofMinutes(30))
            .issue(leader, "BANG_DIEU_HANH_DON_VI", UNIT_HUYEN)
            .orElseThrow();
    assertThat(t.lockedOrgPathPrefix()).isEqualTo("/t01/h01");
    assertThat(t.expiresAt()).isEqualTo(now.plus(EmbedService.MAX_LIFETIME));
    assertThat(t.embedUrl()).isEqualTo("https://bi/embedded/" + EMBEDDED);
    Scope scope = (Scope) grants.get(0)[0];
    assertThat(scope.tenant()).isEqualTo(TENANT);
    assertThat(scope.orgPath()).isEqualTo("/t01/h01");
    assertThat(grants.get(0)[1]).isEqualTo(EMBEDDED);
    assertThat(grants.get(0)[2]).isEqualTo(Duration.ofMinutes(5));
    assertThat(service(Duration.ofMinutes(2)).issue(leader, "BANG_DIEU_HANH_DON_VI", null))
        .get()
        .extracting(EmbedToken::expiresAt)
        .isEqualTo(now.plus(Duration.ofMinutes(2)));
  }

  @Test
  void unknownDashboardOrOutsideBranchIsRefusedBeforeCallingSuperset() {
    Viewer v = new Viewer(TENANT, UUID.randomUUID(), UNIT_HUYEN);
    assertThat(service(null).issue(v, "KHONG_CO", null)).isEmpty();
    assertThatThrownBy(() -> service(null).issue(v, "BANG_DIEU_HANH_DON_VI", UNIT_KHAC))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service(null).issue(v, "BANG_DIEU_HANH_DON_VI", UNIT_TINH))
        .isInstanceOf(BusinessException.class);
    assertThat(grants).isEmpty();
  }
}
