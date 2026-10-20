package vn.viettel.okrkpi.platform.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.lint.ConventionLinter;
import vn.viettel.okrkpi.platform.db.migration.lint.Violation;

/**
 * Lớp cơ sở kiểm quy ước migration (NEN-030), dùng lại qua test-jar của {@code vt-db-migration}.
 * Mô-đun có migration kế thừa lớp này trong một lớp {@code *IT}, khai danh sách schema; lớp cơ sở
 * áp toàn bộ migration lên PostgreSQL 17 Testcontainers rồi chạy {@link ConventionLinter} và ghi
 * danh sách cột cần loại khỏi CDC phân tích ra {@code
 * target/generated-resources/cdc/<schema>-column-exclude-list.txt}.
 *
 * <pre>
 * class TaskMigrationConventionsIT extends MigrationConventionsTest {
 *   protected List&lt;String&gt; schemas() { return List.of("task"); }
 * }
 * </pre>
 */
public abstract class MigrationConventionsTest {

  /** Ảnh PostgreSQL dùng chung cho kiểm thử lược đồ (ADR-02: PostgreSQL 17). */
  public static final String POSTGRES_IMAGE = "postgres:17-alpine";

  /** Một container cho mọi lớp con trong cùng JVM; Ryuk của Testcontainers dọn khi JVM thoát. */
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(POSTGRES_IMAGE).withDatabaseName("lint");

  static {
    POSTGRES.start();
  }

  /** Các schema cần áp migration và lint, theo thứ tự áp. */
  protected abstract List<String> schemas();

  /**
   * Tuỳ chọn Flyway; mặc định theo quy ước thư mục và cho Flyway tạo schema (trong kiểm thử chưa có
   * bước tạo vai trò của NEN-031).
   */
  protected MigrationOptions options() {
    return MigrationOptions.defaults().withCreateSchemas();
  }

  /** Thư mục ghi danh sách cột loại khỏi CDC. */
  protected Path cdcOutputDirectory() {
    return Path.of("target", "generated-resources", "cdc");
  }

  @Test
  void migrationDungQuyUocDatTenKieuVaPhanLoai() throws SQLException, IOException {
    SchemaMigrator migrator = new SchemaMigrator(options(), getClass().getClassLoader());
    MigrationCredentials.Credentials admin =
        new MigrationCredentials.Credentials(POSTGRES.getUsername(), POSTGRES.getPassword());
    List<Violation> violations = new ArrayList<>();
    try (Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      for (String schema : schemas()) {
        migrator.migrate(POSTGRES.getJdbcUrl(), schema, admin);
        violations.addAll(ConventionLinter.lint(connection, schema));
        writeCdcExcludeList(
            schema, ConventionLinter.analyticsCdcExcludedColumns(connection, schema));
      }
    }
    assertThat(violations)
        .as("Vi phạm quy ước migration (TL-03 chương Giới thiệu):%n%s", joinLines(violations))
        .isEmpty();
  }

  private void writeCdcExcludeList(String schema, List<String> columns) throws IOException {
    Path dir = cdcOutputDirectory();
    Files.createDirectories(dir);
    Files.write(dir.resolve(schema + "-column-exclude-list.txt"), columns, StandardCharsets.UTF_8);
  }

  private static String joinLines(List<Violation> violations) {
    StringBuilder sb = new StringBuilder();
    for (Violation v : violations) {
      sb.append("  ").append(v).append(System.lineSeparator());
    }
    return sb.toString();
  }
}
