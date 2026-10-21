package vn.viettel.okrkpi.platform.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Hỗ trợ khởi tạo container Keycloak 26 dùng chung ({@code withReuse(true)}) nạp sẵn {@code
 * infra/dev/keycloak/realm-mission.json} cho kiểm thử đầu-cuối OIDC (NEN-020, ADR-05).
 */
public final class KeycloakContainerSupport {

  public static final int KEYCLOAK_HTTP_PORT = 8080;
  public static final String REALM_NAME = "mission";
  public static final String ADMIN_USER = "admin";
  public static final String ADMIN_PASSWORD = "keycloak_test_admin_pass";

  private static volatile GenericContainer<?> sharedKeycloak;

  private KeycloakContainerSupport() {}

  /** Tạo mới cấu hình Keycloak 26 container nạp sẵn realm {@code mission} (chưa khởi động). */
  @SuppressWarnings("resource")
  public static GenericContainer<?> createKeycloakContainer() {
    GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(MissionTestImages.KEYCLOAK))
            .withExposedPorts(KEYCLOAK_HTTP_PORT)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN_USER)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("KC_HOSTNAME_STRICT", "false")
            .withCommand(
                "start-dev",
                "--import-realm",
                "--http-port=" + KEYCLOAK_HTTP_PORT,
                "--health-enabled=true")
            .withReuse(true);

    Path realmPath = resolveRealmFile();
    if (realmPath != null) {
      container.withCopyFileToContainer(
          MountableFile.forHostPath(realmPath), "/opt/keycloak/data/import/realm-mission.json");
    }
    return container;
  }

  public static synchronized GenericContainer<?> sharedKeycloak() {
    if (sharedKeycloak == null) {
      sharedKeycloak = createKeycloakContainer();
    }
    if (!sharedKeycloak.isRunning()) {
      sharedKeycloak.start();
    }
    return sharedKeycloak;
  }

  public static String issuerUri(String host, int port) {
    Objects.requireNonNull(host, "host must not be null");
    return "http://" + host + ":" + port + "/realms/" + REALM_NAME;
  }

  public static Map<String, String> buildSpringProperties(String host, int port) {
    String issuer = issuerUri(host, port);
    return Map.of(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        issuer,
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        issuer + "/protocol/openid-connect/certs");
  }

  public static void registerProperties(DynamicPropertyRegistry registry) {
    Objects.requireNonNull(registry, "registry must not be null");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () ->
            issuerUri(
                sharedKeycloak().getHost(), sharedKeycloak().getMappedPort(KEYCLOAK_HTTP_PORT)));
  }

  static Path resolveRealmFile() {
    Path[] candidates = {
      Path.of("infra/dev/keycloak/realm-mission.json"),
      Path.of("../infra/dev/keycloak/realm-mission.json"),
      Path.of("../../infra/dev/keycloak/realm-mission.json"),
      Path.of("../../../infra/dev/keycloak/realm-mission.json")
    };
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    return null;
  }
}
