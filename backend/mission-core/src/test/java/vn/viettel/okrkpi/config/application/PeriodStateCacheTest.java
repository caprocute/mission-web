package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-03: đệm trạng thái kỳ hết hạn theo TTL, xoá theo khoá và kiểm khi trúng đệm < 0,1 ms. */
class PeriodStateCacheTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final PeriodId PERIOD = PeriodId.of(UUID.randomUUID());
  private static final PeriodStateCache.Entry OPEN =
      new PeriodStateCache.Entry(PeriodState.MO, "Quý I/2027");

  @Test
  void loadsOnceUntilTtlOrEviction() {
    AtomicLong now = new AtomicLong();
    PeriodStateCache cache = new PeriodStateCache(Duration.ofSeconds(30), now::get);
    AtomicInteger loads = new AtomicInteger();
    for (int i = 0; i < 3; i++) {
      assertThat(cache.get(TENANT, PERIOD, () -> load(loads))).contains(OPEN);
    }
    assertThat(loads).hasValue(1);

    now.addAndGet(Duration.ofSeconds(31).toNanos());
    cache.get(TENANT, PERIOD, () -> load(loads));
    assertThat(loads).hasValue(2);

    cache.evict(TENANT, PERIOD);
    cache.get(TENANT, PERIOD, () -> load(loads));
    assertThat(loads).hasValue(3);

    cache.evictAll();
    cache.get(TENANT, PERIOD, () -> load(loads));
    assertThat(loads).hasValue(4);
  }

  @Test
  void missingPeriodIsCachedAsEmptyAndOtherTenantIsSeparate() {
    PeriodStateCache cache = new PeriodStateCache(PeriodStateCache.DEFAULT_TTL);
    AtomicInteger loads = new AtomicInteger();
    assertThat(
            cache.get(
                TENANT,
                PERIOD,
                () -> {
                  loads.incrementAndGet();
                  return Optional.empty();
                }))
        .isEmpty();
    assertThat(cache.get(TENANT, PERIOD, () -> load(loads))).isEmpty();
    assertThat(cache.get(TenantId.of(UUID.randomUUID()), PERIOD, () -> load(loads))).contains(OPEN);
    assertThat(loads).hasValue(2);
  }

  @Test
  void ttlMustBePositiveAndAtMostThirtySeconds() {
    assertThatThrownBy(() -> new PeriodStateCache(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PeriodStateCache(Duration.ofSeconds(31)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cacheHitTakesLessThanOneTenthOfAMillisecond() {
    PeriodStateCache cache = new PeriodStateCache(PeriodStateCache.DEFAULT_TTL);
    AtomicInteger loads = new AtomicInteger();
    for (int i = 0; i < 50_000; i++) {
      cache.get(TENANT, PERIOD, () -> load(loads));
    }
    int rounds = 100_000;
    long start = System.nanoTime();
    for (int i = 0; i < rounds; i++) {
      cache.get(TENANT, PERIOD, () -> load(loads));
    }
    double perCallMs = (System.nanoTime() - start) / 1e6 / rounds;
    assertThat(perCallMs).isLessThan(0.1);
    assertThat(loads).hasValue(1);
  }

  private static Optional<PeriodStateCache.Entry> load(AtomicInteger loads) {
    loads.incrementAndGet();
    return Optional.of(OPEN);
  }
}
