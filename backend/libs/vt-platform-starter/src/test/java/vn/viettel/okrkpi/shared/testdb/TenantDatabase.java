package vn.viettel.okrkpi.shared.testdb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.SchemaNames;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;

/**
 * CSDL dữ liệu tenant dùng chung cho kiểm thử tích hợp của starter: PostgreSQL 17 + pg_partman
 * ({@link TestPostgres} của NEN-032), bootstrap vai trò như môi trường thật ({@link
 * DatabaseBootstrap}), migrate {@code keyring} rồi {@code task} bằng {@code mig_<schema>}. Ứng dụng
 * kết nối bằng {@code svc_task} (chỉ DML, RLS FORCE), không bằng siêu người dùng.
 *
 * <p>Khởi động một lần cho cả JVM kiểm thử; Ryuk của Testcontainers dọn container khi JVM thoát.
 */
public final class TenantDatabase {

  /** Mật khẩu thử của mọi vai trò (chỉ trong container kiểm thử). */
  public static final String PASSWORD = "mat-khau-thu-nghiem";

  /** Schema được migrate, theo thứ tự (keyring trước schema có cột _enc). */
  public static final List<String> SCHEMAS = List.of("keyring", "task");

  private static PostgreSQLContainer<?> container;

  private TenantDatabase() {}

  /** Container đã khởi động và đã migrate. */
  public static synchronized PostgreSQLContainer<?> start() {
    if (container != null) {
      return container;
    }
    PostgreSQLContainer<?> pg = TestPostgres.container("td_starter");
    pg.start();
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.TENANT_DATA)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(admin, DatabaseLayout.TENANT_DATA);
    } catch (SQLException e) {
      throw new IllegalStateException("Không bootstrap được CSDL kiểm thử", e);
    }
    SchemaMigrator migrator = new SchemaMigrator(MigrationOptions.defaults());
    for (String schema : SCHEMAS) {
      migrator.migrate(
          pg.getJdbcUrl(),
          schema,
          new MigrationCredentials.Credentials(SchemaNames.migrationRole(schema), PASSWORD));
    }
    container = pg;
    return pg;
  }

  public static String jdbcUrl() {
    return start().getJdbcUrl();
  }

  /** Kết nối bằng một vai trò đã bootstrap, ví dụ {@code svc_task}. */
  public static Connection connect(String role) throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), role, PASSWORD);
  }

  /** Kết nối siêu người dùng của container (chỉ để kiểm tra, không dùng cho ứng dụng). */
  public static Connection admin() throws SQLException {
    PostgreSQLContainer<?> pg = start();
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  /** DataSource không gộp kết nối của một vai trò. */
  public static DriverManagerDataSource dataSource(String role) {
    DriverManagerDataSource ds = new DriverManagerDataSource(jdbcUrl(), role, PASSWORD);
    ds.setDriverClassName("org.postgresql.Driver");
    return ds;
  }
}
