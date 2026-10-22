package vn.viettel.okrkpi.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Chọn gói theo khoảng hiệu lực (X-PP-10), dải phiên bản và kỳ dạng mã (CORE-05-03). */
class PackSelectorTest {

  private static final LocalDate Q1_2027 = LocalDate.of(2027, 1, 1);

  private static PackCandidate candidate(
      String code, PackOrigin origin, String flow, String version, LocalDate from, LocalDate to) {
    return new PackCandidate(
        UUID.randomUUID(),
        code,
        origin,
        flow,
        UUID.randomUUID(),
        version,
        "ab",
        null,
        new EffectiveRange(from, to));
  }

  @Test
  void periodBoundsParseQuarterYearAndDate() {
    assertThat(EffectiveRange.periodBound("2027-Q1", false)).contains(Q1_2027);
    assertThat(EffectiveRange.periodBound("2027-Q1", true)).contains(LocalDate.of(2027, 3, 31));
    assertThat(EffectiveRange.periodBound("2026-Q4", true)).contains(LocalDate.of(2026, 12, 31));
    assertThat(EffectiveRange.periodBound(2027, false)).contains(Q1_2027);
    assertThat(EffectiveRange.periodBound("2027", true)).contains(LocalDate.of(2027, 12, 31));
    assertThat(EffectiveRange.periodBound("2027-02-15T00:00:00Z", false))
        .contains(LocalDate.of(2027, 2, 15));
    assertThat(EffectiveRange.periodBound("quý một", false)).isEmpty();
    assertThat(EffectiveRange.periodBound(null, false)).isEmpty();
  }

  @Test
  void rangeCoversAndOverlaps() {
    EffectiveRange from2027 = new EffectiveRange(Q1_2027, null);
    assertThat(from2027.covers(LocalDate.of(2026, 10, 1))).isFalse();
    assertThat(from2027.covers(LocalDate.of(2030, 1, 1))).isTrue();
    assertThat(EffectiveRange.ALWAYS.covers(LocalDate.MIN)).isTrue();
    EffectiveRange y2026 = new EffectiveRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    assertThat(y2026.overlaps(from2027)).isFalse();
    assertThat(from2027.overlaps(EffectiveRange.ALWAYS)).isTrue();
    assertThat(y2026.overlaps(new EffectiveRange(LocalDate.of(2026, 12, 31), null))).isTrue();
  }

  @Test
  void picksLatestStartThenTenantThenHighestVersion() {
    PackCandidate old = candidate("a", PackOrigin.GOC_TOAN_CUC, "DANG", "1.4.0", null, null);
    PackCandidate newer = candidate("b", PackOrigin.GOC_TOAN_CUC, "DANG", "2.0.0", Q1_2027, null);
    PackCandidate newerPatch =
        candidate("b", PackOrigin.GOC_TOAN_CUC, "DANG", "2.0.1", Q1_2027, null);
    PackCandidate tenant = candidate("t", PackOrigin.TENANT, "DANG", "1.0.0", Q1_2027, null);
    PackCandidate otherFlow = candidate("c", PackOrigin.TENANT, "NHA_NUOC", "9.0.0", null, null);
    PackCandidate unhashed =
        new PackCandidate(
            UUID.randomUUID(),
            "d",
            PackOrigin.TENANT,
            "DANG",
            UUID.randomUUID(),
            "9.0.0",
            null,
            null,
            EffectiveRange.ALWAYS);

    assertThat(
            PackSelector.select(List.of(old, newer, otherFlow), "DANG", LocalDate.of(2026, 10, 1)))
        .contains(old);
    assertThat(PackSelector.select(List.of(old, newer, newerPatch), "DANG", Q1_2027))
        .contains(newerPatch);
    assertThat(PackSelector.select(List.of(old, newer, tenant, unhashed), "DANG", Q1_2027))
        .contains(tenant);
    assertThat(PackSelector.select(List.of(otherFlow), "DANG", Q1_2027)).isEmpty();
  }

  @Test
  void reportsOverlappingRangesOfSameCodeAndFlow() {
    PackCandidate a =
        candidate("x", PackOrigin.GOC_TOAN_CUC, "DANG", "1.0.0", null, LocalDate.of(2026, 12, 31));
    PackCandidate b = candidate("x", PackOrigin.GOC_TOAN_CUC, "DANG", "2.0.0", Q1_2027, null);
    PackCandidate c =
        candidate("x", PackOrigin.GOC_TOAN_CUC, "DANG", "2.1.0", LocalDate.of(2027, 4, 1), null);
    PackCandidate d = candidate("y", PackOrigin.GOC_TOAN_CUC, "DANG", "1.0.0", null, null);
    assertThat(PackSelector.overlapping(List.of(a, b, c, d))).containsExactly(List.of(b, c));
  }

  @Test
  void semVerRangesFollowNpmShorthand() {
    SemVerRange range = SemVerRange.parse(">=1.2.0 <2.0.0").orElseThrow();
    assertThat(range.contains(new SemVer(1, 4, 0))).isTrue();
    assertThat(range.contains(new SemVer(2, 0, 0))).isFalse();
    assertThat(range.contains(new SemVer(1, 1, 9))).isFalse();
    assertThat(SemVerRange.parse("^2.1").orElseThrow().contains(new SemVer(2, 9, 0))).isTrue();
    assertThat(SemVerRange.parse("^2.1").orElseThrow().contains(new SemVer(3, 0, 0))).isFalse();
    assertThat(SemVerRange.parse("^0.3.0").orElseThrow().contains(new SemVer(0, 4, 0))).isFalse();
    assertThat(SemVerRange.parse("~1.4.0").orElseThrow().contains(new SemVer(1, 4, 7))).isTrue();
    assertThat(SemVerRange.parse("~1.4.0").orElseThrow().contains(new SemVer(1, 5, 0))).isFalse();
    assertThat(SemVerRange.parse("1.4.0").orElseThrow().contains(new SemVer(1, 4, 0))).isTrue();
    assertThat(SemVerRange.parse(">1.4.0 <=1.5.0").orElseThrow().contains(new SemVer(1, 5, 0)))
        .isTrue();
    assertThat(SemVerRange.parse("=1.4.0").orElseThrow().contains(new SemVer(1, 4, 1))).isFalse();
    assertThat(SemVerRange.parse("latest")).isEmpty();
    assertThat(SemVerRange.parse(" ")).isEmpty();
    assertThat(SemVerRange.parse(null)).isEmpty();
  }
}
