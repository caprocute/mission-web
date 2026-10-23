package vn.viettel.okrkpi.workflow.domain.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ràng buộc lát hiệu lực đơn vị của bản sao (TL-03 {@code org_replica.org_unit_v}). */
class OrgUnitReplicaTest {

  private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
  private static final UUID PARTY = UUID.randomUUID();
  private static final UUID ADMIN = UUID.randomUUID();

  static OrgUnitReplica unit(String code, String name, String type, LocalDate to, long v) {
    return new OrgUnitReplica(UUID.randomUUID(), FROM, to, code, name, type, PARTY, ADMIN, null, v);
  }

  @Test
  void capTrenTheoCayVaPhienBanMo() {
    OrgUnitReplica open = unit("DV", "Đơn vị", "CHI_BO", null, 0);
    assertThat(open.parentOn(OrgTree.DANG)).isEqualTo(PARTY);
    assertThat(open.parentOn(OrgTree.CHUYEN_MON)).isEqualTo(ADMIN);
    assertThat(open.openEnded()).isTrue();
    assertThat(unit("DV", "Đơn vị", "CHI_BO", FROM.plusDays(1), 1).openEnded()).isFalse();
  }

  @Test
  void tuChoiDuLieuSai() {
    assertThatThrownBy(() -> unit(null, "Đơn vị", "CHI_BO", null, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> unit(" ", "Đơn vị", "CHI_BO", null, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> unit("x".repeat(65), "Đơn vị", "CHI_BO", null, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> unit("DV", "x".repeat(256), "CHI_BO", null, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> unit("DV", "Đơn vị", "x".repeat(25), null, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> unit("DV", "Đơn vị", "CHI_BO", FROM, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> unit("DV", "Đơn vị", "CHI_BO", null, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new OrgUnitReplica(null, FROM, null, "DV", "Đơn vị", "CHI_BO", null, null, null, 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new OrgUnitReplica(
                    UUID.randomUUID(), null, null, "DV", "Đơn vị", "CHI_BO", null, null, null, 1))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void loiVongNeuCay() {
    UUID a = UUID.randomUUID();
    OrgTreeCycleException ex = new OrgTreeCycleException(OrgTree.CHUYEN_MON, a, a);
    assertThat(ex.tree()).isEqualTo(OrgTree.CHUYEN_MON);
    assertThat(ex.getMessage()).contains("CHUYEN_MON").contains(a.toString());
  }
}
