package vn.viettel.okrkpi;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.shared.datasource.MissionCoreSchemaVersionGuard;
import vn.viettel.okrkpi.shared.datasource.ModuleName;

/**
 * Container PostgreSQL 17 dùng chung cho các kiểm thử tích hợp của {@code mission-core}, khởi tạo
 * đầy đủ vai trò ({@code mig_<schema>}, {@code svc_<schema>}, {@code ro_<schema>}, {@code
 * cdc_<schema>}) và chạy Flyway cho cả 9 schema nghiệp vụ/bản sao cùng {@code modulith} và {@code
 * keyring}.
 */
public final class MissionCoreTestDatabase {

  public static final String TEST_DB_PASSWORD = "test-mission-core-pwd";

  private static final PostgreSQLContainer<?> CONTAINER = TestPostgres.container("td_core");
  private static boolean initialized = false;

  private MissionCoreTestDatabase() {}

  public static synchronized PostgreSQLContainer<?> container() {
    if (!initialized) {
      CONTAINER.start();
      bootstrapAndMigrate();
      initialized = true;
    }
    return CONTAINER;
  }

  public static void registerDynamicProperties(DynamicPropertyRegistry registry) {
    PostgreSQLContainer<?> pg = container();
    String baseUrl = pg.getJdbcUrl();
    for (ModuleName module : ModuleName.values()) {
      String schema = module.schema();
      String prefix = "okrkpi.datasources." + schema;
      registry.add(
          prefix + ".url",
          () ->
              baseUrl
                  + "?currentSchema="
                  + schema
                  + "&prepareThreshold=0&ApplicationName=mission-core-"
                  + schema);
      registry.add(prefix + ".username", () -> "svc_" + schema);
      registry.add(prefix + ".password", () -> TEST_DB_PASSWORD);
    }
    registry.add(
        "okrkpi.datasources.org_replica.url",
        () ->
            baseUrl
                + "?currentSchema=org_replica&prepareThreshold=0&ApplicationName=mission-core-org_replica");
    registry.add("okrkpi.datasources.org_replica.username", () -> "svc_org_replica");
    registry.add("okrkpi.datasources.org_replica.password", () -> TEST_DB_PASSWORD);
    // Kiểm thử Spring Boot không có Kafka: không khởi động consumer org_replica (CORE-00-03).
    registry.add("okrkpi.inbox.listener-auto-startup", () -> "false");
    registry.add("mission.config.period-cache.listener.enabled", () -> "false");
  }

  private static void bootstrapAndMigrate() {
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.TENANT_DATA)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", TEST_DB_PASSWORD);
    }
    try (Connection admin =
        DriverManager.getConnection(
            CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword())) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(admin, DatabaseLayout.TENANT_DATA);
    } catch (SQLException ex) {
      throw new IllegalStateException("Không thể bootstrap CSDL kiểm thử cho mission-core", ex);
    }

    SchemaMigrator migrator = new SchemaMigrator(MigrationOptions.defaults());
    for (String schema : MissionCoreSchemaVersionGuard.REQUIRED_SCHEMAS) {
      migrator.migrate(
          CONTAINER.getJdbcUrl(),
          schema,
          new MigrationCredentials.Credentials("mig_" + schema, TEST_DB_PASSWORD));
    }
    migrator.migrate(
        CONTAINER.getJdbcUrl(),
        DatabaseLayout.MODULITH,
        new MigrationCredentials.Credentials("mig_modulith", TEST_DB_PASSWORD));
    migrator.migrate(
        CONTAINER.getJdbcUrl(),
        DatabaseLayout.KEYRING,
        new MigrationCredentials.Credentials("mig_keyring", TEST_DB_PASSWORD));
  }
}
