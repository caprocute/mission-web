package vn.viettel.okrkpi.org.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.ForbiddenOperationException;
import vn.viettel.okrkpi.org.application.StepUpRequiredException;

class ContractPolicyAdapterTest {

  private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z");
  private final ContractPolicyAdapter policy = new ContractPolicyAdapter(Clock.fixed(NOW, ZoneOffset.UTC),
      Duration.ofMinutes(5));

  private static Actor actor(Set<String> roles, String acr, Instant authTime) {
    return new Actor(UUID.randomUUID(), UUID.randomUUID(), roles, acr, authTime, null, null);
  }

  @Test
  void napVaiTroTuHopDong() {
    assertThat(policy.rules()).containsKeys("getOrgUnit", "updateOrgUnit", "changeOrgUnit", "getOrgTree");
    assertThat(policy.rules().get("changeOrgUnit").aal()).isEqualTo(2);
    assertThat(policy.rules().get("changeOrgUnit").roles()).containsExactly("tenant_admin");
  }

  @Test
  void docDuocVoiVaiTroTrongHopDong() {
    assertThatCode(() -> policy.authorize(actor(Set.of("member"), "aal1", NOW), "getOrgUnit"))
        .doesNotThrowAnyException();
  }

  @Test
  void tuChoiVaiTroKhongCoVaThaoTacLa() {
    assertThatThrownBy(() -> policy.authorize(actor(Set.of("member"), "aal2", NOW), "changeOrgUnit"))
        .isInstanceOf(ForbiddenOperationException.class);
    assertThatThrownBy(() -> policy.authorize(actor(Set.of("tenant_admin"), "aal2", NOW), "khongCo"))
        .isInstanceOf(ForbiddenOperationException.class);
  }

  @Test
  void thaoTacAal2CanXacThucGanDay() {
    Actor fresh = actor(Set.of("tenant_admin"), "aal2", NOW.minusSeconds(60));
    assertThatCode(() -> policy.authorize(fresh, "updateOrgUnit")).doesNotThrowAnyException();
    assertThatThrownBy(() -> policy.authorize(actor(Set.of("tenant_admin"), "aal1", NOW), "updateOrgUnit"))
        .isInstanceOfSatisfying(StepUpRequiredException.class, e -> assertThat(e.maxAgeSeconds()).isEqualTo(300));
    assertThatThrownBy(() -> policy.authorize(actor(Set.of("tenant_admin"), "aal2", NOW.minusSeconds(600)),
        "updateOrgUnit")).isInstanceOf(StepUpRequiredException.class);
    assertThatThrownBy(() -> policy.authorize(actor(Set.of("tenant_admin"), "aal2", null), "changeOrgUnit"))
        .isInstanceOf(StepUpRequiredException.class);
  }
}
