package vn.viettel.okrkpi.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * NEN-035, TL-03 "Bộ nhớ đệm Valkey" trên Valkey 8 thật: hai tenant cùng mã đối tượng không đọc
 * nhầm giá trị của nhau, khoá dựng cho tenant B bị chặn dưới ngữ cảnh A, và xoá theo tenant bằng
 * {@code SCAN} chỉ xoá khoá của tenant đó.
 */
class ValkeyTenantCacheIT {

  private static final TenantId A = TenantId.of(UUID.randomUUID());
  private static final TenantId B = TenantId.of(UUID.randomUUID());

  @SuppressWarnings("resource")
  private static final GenericContainer<?> VALKEY =
      new GenericContainer<>(DockerImageName.parse("mirror.gcr.io/valkey/valkey:8.1-alpine"))
          .withExposedPorts(6379);

  private static RedisClient client;
  private static StatefulRedisConnection<String, String> connection;
  private static TenantCache cache;

  @BeforeAll
  static void start() {
    VALKEY.start();
    client = RedisClient.create("redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
    connection = client.connect();
    cache = ValkeyTenantCache.standalone(connection);
  }

  @AfterAll
  static void stop() {
    connection.close();
    client.shutdown();
    VALKEY.stop();
  }

  private static TenantCacheKey keyOf(TenantId tenant, String taskId) {
    TenantCacheKey[] key = new TenantCacheKey[1];
    TenantContext.run(tenant, () -> key[0] = TenantCacheKey.of("task", "card", taskId));
    return key[0];
  }

  @Test
  void haiTenantCungMaDoiTuongKhongDocNhauVaKhongDungKhoaCheo() {
    String taskId = UUID.randomUUID().toString();
    TenantCacheKey keyA = keyOf(A, taskId);
    TenantCacheKey keyB = keyOf(B, taskId);
    assertThat(keyA).isNotEqualTo(keyB);

    TenantContext.run(A, () -> cache.put(keyA, "the-cua-A", Duration.ofMinutes(5)));
    TenantContext.run(
        B,
        () -> {
          assertThat(cache.get(TenantCacheKey.of("task", "card", taskId))).isEmpty();
          cache.put(keyB, "the-cua-B", Duration.ofMinutes(5));
        });
    TenantContext.run(
        A,
        () -> {
          assertThat(cache.get(TenantCacheKey.of("task", "card", taskId))).contains("the-cua-A");
          // Khoá dựng cho B (ví dụ lọt qua biến tĩnh) không được dùng dưới ngữ cảnh A.
          assertThatThrownBy(() -> cache.get(keyB)).isInstanceOf(IllegalStateException.class);
          assertThatThrownBy(() -> cache.evict(keyB)).isInstanceOf(IllegalStateException.class);
        });
    TenantContext.run(B, () -> assertThat(cache.get(keyB)).contains("the-cua-B"));
    assertThat(connection.sync().pttl(keyA.value())).isBetween(270_000L, 300_000L * 11 / 10);
  }

  @Test
  void xoaTheoTenantBangScanChiXoaKhoaCuaTenantDo() {
    TenantId c = TenantId.of(UUID.randomUUID());
    TenantId d = TenantId.of(UUID.randomUUID());
    int perTenant = ValkeyTenantCache.SCAN_BATCH * 2 + 17;
    for (TenantId t : new TenantId[] {c, d}) {
      TenantContext.run(
          t,
          () -> {
            for (int i = 0; i < perTenant; i++) {
              cache.put(TenantCacheKey.of("org", "unit_tree", "d" + i), "v", Duration.ofHours(1));
            }
            cache.put(TenantCacheKey.grouped("rl:u1", "minute"), "1", Duration.ofSeconds(60));
          });
    }

    assertThat(cache.evictTenant(c)).isEqualTo(perTenant + 1L);

    assertThat(connection.sync().keys(TenantCacheKey.scanPattern(c))).isEmpty();
    assertThat(connection.sync().keys(TenantCacheKey.scanPattern(d))).hasSize(perTenant + 1);
    TenantContext.run(
        d, () -> assertThat(cache.get(TenantCacheKey.of("org", "unit_tree", "d0"))).contains("v"));
  }
}
