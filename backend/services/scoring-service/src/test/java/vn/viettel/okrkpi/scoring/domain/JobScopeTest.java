package vn.viettel.okrkpi.scoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Phạm vi tác vụ tính: đọc, ghi, gộp và lọc hồ sơ (DV-07-04, DV-07-06). */
class JobScopeTest {

  static final UUID A = UUID.fromString("0192f1a2-0000-7000-8000-0000000000a1");
  static final UUID B = UUID.fromString("0192f1a2-0000-7000-8000-0000000000b2");
  static final UUID UNIT = UUID.fromString("0192f1a2-0000-7000-8000-0000000000c3");
  static final UUID V = UUID.fromString("0192f1a2-0000-7000-8000-0000000000d4");

  static CoreScoringInput in(UUID profile, String flow, UUID unit) {
    return new CoreScoringInput(
        profile, UUID.randomUUID(), flow, unit, null, false, false, null, List.of(), List.of(), 0);
  }

  @Test
  void ghiDocLaiNhuNhau() {
    JobScope scope =
        JobScope.of("MO_LAI_HAU_KIEM")
            .withFlows(List.of("DANG"))
            .withProfiles(List.of(B, A))
            .withUnits(List.of(UNIT))
            .withPolicyHashes(Map.of("DANG", V + ":" + "ab".repeat(32)));
    JobScope back = JobScope.parse(scope.toDoc().json());
    assertThat(back).isEqualTo(scope);
    assertThat(back.originalVersion("DANG")).isEqualTo(V);
    assertThat(back.originalHash("DANG")).isEqualTo("ab".repeat(32));
    assertThat(back.originalVersion("NHA_NUOC")).isNull();
    assertThat(back.originalHash("NHA_NUOC")).isNull();
    assertThat(scope.toDoc().json()).contains("\"ho_so\":[\"" + A + "\",\"" + B + "\"]");
  }

  @Test
  void docDangCuaDoiSoatVaKhoaLa() {
    UUID job = UUID.randomUUID();
    JobScope s =
        JobScope.parse("{\"recheckOf\":\"" + job + "\",\"results\":[\"" + A + "\"],\"khac\":1}");
    assertThat(s.recheckOf()).isEqualTo(job);
    assertThat(s.results()).containsExactly(A);
    assertThat(s.reason()).isNull();
    assertThat(JobScope.parse(s.toDoc().json())).isEqualTo(s);
    assertThatThrownBy(() -> JobScope.parse("[1]")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void locVaGopHoSo() {
    JobScope all = JobScope.of("SU_KIEN");
    assertThat(all.includes(in(A, "DANG", null))).isTrue();
    assertThat(all.mergeProfiles(List.of(A))).isEqualTo(all);

    JobScope some = all.withProfiles(List.of(A)).withFlows(List.of("DANG"));
    assertThat(some.includes(in(A, "DANG", null))).isTrue();
    assertThat(some.includes(in(A, "NHA_NUOC", null))).isFalse();
    assertThat(some.includes(in(B, "DANG", null))).isFalse();
    assertThat(some.mergeProfiles(List.of(B)).profiles()).isEqualTo(Set.of(A, B));

    JobScope unit = all.withUnits(List.of(UNIT));
    assertThat(unit.includes(in(A, "DANG", UNIT))).isTrue();
    assertThat(unit.includes(in(A, "DANG", null))).isFalse();
    assertThat(unit.includes(in(A, "DANG", UUID.randomUUID()))).isFalse();
  }
}
