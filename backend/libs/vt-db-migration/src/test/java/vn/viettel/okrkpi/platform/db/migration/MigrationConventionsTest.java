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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
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
  public static final String POSTGRES_IMAGE = TestPostgres.IMAGE;

  /** Một container cho mọi lớp con trong cùng JVM; Ryuk của Testcontainers dọn khi JVM thoát. */
  protected static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("lint");

  static {
    POSTGRES.start();
  }

  /** Các schema cần áp migration và lint, theo thứ tự áp. */
  protected abstract List<String> schemas();

  /** Mật khẩu thử nghiệm cho vai trò do lớp cơ sở tạo trong container. */
  private static final String TEST_PASSWORD = "mat-khau-thu-nghiem";

  /**
   * Tuỳ chọn Flyway; mặc định theo quy ước thư mục. Schema và bốn vai trò được tạo trước bằng
   * {@link DatabaseBootstrap#applySchemas} (NEN-031), rồi migration chạy bằng {@code mig_<schema>}
   * đúng như Job di trú thật.
   */
  protected MigrationOptions options() {
    return MigrationOptions.defaults();
  }

  /** Thư mục ghi danh sách cột loại khỏi CDC. */
  protected Path cdcOutputDirectory() {
    return Path.of("target", "generated-resources", "cdc");
  }

  @Test
  void migrationDungQuyUocDatTenKieuVaPhanLoai() throws SQLException, IOException {
    SchemaMigrator migrator = new SchemaMigrator(options(), getClass().getClassLoader());
    List<Violation> violations = new ArrayList<>();
    try (Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      Map<String, String> passwords = new HashMap<>();
      for (String schema : schemas()) {
        for (String role : RoleBootstrapPlan.roles(schema)) {
          passwords.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", TEST_PASSWORD);
        }
      }
      new DatabaseBootstrap(RoleLimits.load(), passwords).applySchemas(connection, schemas());
      for (String schema : schemas()) {
        migrator.migrate(
            POSTGRES.getJdbcUrl(),
            schema,
            new MigrationCredentials.Credentials(SchemaNames.migrationRole(schema), TEST_PASSWORD));
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
