package vn.viettel.okrkpi.platform.db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;

/**
 * Schema tự chứa {@code esign}, {@code docgen} (DV-10-01, DV-11): migration riêng của service là
 * nguồn duy nhất tạo {@code outbox_event}, inbox, idempotency. Dùng chính tệp migration thật của
 * esign-service và document-service, chạy bằng {@code mig_<schema>} sau bootstrap như dev.
 *
 * <ul>
 *   <li>CSDL trống: {@code MigrationRunner} áp đủ, lịch sử không có V0_x, Flyway của service thấy
 *       hợp lệ; chạy lại không áp thêm.
 *   <li>CSDL đã chạy dở theo cách cũ (V0_x đã áp, V1 thất bại vì trùng {@code outbox_event}):
 *       Flyway của service từ chối (nguyên nhân 503 ký số trên dev), {@code MigrationRunner} gỡ
 *       V0_x rồi áp V1..Vn.
 *   <li>Bảng dùng chung đã có dữ liệu: không gỡ, báo lỗi, lịch sử giữ nguyên.
 * </ul>
 */
class SelfContainedSchemaIT {

  private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("td_trong");

  private static final String MIG_PASSWORD = "mat-khau-thu-nghiem";

  private static final List<String> SCHEMAS = List.of("esign", "docgen");

  private static final List<String> DATABASES = List.of("td_trong", "td_do_dang", "td_co_du_lieu");

  /** Bản chép tệp migration thật của hai service, mỗi schema một thư mục con như classpath. */
  private static Path migrationsDir;

  @BeforeAll
  static void prepare() throws SQLException, IOException {
    Path services = Path.of("../../services").toAbsolutePath().normalize();
    migrationsDir = Files.createTempDirectory("self-contained");
    for (String schema : SCHEMAS) {
      String service = "esign".equals(schema) ? "esign-service" : "document-service";
      Path source = services.resolve(service + "/src/main/resources/db/migration/" + schema);
      Path target = Files.createDirectories(migrationsDir.resolve(schema));
      try (var files = Files.list(source)) {
        for (Path f : files.toList()) {
          Files.copy(f, target.resolve(f.getFileName()));
        }
      }
    }
    POSTGRES.start();
    try (Connection admin = adminConnection("td_trong");
        Statement st = admin.createStatement()) {
      st.execute("CREATE DATABASE td_do_dang");
      st.execute("CREATE DATABASE td_co_du_lieu");
    }
    Map<String, String> env = new HashMap<>();
    for (String schema : SCHEMAS) {
      for (String role : RoleBootstrapPlan.roles(schema)) {
        env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", MIG_PASSWORD);
      }
    }
    DatabaseBootstrap bootstrap = new DatabaseBootstrap(RoleLimits.load(), env);
    for (String db : DATABASES) {
      try (Connection admin = adminConnection(db)) {
        bootstrap.applySchemas(admin, SCHEMAS);
      }
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void csdlTrongApDuMigrationRiengVaChayLaiKhongApThem() throws SQLException {
    List<MigrationRunner.DatabaseOutcome> outcomes =
        runner().run(List.of(url("td_trong")), SCHEMAS, 1);
    assertThat(outcomes.get(0).success()).as(outcomes.get(0).error()).isTrue();
    assertThat(outcomes.get(0).appliedMigrations())
        .isEqualTo(ownMigrations("esign") + ownMigrations("docgen"));

    for (String schema : SCHEMAS) {
      assertThat(commonHistoryRows("td_trong", schema)).isZero();
      assertThat(isPartition("td_trong", schema + ".outbox_event_default")).isTrue();
      assertThat(serviceFlyway("td_trong", schema).validateWithResult().validationSuccessful)
          .isTrue();
    }

    List<MigrationRunner.DatabaseOutcome> again =
        runner().run(List.of(url("td_trong")), SCHEMAS, 1);
    assertThat(again.get(0).success()).isTrue();
    assertThat(again.get(0).appliedMigrations()).isZero();
  }

  @Test
  void csdlDaCoV0xDuocGoRoiApMigrationRieng() throws SQLException {
    for (String schema : SCHEMAS) {
      applyOldStyle("td_do_dang", schema);
      assertThat(commonHistoryRows("td_do_dang", schema)).isGreaterThan(0);
      assertThatThrownBy(() -> serviceFlyway("td_do_dang", schema).migrate())
          .isInstanceOf(FlywayException.class);
    }

    List<MigrationRunner.DatabaseOutcome> outcomes =
        runner().run(List.of(url("td_do_dang")), SCHEMAS, 1);
    assertThat(outcomes.get(0).success()).as(outcomes.get(0).error()).isTrue();

    for (String schema : SCHEMAS) {
      assertThat(commonHistoryRows("td_do_dang", schema)).isZero();
      assertThat(isPartition("td_do_dang", schema + ".outbox_event_default")).isTrue();
      assertThat(
              count(
                  "td_do_dang",
                  "SELECT count(*) FROM partman.part_config WHERE parent_table LIKE '"
                      + schema
                      + ".%'"))
          .isZero();
      assertThat(
              count(
                  "td_do_dang",
                  "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                      + " WHERE n.nspname = '"
                      + schema
                      + "' AND p.proname IN ('fn_enable_tenant_rls', 'fn_run_partition_maintenance')"))
          .isZero();
      // Flyway của service khởi động bình thường, không còn gì chờ áp.
      assertThat(serviceFlyway("td_do_dang", schema).migrate().migrationsExecuted).isZero();
    }
  }

  @Test
  void bangDungChungCoDuLieuThiKhongTuGo() throws SQLException {
    applyOldStyle("td_co_du_lieu", "docgen");
    try (Connection admin = adminConnection("td_co_du_lieu");
        Statement st = admin.createStatement()) {
      st.execute(
          "INSERT INTO docgen.inbox_processed (event_id, consumer, tenant_id, source_topic,"
              + " kafka_partition, kafka_offset) VALUES (gen_random_uuid(), 'thu', gen_random_uuid(),"
              + " 'org.unit.changed.v1', 0, 1)");
    }
    int before = commonHistoryRows("td_co_du_lieu", "docgen");

    List<MigrationRunner.DatabaseOutcome> outcomes =
        runner().run(List.of(url("td_co_du_lieu")), List.of("docgen"), 1);

    assertThat(outcomes.get(0).success()).isFalse();
    assertThat(outcomes.get(0).error()).contains("docgen.inbox_processed");
    assertThat(commonHistoryRows("td_co_du_lieu", "docgen")).isEqualTo(before);
  }

  /**
   * Cách chạy cũ: phần dùng chung V0_x trước thư mục riêng; V1 của service thất bại vì trùng bảng.
   */
  private static void applyOldStyle(String db, String schema) {
    Flyway old =
        Flyway.configure()
            .dataSource(url(db), SchemaNames.migrationRole(schema), MIG_PASSWORD)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(false)
            .table(SchemaMigrator.HISTORY_TABLE)
            .locations("classpath:db/common", "classpath:db/common-business", location(schema))
            .load();
    assertThatThrownBy(old::migrate).isInstanceOf(FlywayException.class);
  }

  /** Flyway như Spring Boot của service cấu hình ({@code spring.flyway.locations}). */
  private static Flyway serviceFlyway(String db, String schema) {
    return Flyway.configure()
        .dataSource(url(db), SchemaNames.migrationRole(schema), MIG_PASSWORD)
        .schemas(schema)
        .defaultSchema(schema)
        .table(SchemaMigrator.HISTORY_TABLE)
        .locations(location(schema))
        .load();
  }

  private static MigrationRunner runner() {
    MigrationCredentials credentials =
        (jdbcUrl, schema) ->
            new MigrationCredentials.Credentials(SchemaNames.migrationRole(schema), MIG_PASSWORD);
    MigrationOptions options =
        new MigrationOptions("classpath:db/common", "filesystem:" + migrationsDir + "/", false);
    return new MigrationRunner(credentials, options);
  }

  private static String location(String schema) {
    return "filesystem:" + migrationsDir.resolve(schema);
  }

  private static int ownMigrations(String schema) {
    try (var files = Files.list(migrationsDir.resolve(schema))) {
      return (int) files.filter(f -> f.getFileName().toString().startsWith("V")).count();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int commonHistoryRows(String db, String schema) throws SQLException {
    return count(
        db, "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE version LIKE '0.%'");
  }

  private static boolean isPartition(String db, String relation) throws SQLException {
    return count(
            db, "SELECT count(*) FROM pg_inherits WHERE inhrelid = to_regclass('" + relation + "')")
        == 1;
  }

  private static int count(String db, String sql) throws SQLException {
    try (Connection admin = adminConnection(db);
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static String url(String db) {
    return POSTGRES.getJdbcUrl().replace("/td_trong", "/" + db);
  }

  private static Connection adminConnection(String db) throws SQLException {
    return DriverManager.getConnection(url(db), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
