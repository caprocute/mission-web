package vn.viettel.okrkpi.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Kiểm nội dung và vòng đời ca kiểm thử vàng (CORE-05-06, BR-15-08). */
class GoldenCaseRulesTest {

  private static final Set<String> DECLARED = Set.of("nhiem_vu", "ty_le_O", "nv");

  private static List<String> fields(List<PolicyManifestRules.Problem> problems) {
    return problems.stream().map(PolicyManifestRules.Problem::field).toList();
  }

  @Test
  void inputUsesOnlyDeclaredVariablesAndSyntheticData() {
    assertThat(GoldenCaseRules.checkInput(Map.of("nv", Map.of("ke_hoach", 4)), DECLARED)).isEmpty();
    assertThat(
            GoldenCaseRules.checkInput(
                Map.of("loai", "QUY", "tham_so", Map.of(), "dau_vao", Map.of("ty_le_O", 1.0)),
                DECLARED))
        .isEmpty();
    assertThat(fields(GoldenCaseRules.checkInput(Map.of(), DECLARED))).containsExactly("input");
    assertThat(fields(GoldenCaseRules.checkInput(null, DECLARED))).containsExactly("input");
    assertThat(
            fields(
                GoldenCaseRules.checkInput(
                    Map.of("khac", 1, "dau_vao", Map.of("ho_ten", "x")), DECLARED)))
        .containsExactlyInAnyOrder("input.khac", "input.dau_vao.ho_ten");
    assertThat(
            fields(
                GoldenCaseRules.checkInput(
                    Map.of(
                        "nhiem_vu",
                        List.of(Map.of("cccd", "001203004567"), Map.of("lien_he", "a@b.vn")),
                        "nv",
                        Map.of("dien_thoai", "0912345678")),
                    DECLARED)))
        .containsExactlyInAnyOrder(
            "input.nhiem_vu[0].cccd", "input.nhiem_vu[1].lien_he", "input.nv.dien_thoai");
  }

  @Test
  void expectedNeedsAnOutputOfThePack() {
    assertThat(GoldenCaseRules.checkExpected(Map.of("C", 1.0), Set.of("C"))).isEmpty();
    assertThat(GoldenCaseRules.checkExpected(Map.of("xep_loai", "HTNV"), Set.of())).isEmpty();
    assertThat(fields(GoldenCaseRules.checkExpected(Map.of("la", 1), Set.of("C"))))
        .containsExactly("expected");
    assertThat(fields(GoldenCaseRules.checkExpected(null, Set.of()))).containsExactly("expected");
    assertThat(fields(GoldenCaseRules.checkExpected(Map.of("diem", 9, "ghi", "x@y.vn"), Set.of())))
        .containsExactly("expected.ghi");
  }

  @Test
  void approvedCaseIsImmutableAndKeepsAuthor() {
    UUID author = UUID.randomUUID();
    GoldenTestCase draft =
        GoldenTestCase.draft(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TC-1",
            "Mô tả",
            "{}",
            "{\"diem\":1}",
            null,
            GoldenCaseSource.BA,
            author);
    assertThat(draft.editable()).isTrue();
    assertThat(draft.authoredBy(author)).isTrue();
    assertThat(draft.authoredBy(UUID.randomUUID())).isFalse();
    GoldenTestCase approved = draft.approved(UUID.randomUUID(), Instant.EPOCH, new byte[] {1});
    assertThat(approved.status()).isEqualTo(GoldenCaseStatus.DA_DUYET);
    assertThat(approved.editable()).isFalse();
    assertThat(approved.rowVersion()).isEqualTo(1);
    assertThat(approved.createdBy()).isEqualTo(author);
    assertThat(GoldenCaseSource.valueOf("VAN_BAN")).isNotNull();
  }
}
