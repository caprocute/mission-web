package vn.viettel.okrkpi.workflow.domain.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ràng buộc nhân sự của bản sao (TL-03 {@code org_replica.person_v}). */
class PersonReplicaTest {

  static PersonReplica person(String name, String status, long v) {
    return new PersonReplica(UUID.randomUUID(), name, true, status, v);
  }

  @Test
  void hopLe() {
    PersonReplica p = person("Nguyễn Văn A", "DANG_CONG_TAC", 0);
    assertThat(p.fullName()).isEqualTo("Nguyễn Văn A");
    assertThat(person("x".repeat(200), "x".repeat(16), 1).fullName()).hasSize(200);
  }

  @Test
  void tuChoiDuLieuSai() {
    assertThatThrownBy(() -> person(null, "DANG_CONG_TAC", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> person(" ", "DANG_CONG_TAC", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> person("x".repeat(201), "DANG_CONG_TAC", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> person("A", null, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> person("A", "", 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> person("A", "x".repeat(17), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> person("A", "NGHI_HUU", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PersonReplica(null, "A", false, "NGHI_HUU", 1))
        .isInstanceOf(NullPointerException.class);
  }
}
