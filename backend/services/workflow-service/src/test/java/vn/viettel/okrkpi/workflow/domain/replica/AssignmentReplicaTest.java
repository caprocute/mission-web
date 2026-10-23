package vn.viettel.okrkpi.workflow.domain.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bất biến của lát hiệu lực phân công nhận từ EVT-15 (DV-06-10). */
class AssignmentReplicaTest {

  private static final LocalDate FROM = LocalDate.of(2026, 1, 1);

  static AssignmentReplica of(boolean primary, LocalDate to, String kind, long version) {
    return new AssignmentReplica(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        kind,
        primary,
        false,
        FROM,
        to,
        version);
  }

  @Test
  void nhanhSuyRaTuCoPhanCongChinh() {
    assertThat(of(true, null, "CHINH", 1).branch()).isEqualTo("CHINH");
    assertThat(of(false, null, "KIEM_NHIEM", 1).branch()).isEqualTo("PHU");
  }

  @Test
  void hieuLucTheoNuaKhoangValidFromValidTo() {
    AssignmentReplica open = of(true, null, "CHINH", 1);
    assertThat(open.activeOn(FROM)).isTrue();
    assertThat(open.activeOn(FROM.minusDays(1))).isFalse();
    assertThat(open.activeOn(LocalDate.of(2099, 1, 1))).isTrue();
    AssignmentReplica ended = of(true, LocalDate.of(2026, 6, 30), "CHINH", 1);
    assertThat(ended.activeOn(LocalDate.of(2026, 6, 29))).isTrue();
    assertThat(ended.activeOn(LocalDate.of(2026, 6, 30))).isFalse();
  }

  @Test
  void tuChoiDuLieuSai() {
    assertThatThrownBy(() -> of(true, FROM, "CHINH", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> of(true, FROM.minusDays(1), "CHINH", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> of(true, null, " ", 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> of(true, null, "X".repeat(17), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> of(true, null, "CHINH", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AssignmentReplica(
                    UUID.randomUUID(),
                    null,
                    UUID.randomUUID(),
                    null,
                    null,
                    "CHINH",
                    true,
                    false,
                    FROM,
                    null,
                    1))
        .isInstanceOf(NullPointerException.class);
  }
}
