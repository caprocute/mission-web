package vn.viettel.okrkpi.platform.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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

  /** Container chưa khởi động với tên CSDL cho trước. */
  public static PostgreSQLContainer<?> container(String database) {
    return container().withDatabaseName(database);
  }
}
