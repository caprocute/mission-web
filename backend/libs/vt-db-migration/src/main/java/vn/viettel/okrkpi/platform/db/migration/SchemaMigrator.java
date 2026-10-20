package vn.viettel.okrkpi.platform.db.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

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
        .locations(options.commonLocation(), options.schemaLocation(schema))
        .failOnMissingLocations(false)
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .outOfOrder(false)
        .load();
  }

  /** Áp mọi migration còn thiếu; ném lỗi Flyway nếu một migration thất bại. */
  public MigrateResult migrate(
      String jdbcUrl, String schema, MigrationCredentials.Credentials credentials) {
    return flyway(jdbcUrl, schema, credentials).migrate();
  }
}
