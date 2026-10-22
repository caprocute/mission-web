package vn.viettel.okrkpi.shared.cache;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Hiện thực {@link TenantCache} trên Valkey 8 qua Lettuce, cho cả cụm (cell bridge, pool) và cặp
 * chính–bản sao (cell silo nhỏ). Ngoài khoá mang tenant, mỗi thao tác còn đối chiếu tenant của khoá
 * với {@link TenantContext} hiện hành (nếu có) để chặn một yêu cầu của tenant A dùng khoá dựng cho
 * tenant B.
 */
public final class ValkeyTenantCache implements TenantCache {

  static final int SCAN_BATCH = 500;

  private final RedisStringCommands<String, String> strings;
  private final RedisKeyCommands<String, String> keys;

  private ValkeyTenantCache(
      RedisStringCommands<String, String> strings, RedisKeyCommands<String, String> keys) {
    this.strings = strings;
    this.keys = keys;
  }

  public static ValkeyTenantCache standalone(StatefulRedisConnection<String, String> connection) {
    return new ValkeyTenantCache(connection.sync(), connection.sync());
  }

  /** Ở chế độ cụm, Lettuce chạy {@code SCAN} lần lượt trên từng shard chính. */
  public static ValkeyTenantCache cluster(
      StatefulRedisClusterConnection<String, String> connection) {
    return new ValkeyTenantCache(connection.sync(), connection.sync());
  }

  @Override
  public Optional<String> get(TenantCacheKey key) {
    return Optional.ofNullable(strings.get(checked(key)));
  }

  @Override
  public void put(TenantCacheKey key, String value, Duration ttl) {
    Objects.requireNonNull(value, "value");
    strings.set(checked(key), value, SetArgs.Builder.px(jitter(ttl)));
  }

  @Override
  public void evict(TenantCacheKey key) {
    keys.del(checked(key));
  }

  @Override
  public long evictTenant(TenantId tenant) {
    ScanArgs args = ScanArgs.Builder.matches(TenantCacheKey.scanPattern(tenant)).limit(SCAN_BATCH);
    long deleted = 0;
    ScanCursor cursor = ScanCursor.INITIAL;
    do {
      KeyScanCursor<String> page = keys.scan(cursor, args);
      List<String> batch = page.getKeys();
      if (!batch.isEmpty()) {
        deleted += keys.unlink(batch.toArray(String[]::new));
      }
      cursor = page;
    } while (!cursor.isFinished());
    return deleted;
  }

  static long jitter(Duration ttl) {
    long millis = Objects.requireNonNull(ttl, "ttl").toMillis();
    if (millis <= 0) {
      throw new IllegalArgumentException("TTL của khoá đệm phải dương");
    }
    long spread = millis / 10;
    return spread == 0
        ? millis
        : millis + ThreadLocalRandom.current().nextLong(-spread, spread + 1);
  }

  private static String checked(TenantCacheKey key) {
    Objects.requireNonNull(key, "key");
    TenantContext.current()
        .filter(current -> !current.equals(key.tenant()))
        .ifPresent(
            current -> {
              throw new IllegalStateException(
                  "Khoá đệm thuộc tenant khác với ngữ cảnh tenant hiện hành");
            });
    return key.value();
  }
}
