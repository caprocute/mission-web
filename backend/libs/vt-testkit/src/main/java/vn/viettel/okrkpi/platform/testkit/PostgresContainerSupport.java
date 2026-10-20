package vn.viettel.okrkpi.platform.testkit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;

/**
 * Hỗ trợ khởi tạo container PostgreSQL 17 dùng chung ({@code withReuse(true)}) kèm 21 schema, tiện
 * ích {@code pgcrypto}/{@code partman}, bộ 4 tài khoản {@code mig_<schema>}, {@code svc_<schema>},
 * {@code ro_<schema>}, {@code cdc_<schema>} (đều {@code NOBYPASSRLS}) và PgBouncer chế độ {@code
 * transaction} (NEN-020, NEN-031, NEN-033, TL-03).
 */
public final class PostgresContainerSupport {

  public static final String DEFAULT_DATABASE = "mission";
  public static final String DEFAULT_SUPERUSER = "mission_admin";
  public static final String DEFAULT_PASSWORD = "mission_test_password";

  private static final List<String> EXTRA_DEV_SCHEMAS =
      List.of("registry", "esign", "ai", "apicurio");

  private static volatile PostgreSQLContainer<?> sharedPostgres;
  private static volatile boolean initialized;

  private PostgresContainerSupport() {}

  /**
   * Tạo mới cấu hình PostgreSQL 17 container chuẩn kèm tệp tiện ích {@code pg_partman} (chưa khởi
   * động).
   */
  @SuppressWarnings("resource")
  public static PostgreSQLContainer<?> createPostgresContainer() {
    PostgreSQLContainer<?> container =
        new PostgreSQLContainer<>(DockerImageName.parse(MissionTestImages.POSTGRES))
            .withDatabaseName(DEFAULT_DATABASE)
            .withUsername(DEFAULT_SUPERUSER)
            .withPassword(DEFAULT_PASSWORD)
            .withCommand(
                "postgres",
                "-c",
                "wal_level=logical",
                "-c",
                "max_wal_senders=10",
                "-c",
                "max_replication_slots=10",
                "-c",
                "max_connections=200")
            .withReuse(true);
    for (String file : new String[] {"pg_partman.control", "pg_partman--5.1.0.sql"}) {
      container.withCopyFileToContainer(
          MountableFile.forClasspathResource("pg_partman/" + file),
          "/usr/share/postgresql/17/extension/" + file);
    }
    return container;
  }

  /** Tạo mới cấu hình PgBouncer container chế độ {@code transaction} nối tới PostgreSQL. */
  @SuppressWarnings("resource")
  public static GenericContainer<?> createPgBouncerContainer(
      Network network, String postgresNetworkAlias) {
    Objects.requireNonNull(network, "network must not be null");
    Objects.requireNonNull(postgresNetworkAlias, "postgresNetworkAlias must not be null");
    return new GenericContainer<>(DockerImageName.parse(MissionTestImages.PGBOUNCER))
        .withNetwork(network)
        .withExposedPorts(6432)
        .withEnv("DB_HOST", postgresNetworkAlias)
        .withEnv("DB_PORT", "5432")
        .withEnv("DB_USER", DEFAULT_SUPERUSER)
        .withEnv("DB_PASSWORD", DEFAULT_PASSWORD)
        .withEnv("POOL_MODE", "transaction")
        .withEnv("MAX_PREPARED_STATEMENTS", "200")
        .withEnv("DEFAULT_POOL_SIZE", "20")
        .withEnv("MAX_CLIENT_CONN", "2000")
        .withEnv("SERVER_RESET_QUERY", "DISCARD ALL")
        .withReuse(true);
  }

  /** Trả về instance PostgreSQL 17 dùng chung đã khởi động và đã chạy khởi tạo schema/role. */
  public static synchronized PostgreSQLContainer<?> sharedPostgres() {
    if (sharedPostgres == null) {
      sharedPostgres = createPostgresContainer();
    }
    if (!sharedPostgres.isRunning()) {
      sharedPostgres.start();
    }
    if (!initialized) {
      initializeSchemasAndRoles(
          sharedPostgres.getJdbcUrl(),
          sharedPostgres.getUsername(),
          sharedPostgres.getPassword(),
          DEFAULT_PASSWORD);
      initialized = true;
    }
    return sharedPostgres;
  }

  /**
   * Thực thi khởi tạo toàn bộ schema và bộ 4 tài khoản {@code mig_/svc_/ro_/cdc_} trên kết nối JDBC
   * superuser bất kỳ.
   */
  public static void initializeSchemasAndRoles(
      String superuserJdbcUrl, String superuser, String superuserPassword, String rolePassword) {
    Objects.requireNonNull(superuserJdbcUrl, "superuserJdbcUrl must not be null");
    Objects.requireNonNull(superuser, "superuser must not be null");
    Objects.requireNonNull(superuserPassword, "superuserPassword must not be null");
    Objects.requireNonNull(rolePassword, "rolePassword must not be null");

    Map<String, String> passwordMap = buildUniformPasswordMap(rolePassword);
    DatabaseBootstrap bootstrap = new DatabaseBootstrap(RoleLimits.load(), passwordMap);
    try (Connection conn =
        DriverManager.getConnection(superuserJdbcUrl, superuser, superuserPassword)) {
      bootstrap.apply(conn, DatabaseLayout.TENANT_DATA);
      bootstrap.applySchemas(conn, DatabaseLayout.CELL_SHARED.schemas());
      bootstrap.applySchemas(conn, EXTRA_DEV_SCHEMAS);
      try (java.sql.Statement st = conn.createStatement()) {
        for (String extra : DatabaseLayout.CELL_SHARED.schemas()) {
          st.execute(
              "GRANT CONNECT ON DATABASE "
                  + DEFAULT_DATABASE
                  + " TO "
                  + String.join(", ", RoleBootstrapPlan.roles(extra)));
        }
        for (String extra : EXTRA_DEV_SCHEMAS) {
          st.execute(
              "GRANT CONNECT ON DATABASE "
                  + DEFAULT_DATABASE
                  + " TO "
                  + String.join(", ", RoleBootstrapPlan.roles(extra)));
        }
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("Không thể khởi tạo schema và role cho CSDL kiểm thử", ex);
    }
  }

  /**
   * Trả về danh sách toàn bộ 21 schema được tạo trong CSDL {@code mission} ở môi trường dev/test.
   */
  public static List<String> allMissionSchemas() {
    List<String> list = new ArrayList<>(DatabaseLayout.TENANT_DATA.schemas());
    list.addAll(DatabaseLayout.CELL_SHARED.schemas());
    list.addAll(EXTRA_DEV_SCHEMAS);
    return List.copyOf(list);
  }

  public static String migUsername(String schema) {
    return RoleBootstrapPlan.role("mig", schema);
  }

  public static String svcUsername(String schema) {
    return RoleBootstrapPlan.role("svc", schema);
  }

  public static String roUsername(String schema) {
    return RoleBootstrapPlan.role("ro", schema);
  }

  public static String cdcUsername(String schema) {
    return RoleBootstrapPlan.role("cdc", schema);
  }

  /**
   * Tạo bản đồ thuộc tính Spring Boot chuẩn cho một schema cụ thể: ứng dụng kết nối bằng {@code
   * svc_<schema>} còn Flyway kết nối bằng {@code mig_<schema>}.
   */
  public static Map<String, String> buildSpringProperties(
      String jdbcUrl, String schema, String rolePassword) {
    Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    Objects.requireNonNull(rolePassword, "rolePassword must not be null");
    return Map.of(
        "spring.datasource.url", jdbcUrl,
        "spring.datasource.username", svcUsername(schema),
        "spring.datasource.password", rolePassword,
        "spring.flyway.url", jdbcUrl,
        "spring.flyway.user", migUsername(schema),
        "spring.flyway.password", rolePassword,
        "spring.flyway.schemas", schema,
        "spring.flyway.default-schema", schema);
  }

  /**
   * Đăng ký thuộc tính kết nối CSDL theo đúng phân tách {@code svc_<schema>} / {@code
   * mig_<schema>}.
   */
  public static void registerProperties(DynamicPropertyRegistry registry, String schema) {
    Objects.requireNonNull(registry, "registry must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    registry.add("spring.datasource.url", () -> sharedPostgres().getJdbcUrl());
    registry.add("spring.datasource.username", () -> svcUsername(schema));
    registry.add("spring.datasource.password", () -> DEFAULT_PASSWORD);
    registry.add("spring.flyway.url", () -> sharedPostgres().getJdbcUrl());
    registry.add("spring.flyway.user", () -> migUsername(schema));
    registry.add("spring.flyway.password", () -> DEFAULT_PASSWORD);
    registry.add("spring.flyway.schemas", () -> schema);
    registry.add("spring.flyway.default-schema", () -> schema);
  }

  private static Map<String, String> buildUniformPasswordMap(String rolePassword) {
    Map<String, String> map = new LinkedHashMap<>();
    for (String schema : allMissionSchemas()) {
      for (String kind : RoleBootstrapPlan.ROLE_KINDS) {
        String role = RoleBootstrapPlan.role(kind, schema);
        map.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", rolePassword);
      }
    }
    return map;
  }
}
