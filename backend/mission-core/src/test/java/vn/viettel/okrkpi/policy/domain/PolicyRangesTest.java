package vn.viettel.okrkpi.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Khoảng hiệu lực theo kỳ và dải phiên bản của CORE-05-03. */
class PolicyRangesTest {

  @Test
  void khoangHieuLucTheoQuyThangNamVaNgay() {
    PolicyEffectiveRange q = PolicyEffectiveRange.of("2027-Q1", "2027-Q4", null).orElseThrow();
    assertThat(q.from()).isEqualTo(LocalDate.of(2027, 1, 1));
    assertThat(q.to()).isEqualTo(LocalDate.of(2027, 12, 31));
    assertThat(q.covers(LocalDate.of(2027, 10, 1))).isTrue();
    assertThat(q.covers(LocalDate.of(2026, 10, 1))).isFalse();
    assertThat(q.covers(LocalDate.of(2028, 1, 1))).isFalse();
    assertThat(PolicyEffectiveRange.of("2026-11", null, null).orElseThrow().from())
        .isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(PolicyEffectiveRange.of("2026", "2026", null).orElseThrow().to())
        .isEqualTo(LocalDate.of(2026, 12, 31));
    assertThat(PolicyEffectiveRange.of("2026-10-15", null, null).orElseThrow().from())
        .isEqualTo(LocalDate.of(2026, 10, 15));
  }

  @Test
  void tuKyTrongDungNgayHieuLucCaHaiTrongLaTuDau() {
    LocalDate from = LocalDate.of(2026, 7, 1);
    assertThat(PolicyEffectiveRange.of(null, null, from).orElseThrow().from()).isEqualTo(from);
    PolicyEffectiveRange open = PolicyEffectiveRange.of(" ", null, null).orElseThrow();
    assertThat(open.covers(LocalDate.of(1990, 1, 1))).isTrue();
    assertThat(open.start()).isEqualTo(LocalDate.MIN);
  }

  @Test
  void kySaiDangHoacNguocChieuBiLoai() {
    assertThat(PolicyEffectiveRange.of("2027-Q5", null, null)).isEmpty();
    assertThat(PolicyEffectiveRange.of("quy 1", null, null)).isEmpty();
    assertThat(PolicyEffectiveRange.of("2027-Q1", "2026-Q4", null)).isEmpty();
    assertThat(PolicyEffectiveRange.of("2027-Q1", "2027-13", null)).isEmpty();
  }

  @Test
  void khoangGiaoNhau() {
    PolicyEffectiveRange a = PolicyEffectiveRange.of("2026-Q1", "2026-Q4", null).orElseThrow();
    PolicyEffectiveRange b = PolicyEffectiveRange.of("2027-Q1", null, null).orElseThrow();
    PolicyEffectiveRange c = PolicyEffectiveRange.of("2026-Q3", null, null).orElseThrow();
    assertThat(a.overlaps(b)).isFalse();
    assertThat(b.overlaps(a)).isFalse();
    assertThat(a.overlaps(c)).isTrue();
    assertThat(c.overlaps(b)).isTrue();
  }

  @Test
  void daiPhienBanKieuNpm() {
    SemVerRange range = SemVerRange.parse("khoi-chinh-quyen/nd335@>=1.2.0 <2.0.0").orElseThrow();
    assertThat(range.code()).isEqualTo("khoi-chinh-quyen/nd335");
    assertThat(range.includes(new SemVer(1, 2, 0))).isTrue();
    assertThat(range.includes(new SemVer(1, 9, 9))).isTrue();
    assertThat(range.includes(new SemVer(2, 0, 0))).isFalse();
    assertThat(range.includes(new SemVer(1, 1, 9))).isFalse();

    SemVerRange caret = SemVerRange.parse("^2.1").orElseThrow();
    assertThat(caret.code()).isNull();
    assertThat(caret.includes(new SemVer(2, 5, 0))).isTrue();
    assertThat(caret.includes(new SemVer(3, 0, 0))).isFalse();
    assertThat(SemVerRange.parse("^0.3.1").orElseThrow().includes(new SemVer(0, 4, 0))).isFalse();
    assertThat(SemVerRange.parse("~1.2.0").orElseThrow().includes(new SemVer(1, 3, 0))).isFalse();
    assertThat(SemVerRange.parse("~1").orElseThrow().includes(new SemVer(1, 9, 0))).isTrue();
    assertThat(SemVerRange.parse("a@1.3.0").orElseThrow().includes(new SemVer(1, 3, 1))).isFalse();
    assertThat(SemVerRange.parse("1.3").orElseThrow().includes(new SemVer(1, 3, 7))).isTrue();
    assertThat(SemVerRange.parse("1").orElseThrow().includes(new SemVer(2, 0, 0))).isFalse();
    assertThat(SemVerRange.parse(">1.0.0 <=1.5.0").orElseThrow().includes(new SemVer(1, 5, 0)))
        .isTrue();
    assertThat(SemVerRange.parse("=1.0.0").orElseThrow().includes(new SemVer(1, 0, 0))).isTrue();
    assertThat(SemVerRange.parse("*").orElseThrow().includes(new SemVer(9, 9, 9))).isTrue();
    assertThat(SemVerRange.parse(null).orElseThrow().includes(new SemVer(0, 0, 1))).isTrue();
    assertThat(SemVerRange.parse("abc")).isEmpty();
  }
}
