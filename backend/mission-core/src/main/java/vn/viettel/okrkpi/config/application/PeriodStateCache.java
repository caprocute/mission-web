package vn.viettel.okrkpi.config.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bộ đệm trạng thái kỳ trong bộ nhớ của pod (CORE-08-03, TL-03 "Schema config": Caffeine, hạn ≤ 30
 * giây) để {@code PeriodApi.assertWritable} không phải đọc CSDL ở mỗi lệnh ghi. Trúng đệm chỉ là
 * một lần tra bảng băm. Kỳ không tồn tại cũng được đệm để không dội CSDL.
 *
 * <p>Đệm được xoá ngay trong pod khi kỳ chuyển trạng thái, và ở pod khác khi nhận EVT-09 hoặc
 * EVT-26; hạn 30 giây là trần độ trễ khi mất sự kiện.
 */
public final class PeriodStateCache {

  /** Thời hạn mặc định theo TL-03. */
  public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

  static final long MAX_ENTRIES = 200_000;

  /** Trạng thái kỳ đã đệm: tên kỳ để dựng thông báo MSG-08-06. */
  public record Entry(PeriodState state, String name) {}

  private record Key(UUID tenant, UUID period) {}

  private final Cache<Key, Optional<Entry>> cache;

  public PeriodStateCache(Duration ttl) {
    this(ttl, Ticker.systemTicker());
  }

  PeriodStateCache(Duration ttl, Ticker ticker) {
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(DEFAULT_TTL) > 0) {
      throw new IllegalArgumentException("Hạn đệm trạng thái kỳ phải trong (0, 30 giây]");
    }
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(MAX_ENTRIES)
            .ticker(Objects.requireNonNull(ticker, "ticker"))
            .build();
  }

  /** Trạng thái kỳ từ đệm, gọi {@code loader} khi trượt. */
  public Optional<Entry> get(TenantId tenant, PeriodId period, Supplier<Optional<Entry>> loader) {
    return cache.get(new Key(tenant.value(), period.value()), k -> loader.get());
  }

  public void evict(TenantId tenant, PeriodId period) {
    cache.invalidate(new Key(tenant.value(), period.value()));
  }

  public void evictAll() {
    cache.invalidateAll();
  }

  long size() {
    cache.cleanUp();
    return cache.estimatedSize();
  }
}
