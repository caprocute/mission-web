package vn.viettel.okrkpi.platform.testkit;

import java.util.Map;
import java.util.Objects;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Hỗ trợ khởi tạo container Valkey 8 dùng chung ({@code withReuse(true)}) cho bộ nhớ đệm L2, khoá
 * phân tán và danh sách thu hồi JWT {@code jti} (NEN-020, TL-01 Phụ lục B).
 */
public final class ValkeyContainerSupport {

  public static final int VALKEY_PORT = 6379;
  public static final String DEFAULT_PASSWORD = "valkey_test_password";

  private static volatile GenericContainer<?> sharedValkey;

  private ValkeyContainerSupport() {}

  /** Tạo mới cấu hình Valkey 8 container (chưa khởi động). */
  @SuppressWarnings("resource")
  public static GenericContainer<?> createValkeyContainer() {
    return new GenericContainer<>(DockerImageName.parse(MissionTestImages.VALKEY))
        .withExposedPorts(VALKEY_PORT)
        .withCommand(
            "valkey-server",
            "--appendonly",
            "yes",
            "--maxmemory",
            "256mb",
            "--maxmemory-policy",
            "allkeys-lru",
            "--requirepass",
            DEFAULT_PASSWORD)
        .withReuse(true);
  }

  public static synchronized GenericContainer<?> sharedValkey() {
    if (sharedValkey == null) {
      sharedValkey = createValkeyContainer();
    }
    if (!sharedValkey.isRunning()) {
      sharedValkey.start();
    }
    return sharedValkey;
  }

  public static Map<String, String> buildSpringProperties(String host, int port, String password) {
    Objects.requireNonNull(host, "host must not be null");
    Objects.requireNonNull(password, "password must not be null");
    return Map.of(
        "spring.data.redis.host", host,
        "spring.data.redis.port", Integer.toString(port),
        "spring.data.redis.password", password);
  }

  public static void registerProperties(DynamicPropertyRegistry registry) {
    Objects.requireNonNull(registry, "registry must not be null");
    registry.add("spring.data.redis.host", () -> sharedValkey().getHost());
    registry.add(
        "spring.data.redis.port",
        () -> Integer.toString(sharedValkey().getMappedPort(VALKEY_PORT)));
    registry.add("spring.data.redis.password", () -> DEFAULT_PASSWORD);
  }
}
