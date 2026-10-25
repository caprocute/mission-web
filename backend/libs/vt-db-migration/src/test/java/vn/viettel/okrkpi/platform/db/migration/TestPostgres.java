package vn.viettel.okrkpi.platform.db.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;

/**
 * Container PostgreSQL 17 dùng chung cho kiểm thử lược đồ, có sẵn tệp cài pg_partman 5.1.0
 * (NEN-032, xem {@code src/test/resources/pg_partman/README.md}). Chỉ chép tệp, không tạo
 * extension; {@code DatabaseBootstrap} tạo extension như ở môi trường thật.
 */
public final class TestPostgres {

  /** Ảnh PostgreSQL dùng chung cho kiểm thử lược đồ (ADR-02: PostgreSQL 17). */
  public static final String IMAGE = "postgres:17-alpine";

  /** Phiên bản pg_partman đi kèm kiểm thử. */
  public static final String PARTMAN_VERSION = "5.1.0";

  private static final String EXTENSION_DIR = "/usr/local/share/postgresql/extension/";

  private TestPostgres() {}

  /** Container chưa khởi động, CSDL mặc định {@code test}. */
  public static PostgreSQLContainer<?> container() {
    PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE);
    for (String file :
        new String[] {"pg_partman.control", "pg_partman--" + PARTMAN_VERSION + ".sql"}) {
      container.withCopyFileToContainer(
          MountableFile.forClasspathResource("pg_partman/" + file), EXTENSION_DIR + file);
    }
    return container;
  }

  /**
   * Cài pg_partman và cấp quyền cho {@code migRole} như bootstrap thật, dùng cho kiểm thử tự tạo
   * schema mà không chạy {@code DatabaseBootstrap}.
   */
  public static void installPartman(Connection admin, String migRole) throws SQLException {
    try (Statement st = admin.createStatement()) {
      for (String sql : RoleBootstrapPlan.partmanGrants(migRole)) {
        st.execute(sql);
      }
    }
  }

  /**
   * Mở kết nối riêng của kiểm thử với ngữ cảnh tenant đặt qua tham số khởi tạo phiên ({@code
   * options=-c app.tenant_id=...}), thay cho lệnh đặt biến mức phiên mà quy tắc Semgrep {@code
   * mission-no-session-set-app-param} cấm. Kết nối đi thẳng tới container, không qua PgBouncer, nên
   * ngữ cảnh gắn với đúng kết nối này và mất khi đóng (TL-03 "Đặt ngữ cảnh tenant an toàn qua
   * PgBouncer").
   */
  public static Connection connectAsTenant(
      String jdbcUrl, String user, String password, Object tenant) throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    props.setProperty("options", "-c app.tenant_id=" + tenant);
    return DriverManager.getConnection(jdbcUrl, props);
  }

  /** Container chưa khởi động với tên CSDL cho trước. */
  public static PostgreSQLContainer<?> container(String database) {
    return container().withDatabaseName(database);
  }
}
