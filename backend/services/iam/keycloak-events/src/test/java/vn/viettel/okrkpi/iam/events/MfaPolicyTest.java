package vn.viettel.okrkpi.iam.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** DV-02-03: quyết định bắt buộc MFA theo vai trò, tenant và điểm rủi ro. */
class MfaPolicyTest {

  private final MfaPolicy.Settings s = MfaPolicy.Settings.defaults();

  private MfaPolicy.Decision eval(Set<String> roles, boolean tenant, List<String> known, List<String> presented,
      List<String> knownNets, String net, int failures) {
    return MfaPolicy.evaluate(s, new MfaPolicy.Input(roles, tenant, known, presented, knownNets, net, failures));
  }

  @Test
  void vaiTroVaTenantLuonBatBuoc() {
    assertThat(eval(Set.of("appraiser"), false, List.of(), List.of("a"), List.of(), null, 0).reasons())
        .containsExactly(MfaPolicy.VAI_TRO);
    assertThat(eval(Set.of(), true, List.of(), List.of("a"), List.of(), null, 0).reasons())
        .containsExactly(MfaPolicy.TENANT);
  }

  @Test
  void lanDauChiGhiNhanThietBiCuKhongHoi() {
    assertThat(eval(Set.of(), false, List.of(), List.of("a"), List.of(), "10.0.0.0/24", 0).required()).isFalse();
    assertThat(eval(Set.of(), false, List.of("a", "b"), List.of("x", "b"), List.of("10.0.0.0/24"), "10.0.0.0/24", 0)
        .required()).isFalse();
  }

  @Test
  void thietBiHoacViTriMoiBatBuoc() {
    MfaPolicy.Decision d = eval(Set.of(), false, List.of("a"), List.of("x"), List.of("10.0.0.0/24"), "10.0.0.0/24", 0);
    assertThat(d.required()).isTrue();
    assertThat(d.reasons()).containsExactly(MfaPolicy.THIET_BI_MOI);
    d = eval(Set.of(), false, List.of("a"), List.of("a"), List.of("10.0.0.0/24"), "10.9.0.0/24", 0);
    assertThat(d.reasons()).containsExactly(MfaPolicy.VI_TRI_MOI);
    assertThat(d.newNetwork()).isTrue();
  }

  @Test
  void lanSaiRiengLeChuaDuNguongTheoMacDinh() {
    MfaPolicy.Decision d = eval(Set.of(), false, List.of("a"), List.of("a"), List.of(), "10.0.0.0/24", 3);
    assertThat(d.score()).isEqualTo(25);
    assertThat(d.required()).isFalse();
    MfaPolicy.Settings strict = new MfaPolicy.Settings(List.of(), 50, 50, 60, 50);
    assertThat(MfaPolicy.evaluate(strict, new MfaPolicy.Input(Set.of(), false, List.of("a"), List.of("a"), List.of(),
        null, 1)).reasons()).containsExactly(MfaPolicy.RUI_RO);
  }

  @Test
  void daiMang() {
    assertThat(MfaPolicy.network("203.113.7.42")).isEqualTo("203.113.7.0/24");
    assertThat(MfaPolicy.network("2001:db8:abcd:12::1")).isEqualTo("2001:0db8:abcd::/48");
    assertThat(MfaPolicy.network("khong-phai-ip")).isNull();
    assertThat(MfaPolicy.network(null)).isNull();
  }

  @Test
  void cauHinhTuAuthenticatorConfig() {
    org.keycloak.models.AuthenticatorConfigModel m = new org.keycloak.models.AuthenticatorConfigModel();
    m.setConfig(java.util.Map.of("roles", "leader, decider", "threshold", "80", "weightNewDevice", "x"));
    MfaPolicy.Settings c = MissionMfaPolicyAuthenticatorFactory.settings(m);
    assertThat(c.roles()).containsExactly("leader", "decider");
    assertThat(c.threshold()).isEqualTo(80);
    assertThat(c.weightNewDevice()).isEqualTo(50);
    assertThat(MissionMfaPolicyAuthenticatorFactory.settings(null).roles()).isEqualTo(MfaPolicy.DEFAULT_ROLES);
  }
}
