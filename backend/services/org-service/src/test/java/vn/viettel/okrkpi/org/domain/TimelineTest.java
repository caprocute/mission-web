package vn.viettel.okrkpi.org.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TimelineTest {

  record V(UUID versionId, UUID id, ValidPeriod validPeriod, String name) implements Versioned<V> {
    @Override
    public V withValidPeriod(ValidPeriod period) {
      return new V(null, id, period, name);
    }

    V named(String n) {
      return new V(versionId, id, validPeriod, n);
    }
  }

  private static final UUID ID = UUID.randomUUID();

  private static LocalDate d(int y, int m, int day) {
    return LocalDate.of(y, m, day);
  }

  private static V v(LocalDate from, LocalDate to, String name) {
    return new V(UUID.randomUUID(), ID, new ValidPeriod(from, to), name);
  }

  @Test
  void doiTenGiuaKhoangTachBaPhan() {
    V a = v(d(2020, 1, 1), null, "A");
    VersionPlan<V> plan = Timeline.applyChange(List.of(a), d(2024, 1, 1), d(2025, 1, 1), x -> x.named("B"));
    assertThat(plan.toClose()).containsExactly(a.versionId());
    assertThat(plan.toInsert()).extracting(V::name, x -> x.validPeriod().from(), x -> x.validPeriod().to())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("A", d(2020, 1, 1), d(2024, 1, 1)),
            org.assertj.core.groups.Tuple.tuple("B", d(2024, 1, 1), d(2025, 1, 1)),
            org.assertj.core.groups.Tuple.tuple("A", d(2025, 1, 1), null));
  }

  @Test
  void thayDoiMoKhongDongPhienBanKhongGiao() {
    V a = v(d(2020, 1, 1), d(2023, 1, 1), "A");
    V b = v(d(2023, 1, 1), null, "B");
    VersionPlan<V> plan = Timeline.applyChange(List.of(b, a), d(2024, 6, 1), null, x -> x.named("C"));
    assertThat(plan.toClose()).containsExactly(b.versionId());
    assertThat(plan.toInsert()).extracting(V::name).containsExactly("B", "C");
    assertThat(plan.toInsert().get(1).validPeriod().isOpenEnded()).isTrue();
  }

  @Test
  void thayDoiTrumNhieuPhienBan() {
    V a = v(d(2020, 1, 1), d(2023, 1, 1), "A");
    V b = v(d(2023, 1, 1), null, "B");
    VersionPlan<V> plan = Timeline.applyChange(List.of(a, b), d(2022, 1, 1), d(2024, 1, 1), x -> x.named("Z"));
    assertThat(plan.toClose()).containsExactly(a.versionId(), b.versionId());
    assertThat(plan.toInsert()).extracting(V::name).containsExactly("A", "Z", "Z", "B");
  }

  @Test
  void endAtChamDutHieuLuc() {
    V a = v(d(2020, 1, 1), null, "A");
    VersionPlan<V> plan = Timeline.endAt(List.of(a), d(2025, 7, 1));
    assertThat(plan.toInsert()).singleElement()
        .satisfies(x -> assertThat(x.validPeriod()).isEqualTo(new ValidPeriod(d(2020, 1, 1), d(2025, 7, 1))));
    assertThat(Timeline.endAt(List.of(v(d(2026, 1, 1), null, "S")), d(2025, 7, 1)).toInsert()).isEmpty();
  }

  @Test
  void removeRangeKhongGiaoThiRong() {
    V a = v(d(2020, 1, 1), d(2021, 1, 1), "A");
    assertThat(Timeline.removeRange(List.of(a), d(2022, 1, 1), null).isEmpty()).isTrue();
  }

  @Test
  void versionAtVaKhoangRong() {
    V a = v(d(2020, 1, 1), d(2021, 1, 1), "A");
    V b = v(d(2021, 1, 1), null, "B");
    assertThat(Timeline.versionAt(List.of(a, b), d(2021, 1, 1))).contains(b);
    assertThat(Timeline.versionAt(List.of(a, b), d(2019, 1, 1))).isEmpty();
    assertThatThrownBy(() -> Timeline.applyChange(List.of(a), d(2021, 1, 1), d(2021, 1, 1), x -> x))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validPeriodPhepToan() {
    ValidPeriod p = new ValidPeriod(d(2020, 1, 1), d(2021, 1, 1));
    assertThat(p.contains(d(2020, 12, 31))).isTrue();
    assertThat(p.contains(d(2021, 1, 1))).isFalse();
    assertThat(p.overlaps(d(2021, 1, 1), null)).isFalse();
    assertThat(p.overlaps(d(2019, 1, 1), d(2020, 1, 2))).isTrue();
    assertThat(p.intersect(d(2020, 6, 1), null)).contains(new ValidPeriod(d(2020, 6, 1), d(2021, 1, 1)));
    assertThat(ValidPeriod.openFrom(d(2020, 1, 1)).intersect(d(2019, 1, 1), d(2020, 2, 1)))
        .contains(new ValidPeriod(d(2020, 1, 1), d(2020, 2, 1)));
    assertThat(ValidPeriod.openFrom(d(2020, 1, 1)).intersect(d(2021, 1, 1), null))
        .contains(ValidPeriod.openFrom(d(2021, 1, 1)));
    assertThat(p.intersect(d(2022, 1, 1), null)).isEmpty();
    assertThatThrownBy(() -> new ValidPeriod(d(2021, 1, 1), d(2021, 1, 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void versionPlanBatBien() {
    VersionPlan<String> plan = VersionPlan.<String>empty().plusInsert("x");
    assertThat(plan.isEmpty()).isFalse();
    assertThat(plan.toInsert()).containsExactly("x");
    assertThat(VersionPlan.empty().isEmpty()).isTrue();
  }

  @Test
  void uuidV7DungPhienBanVaTangTheoThoiGian() {
    Clock t1 = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    Clock t2 = Clock.fixed(Instant.parse("2026-01-01T00:00:01Z"), ZoneOffset.UTC);
    UUID a = UuidV7.next(t1);
    UUID b = UuidV7.next(t2);
    assertThat(a.version()).isEqualTo(7);
    assertThat(a.variant()).isEqualTo(2);
    assertThat(a.getMostSignificantBits() >>> 16).isEqualTo(t1.millis());
    assertThat(Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits())).isNegative();
    Set<UUID> many = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      many.add(UuidV7.next(t1));
    }
    assertThat(many).hasSize(1000);
  }

  @Test
  void cayMacDinhTheoLoaiDonVi() {
    assertThat(TreeKind.defaultFor("CHI_BO")).isEqualTo(TreeKind.DANG);
    assertThat(TreeKind.defaultFor("TO_CHUC_DANG")).isEqualTo(TreeKind.DANG);
    assertThat(TreeKind.defaultFor("CO_QUAN")).isEqualTo(TreeKind.CHUYEN_MON);
  }
}
