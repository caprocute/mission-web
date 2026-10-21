package vn.viettel.okrkpi.platform.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Hỗ trợ khởi tạo container OPA 1.x (đánh giá chính sách RBAC/ABAC Rego) và OpenBao 2.x (Transit
 * KEK + KV v2) dùng chung ({@code withReuse(true)}) cho kiểm thử tích hợp bảo mật (NEN-020, ADR-05,
 * ADR-06).
 */
public final class SecurityContainerSupport {

  public static final int OPA_PORT = 8181;
  public static final int OPENBAO_PORT = 8200;
  public static final String DEFAULT_OPENBAO_TOKEN = "mission_openbao_test_token";
  public static final List<String> DEFAULT_TRANSIT_KEYS =
      List.of("kek-default", "kek-tenant-demo", "blind-index-default");

  private static volatile GenericContainer<?> sharedOpa;
  private static volatile GenericContainer<?> sharedOpenBao;
  private static volatile boolean openBaoInitialized;

  private SecurityContainerSupport() {}

  /** Tạo mới cấu hình OPA 1.x container nạp sẵn {@code infra/dev/opa/policies/authz.rego}. */
  @SuppressWarnings("resource")
  public static GenericContainer<?> createOpaContainer() {
    GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(MissionTestImages.OPA))
            .withExposedPorts(OPA_PORT)
            .withCommand("run", "--server", "--addr=0.0.0.0:" + OPA_PORT, "/policies")
            .withReuse(true);
    Path policyPath = resolveOpaPolicyFile();
    if (policyPath != null) {
      container.withCopyFileToContainer(
          MountableFile.forHostPath(policyPath), "/policies/authz.rego");
    }
    return container;
  }

  /** Tạo mới cấu hình OpenBao 2.x container chế độ {@code -dev} (chưa khởi động). */
  @SuppressWarnings("resource")
  public static GenericContainer<?> createOpenBaoContainer() {
    return new GenericContainer<>(DockerImageName.parse(MissionTestImages.OPENBAO))
        .withExposedPorts(OPENBAO_PORT)
        .withEnv("BAO_DEV_ROOT_TOKEN_ID", DEFAULT_OPENBAO_TOKEN)
        .withEnv("BAO_ADDR", "http://127.0.0.1:" + OPENBAO_PORT)
        .withCommand(
            "server",
            "-dev",
            "-dev-listen-address=0.0.0.0:" + OPENBAO_PORT,
            "-dev-root-token-id=" + DEFAULT_OPENBAO_TOKEN)
        .withReuse(true);
  }

  public static synchronized GenericContainer<?> sharedOpa() {
    if (sharedOpa == null) {
      sharedOpa = createOpaContainer();
    }
    if (!sharedOpa.isRunning()) {
      sharedOpa.start();
    }
    return sharedOpa;
  }

  public static synchronized GenericContainer<?> sharedOpenBao() {
    if (sharedOpenBao == null) {
      sharedOpenBao = createOpenBaoContainer();
    }
    if (!sharedOpenBao.isRunning()) {
      sharedOpenBao.start();
    }
    if (!openBaoInitialized) {
      initializeOpenBaoEngines(sharedOpenBao);
      openBaoInitialized = true;
    }
    return sharedOpenBao;
  }

  public static Map<String, String> buildSpringProperties(
      String opaHost, int opaPort, String openBaoHost, int openBaoPort, String openBaoToken) {
    Objects.requireNonNull(opaHost, "opaHost must not be null");
    Objects.requireNonNull(openBaoHost, "openBaoHost must not be null");
    Objects.requireNonNull(openBaoToken, "openBaoToken must not be null");
    return Map.of(
        "okrkpi.security.opa.url",
        "http://" + opaHost + ":" + opaPort + "/v1/data/okrkpi/authz/decision",
        "okrkpi.crypto.openbao.url",
        "http://" + openBaoHost + ":" + openBaoPort,
        "okrkpi.crypto.openbao.token",
        openBaoToken);
  }

  public static void registerProperties(DynamicPropertyRegistry registry) {
    Objects.requireNonNull(registry, "registry must not be null");
    registry.add(
        "okrkpi.security.opa.url",
        () ->
            "http://"
                + sharedOpa().getHost()
                + ":"
                + sharedOpa().getMappedPort(OPA_PORT)
                + "/v1/data/okrkpi/authz/decision");
    registry.add(
        "okrkpi.crypto.openbao.url",
        () ->
            "http://"
                + sharedOpenBao().getHost()
                + ":"
                + sharedOpenBao().getMappedPort(OPENBAO_PORT));
    registry.add("okrkpi.crypto.openbao.token", () -> DEFAULT_OPENBAO_TOKEN);
  }

  static Path resolveOpaPolicyFile() {
    Path[] candidates = {
      Path.of("infra/dev/opa/policies/authz.rego"),
      Path.of("../infra/dev/opa/policies/authz.rego"),
      Path.of("../../infra/dev/opa/policies/authz.rego"),
      Path.of("../../../infra/dev/opa/policies/authz.rego")
    };
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    return null;
  }

  private static void initializeOpenBaoEngines(GenericContainer<?> container) {
    try {
      container.execInContainer("bao", "secrets", "enable", "-path=transit", "transit");
      for (String key : DEFAULT_TRANSIT_KEYS) {
        ExecResult res =
            container.execInContainer(
                "bao",
                "write",
                "-f",
                "transit/keys/" + key,
                "type=aes256-gcm96",
                "exportable=false");
        if (res.getExitCode() != 0) {
          throw new IllegalStateException(
              "Không thể tạo khoá Transit " + key + ": " + res.getStderr());
        }
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Không thể khởi tạo OpenBao Transit KEK cho kiểm thử", ex);
    }
  }
}
