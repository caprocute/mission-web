package vn.viettel.okrkpi.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Máy trạng thái phiên bản Policy Pack theo baseline mục 9.1 và TL-02b QT-15 (CORE-05-02). */
class PolicyPackVersionTest {

  private static final UUID AUTHOR = UUID.randomUUID();
  private static final UUID APPROVER = UUID.randomUUID();
  private static final Instant AT = Instant.parse("2026-10-22T02:00:00Z");

  private static PolicyPackVersion draft() {
    return PolicyPackVersion.draft(
        UUID.randomUUID(), UUID.randomUUID(), "2.1.0", "{}", null, "Ghi chú", AUTHOR, AT);
  }

  private static PolicyPackVersion with(
      PolicyPackVersion v,
      PolicyVersionStatus status,
      BigDecimal passRate,
      boolean signed,
      UUID approvedBy) {
    return new PolicyPackVersion(
        v.id(),
        v.packId(),
        v.version(),
        status,
        null,
        v.manifestJson(),
        signed ? "ab" : null,
        passRate,
        null,
        signed,
        approvedBy,
        null,
        null,
        null,
        v.sourceVersionId(),
        v.changeNote(),
        v.createdAt(),
        v.createdBy(),
        v.updatedAt(),
        v.updatedBy(),
        v.rowVersion());
  }

  @Test
  void draftStartsAtNhapWithRowVersionZero() {
    PolicyPackVersion v = draft();
    assertThat(v.status()).isEqualTo(PolicyVersionStatus.NHAP);
    assertThat(v.rowVersion()).isZero();
    assertThat(v.inheritsFromParent()).isFalse();
    assertThat(v.semVer()).isEqualTo(new SemVer(2, 1, 0));
  }

  @Test
  void fullHappyPathFollowsBaseline() {
    PolicyPackVersion v = draft().moveTo(PolicyVersionStatus.DANG_KIEM_THU, AUTHOR, AT);
    v = with(v, v.status(), BigDecimal.ONE, false, null);
    v = v.moveTo(PolicyVersionStatus.DAT_KIEM_THU, AUTHOR, AT);
    v = v.moveTo(PolicyVersionStatus.CHO_PHE_DUYET, AUTHOR, AT);
    v = with(v, v.status(), BigDecimal.ONE, true, APPROVER);
    Instant published = AT.plusSeconds(60);
    v = v.moveTo(PolicyVersionStatus.DA_PHAT_HANH, APPROVER, published);
    assertThat(v.status()).isEqualTo(PolicyVersionStatus.DA_PHAT_HANH);
    assertThat(v.publishedAt()).isEqualTo(published);
    assertThat(v.rowVersion()).isEqualTo(4);
    v = v.moveTo(PolicyVersionStatus.NGUNG, APPROVER, published);
    assertThat(v.status()).isEqualTo(PolicyVersionStatus.NGUNG);
    assertThat(v.publishedAt()).isEqualTo(published);
    assertThat(v.updatedBy()).isEqualTo(APPROVER);
  }

  @Test
  void contentChangesReturnToNhap() {
    assertThat(PolicyVersionStatus.DANG_KIEM_THU.canMoveTo(PolicyVersionStatus.NHAP)).isTrue();
    assertThat(PolicyVersionStatus.DAT_KIEM_THU.canMoveTo(PolicyVersionStatus.NHAP)).isTrue();
    assertThat(PolicyVersionStatus.CHO_PHE_DUYET.canMoveTo(PolicyVersionStatus.NHAP)).isTrue();
    assertThat(PolicyVersionStatus.DA_PHAT_HANH.canMoveTo(PolicyVersionStatus.NHAP)).isFalse();
    assertThat(PolicyVersionStatus.NGUNG.next()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = PolicyVersionStatus.class,
      names = {"NHAP", "DAT_KIEM_THU", "CHO_PHE_DUYET", "DA_PHAT_HANH", "NGUNG"})
  void draftCannotSkipTesting(PolicyVersionStatus target) {
    assertThatThrownBy(() -> draft().moveTo(target, AUTHOR, AT))
        .isInstanceOf(PolicyTransitionException.class)
        .extracting(e -> ((PolicyTransitionException) e).code())
        .isEqualTo("POLICY-409-VERSION-CONFLICT");
  }

  @Test
  void passedRequiresEveryGoldenCase() {
    PolicyPackVersion testing = with(draft(), PolicyVersionStatus.DANG_KIEM_THU, null, false, null);
    assertThatThrownBy(() -> testing.moveTo(PolicyVersionStatus.DAT_KIEM_THU, AUTHOR, AT))
        .extracting(e -> ((PolicyTransitionException) e).code())
        .isEqualTo("POLICY-409-NOT-TESTED");
    PolicyPackVersion partial =
        with(draft(), PolicyVersionStatus.DANG_KIEM_THU, new BigDecimal("0.9932"), false, null);
    assertThatThrownBy(() -> partial.moveTo(PolicyVersionStatus.DAT_KIEM_THU, AUTHOR, AT))
        .extracting(e -> ((PolicyTransitionException) e).code())
        .isEqualTo("POLICY-409-NOT-TESTED");
    PolicyPackVersion all =
        with(draft(), PolicyVersionStatus.DANG_KIEM_THU, new BigDecimal("1.0000"), false, null);
    assertThat(all.moveTo(PolicyVersionStatus.DAT_KIEM_THU, AUTHOR, AT).status())
        .isEqualTo(PolicyVersionStatus.DAT_KIEM_THU);
  }

  @Test
  void publishRequiresSignatureAndSeparateApprover() {
    PolicyPackVersion unsigned =
        with(draft(), PolicyVersionStatus.CHO_PHE_DUYET, BigDecimal.ONE, false, APPROVER);
    assertThatThrownBy(() -> unsigned.moveTo(PolicyVersionStatus.DA_PHAT_HANH, APPROVER, AT))
        .extracting(e -> ((PolicyTransitionException) e).code())
        .isEqualTo("POLICY-409-NOT-SIGNED");
    PolicyPackVersion self =
        with(draft(), PolicyVersionStatus.CHO_PHE_DUYET, BigDecimal.ONE, true, AUTHOR);
    assertThatThrownBy(() -> self.moveTo(PolicyVersionStatus.DA_PHAT_HANH, AUTHOR, AT))
        .extracting(e -> ((PolicyTransitionException) e).code())
        .isEqualTo("POLICY-403-SAME-AUTHOR");
  }

  @Test
  void frozenFromPassedTesting() {
    assertThat(PolicyVersionStatus.NHAP.frozen()).isFalse();
    assertThat(PolicyVersionStatus.DANG_KIEM_THU.frozen()).isFalse();
    assertThat(PolicyVersionStatus.DAT_KIEM_THU.frozen()).isTrue();
    assertThat(PolicyVersionStatus.NGUNG.frozen()).isTrue();
  }

  @Test
  void semVerParsesComparesAndBumpsMinor() {
    assertThat(SemVer.parse("2.10.3")).contains(new SemVer(2, 10, 3));
    assertThat(SemVer.parse("2.1")).isEmpty();
    assertThat(SemVer.parse("v2.1.0")).isEmpty();
    assertThat(SemVer.parse(null)).isEmpty();
    assertThat(SemVer.parse("99999999999.0.0")).isEmpty();
    assertThat(new SemVer(2, 10, 0)).isGreaterThan(new SemVer(2, 9, 7));
    assertThat(new SemVer(2, 1, 4).nextMinor()).hasToString("2.2.0");
    assertThatThrownBy(() -> new SemVer(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PolicyPackVersion.draft(
                    UUID.randomUUID(), UUID.randomUUID(), "2.1", "{}", null, "x", AUTHOR, AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rulesAndTablesCopyToNewVersion() {
    UUID target = UUID.randomUUID();
    PolicyRule rule =
        new PolicyRule(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "A",
            "1.0",
            "DECIMAL",
            "{}",
            "PL 3",
            1,
            "{}",
            true);
    PolicyRule copy = rule.copyTo(UUID.randomUUID(), target);
    assertThat(copy.versionId()).isEqualTo(target);
    assertThat(copy).usingRecursiveComparison().ignoringFields("id", "versionId").isEqualTo(rule);
    DecisionTable table =
        new DecisionTable(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "xep_loai",
            "<dmn/>",
            "FIRST",
            new byte[] {1, 2},
            "PL 5",
            "[]",
            "{}");
    DecisionTable tableCopy = table.copyTo(UUID.randomUUID(), target);
    assertThat(tableCopy.versionId()).isEqualTo(target);
    assertThat(tableCopy.dmnHash()).containsExactly(1, 2);
    assertThat(tableCopy).isNotEqualTo(table).hasToString("DecisionTable[xep_loai@" + target + "]");
    assertThat(table).isEqualTo(table.copyTo(table.id(), table.versionId()));
    assertThat(table.hashCode()).isEqualTo(table.copyTo(table.id(), table.versionId()).hashCode());
  }

  @Test
  void manifestRulesReportEachMissingBlock() {
    assertThat(PolicyManifestRules.check(null))
        .extracting(PolicyManifestRules.Problem::field)
        .containsExactly("manifest");
    assertThat(PolicyManifestRules.check(Map.of()))
        .extracting(PolicyManifestRules.Problem::field)
        .containsExactly(
            "apiVersion",
            "kind",
            "metadata",
            "inputs",
            "definitions",
            "rounding",
            "classification",
            "quotas");
    Map<String, Object> bad =
        Map.of(
            "apiVersion", PolicyManifestRules.API_VERSION,
            "kind", PolicyManifestRules.KIND,
            "metadata", Map.of("id", "Khoi Dang", "version", "2.0", "luong", "CA_HAI", "ten", " "),
            "inputs", Map.of(),
            "definitions", Map.of("a", 1),
            "rounding", Map.of(),
            "classification", Map.of(),
            "quotas", Map.of());
    assertThat(PolicyManifestRules.check(bad))
        .extracting(PolicyManifestRules.Problem::field)
        .containsExactly(
            "metadata.id", "metadata.version", "metadata.luong", "metadata.ten", "inputs");
  }
}
