package vn.viettel.okrkpi.config.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodWriteTarget;
import vn.viettel.okrkpi.config.application.PeriodStateCache;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-06: phạm vi mở lại hậu kiểm và chi phí kiểm phạm vi khi trúng đệm. */
public class ReopenScopeTest {

  public static final ReopenScope WHOLE = ReopenScope.of("TOAN_KY", List.of());

  @Test
  void coversByKind() {
    UUID unit = UUID.randomUUID();
    UUID dossier = UUID.randomUUID();
    assertThat(WHOLE.covers(null, null)).isTrue();
    ReopenScope units = ReopenScope.of("DON_VI", List.of(unit));
    assertThat(units.covers(unit, null)).isTrue();
    assertThat(units.covers(null, unit)).isFalse();
    assertThat(units.covers(null, null)).isFalse();
    ReopenScope dossiers = ReopenScope.of("HO_SO", List.of(dossier));
    assertThat(dossiers.covers(unit, dossier)).isTrue();
    assertThat(dossiers.covers(unit, null)).isFalse();
    assertThatThrownBy(() -> ReopenScope.of("HO_SO", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReopenScope.of("KHAC", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new ReopenScope(ReopenScope.Kind.TOAN_KY, null).ids()).isEmpty();
  }

  @Test
  void reopenWindowAndMessage() {
    Instant at = Instant.parse("2027-01-04T02:00:00Z");
    TenantId t = TenantId.of(UUID.randomUUID());
    PeriodId p = PeriodId.of(UUID.randomUUID());
    assertThatThrownBy(() -> PeriodReopen.open(UUID.randomUUID(), t, p, WHOLE, at, at))
        .isInstanceOf(IllegalArgumentException.class);
    PeriodReopen r = PeriodReopen.open(UUID.randomUUID(), t, p, WHOLE, at, at.plusSeconds(60));
    assertThat(r.due(at)).isFalse();
    assertThat(r.due(at.plusSeconds(60))).isTrue();
    assertThat(PeriodLockedException.outOfReopenScope(p, "Quý IV/2026", "TASK").detail())
        .isEqualTo(
            "Kỳ Quý IV/2026 đã khoá. Dữ liệu này nằm ngoài phạm vi được mở lại nên không thể sửa.");
    assertThat(PeriodLockedException.outOfScopeDetailOf(null)).startsWith("Kỳ đánh giá đã khoá.");
  }

  /** Kiểm phạm vi khi trúng đệm phải dưới 0,1 ms mỗi lần (task CORE-08-06 mục 6). */
  @Test
  void scopeCheckOnCacheHitIsUnderOneTenthMillisecond() {
    Set<UUID> ids = Set.copyOf(IntStream.range(0, 5_000).mapToObj(i -> UUID.randomUUID()).toList());
    UUID inside = ids.iterator().next();
    ReopenScope scope = new ReopenScope(ReopenScope.Kind.HO_SO, ids);
    PeriodStateCache cache = new PeriodStateCache(Duration.ofSeconds(30));
    TenantId t = TenantId.of(UUID.randomUUID());
    PeriodId p = PeriodId.of(UUID.randomUUID());
    PeriodStateCache.Entry entry =
        new PeriodStateCache.Entry(PeriodState.MO_LAI_HAU_KIEM, "Quý IV/2026", scope);
    cache.get(t, p, () -> Optional.of(entry));
    PeriodWriteTarget in = PeriodWriteTarget.dossier(inside);
    PeriodWriteTarget out = PeriodWriteTarget.dossier(UUID.randomUUID());
    assertThat(PeriodWriteTarget.unit(inside).dossierId()).isNull();
    int n = 200_000;
    int hits = 0;
    for (int i = 0; i < n; i++) {
      hits +=
          cache
                  .get(t, p, Optional::empty)
                  .orElseThrow()
                  .reopen()
                  .covers(in.unitId(), in.dossierId())
              ? 1
              : 0;
    }
    long begin = System.nanoTime();
    for (int i = 0; i < n; i++) {
      PeriodStateCache.Entry e = cache.get(t, p, Optional::empty).orElseThrow();
      PeriodWriteTarget w = (i & 1) == 0 ? in : out;
      hits += e.reopen().covers(w.unitId(), w.dossierId()) ? 1 : 0;
    }
    double perCallMs = (System.nanoTime() - begin) / 1e6 / n;
    assertThat(hits).isEqualTo(n + n / 2);
    assertThat(perCallMs).isLessThan(0.1);
  }
}
