package vn.viettel.okrkpi.platform.db.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;

/**
 * Áp migration Flyway cho đúng một schema theo quy ước TL-03 "Flyway và quy trình thay đổi lược
 * đồ": lịch sử {@code flyway_schema_history} nằm trong chính schema đó, thư mục migration gồm phần
 * dùng chung và phần riêng của schema, không bao giờ cho phép {@code clean}.
 */
public final class SchemaMigrator {

  /** Tên bảng lịch sử Flyway, nằm trong từng schema. */
  public static final String HISTORY_TABLE = "flyway_schema_history";

  private final MigrationOptions options;
  private final ClassLoader classLoader;

  public SchemaMigrator(MigrationOptions options) {
    this(options, Thread.currentThread().getContextClassLoader());
  }

  public SchemaMigrator(MigrationOptions options, ClassLoader classLoader) {
    this.options = options;
    this.classLoader = classLoader;
  }

  /** Cấu hình Flyway đã dựng sẵn cho schema; dùng lại ở kiểm thử. */
  public Flyway flyway(
      String jdbcUrl, String schema, MigrationCredentials.Credentials credentials) {
    SchemaNames.require(schema);
    return Flyway.configure(classLoader)
        .dataSource(jdbcUrl, credentials.user(), credentials.password())
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(options.createSchemas())
        .table(HISTORY_TABLE)
        .locations(locations(schema))
        .failOnMissingLocations(false)
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .outOfOrder(false)
        .load();
  }

  /**
   * Thư mục migration của schema: dùng chung, dùng chung nghiệp vụ (nếu là schema nghiệp vụ),
   * riêng. Schema tự chứa chỉ có thư mục riêng, giống cấu hình Flyway của chính service, để hai
   * đường áp migration cho cùng lịch sử và checksum.
   */
  String[] locations(String schema) {
    if (DatabaseLayout.isSelfContained(schema)) {
      return new String[] {options.schemaLocation(schema)};
    }
    if (DatabaseLayout.isBusinessSchema(schema)) {
      return new String[] {
        options.commonLocation(), options.businessCommonLocation(), options.schemaLocation(schema)
      };
    }
    return new String[] {options.commonLocation(), options.schemaLocation(schema)};
  }

  /**
   * Áp mọi migration còn thiếu; ném lỗi Flyway nếu một migration thất bại. Schema tự chứa được gỡ
   * phần V0_x đã áp nhầm trước (xem {@link SelfContainedSchemaRepair}).
   */
  public MigrateResult migrate(
      String jdbcUrl, String schema, MigrationCredentials.Credentials credentials) {
    if (DatabaseLayout.isSelfContained(schema)) {
      try (Connection connection =
          DriverManager.getConnection(jdbcUrl, credentials.user(), credentials.password())) {
        SelfContainedSchemaRepair.repair(connection, schema);
      } catch (SQLException e) {
        throw new IllegalStateException(
            "Không gỡ được phần dùng chung V0_x của schema " + schema + ": " + e.getMessage(), e);
      }
    }
    return flyway(jdbcUrl, schema, credentials).migrate();
  }
}
