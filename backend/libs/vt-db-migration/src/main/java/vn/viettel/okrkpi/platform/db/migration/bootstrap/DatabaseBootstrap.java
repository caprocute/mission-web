package vn.viettel.okrkpi.platform.db.migration.bootstrap;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Áp vai trò và quyền cho một CSDL (NEN-031), chạy bằng tài khoản quản trị cụm, không qua Flyway
 * của ứng dụng. Idempotent: chạy lại sau mỗi lần migration để bắt kịp quyền trên bảng mới tạo (ví
 * dụ {@code outbox_event} cho {@code cdc_<schema>}).
 *
 * <p>Mật khẩu: production do CloudNativePG (managed roles) và External Secrets cấp, bộ này không
 * đặt; ở dev và kiểm thử, nếu có biến {@code <VAI_TRO>_PASSWORD} (ví dụ {@code SVC_TASK_PASSWORD})
 * thì đặt mật khẩu cho vai trò đó.
 *
 * <pre>
 *   PG_ADMIN_USER=... PG_ADMIN_PASSWORD=... java ... DatabaseBootstrap \
 *     --db=jdbc:postgresql://pg-biz-rw/td_hcm --layout=td
 *   java ... DatabaseBootstrap --print-sql --database=td_hcm --layout=td   # in SQL để duyệt
 * </pre>
 */
public final class DatabaseBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(DatabaseBootstrap.class);

  private final RoleLimits limits;
  private final Map<String, String> passwords;

  /**
   * @param passwords bản đồ biến môi trường; chỉ khoá dạng {@code <VAI_TRO>_PASSWORD} được dùng
   */
  public DatabaseBootstrap(RoleLimits limits, Map<String, String> passwords) {
    this.limits = limits;
    this.passwords = Map.copyOf(passwords);
  }

  public static void main(String[] args) throws SQLException {
    String url = null;
    String database = null;
    DatabaseLayout layout = null;
    String tenancy = null;
    boolean printOnly = false;
    for (String arg : args) {
      if (arg.startsWith("--db=")) {
        url = arg.substring("--db=".length());
      } else if (arg.startsWith("--database=")) {
        database = arg.substring("--database=".length());
      } else if (arg.startsWith("--layout=")) {
        layout = DatabaseLayout.fromCode(arg.substring("--layout=".length()));
      } else if (arg.startsWith("--tenancy=")) {
        tenancy = arg.substring("--tenancy=".length());
      } else if ("--print-sql".equals(arg)) {
        printOnly = true;
      } else {
        throw new IllegalArgumentException("Tham số lạ: " + arg);
      }
    }
    if (layout == null) {
      throw new IllegalArgumentException("Cần --layout=td hoặc --layout=cell_shared");
    }
    if (printOnly) {
      if (database == null) {
        throw new IllegalArgumentException("--print-sql cần --database=<tên CSDL>");
      }
      for (String s : RoleBootstrapPlan.statements(database, layout, RoleLimits.load())) {
        System.out.println(s + ";");
      }
      if (tenancy != null) {
        System.out.println(RoleBootstrapPlan.tenancyModelStatement(database, tenancy) + ";");
      }
      return;
    }
    if (url == null) {
      throw new IllegalArgumentException("Cần --db=<chuỗi kết nối JDBC>");
    }
    Map<String, String> env = System.getenv();
    try (Connection admin =
        DriverManager.getConnection(url, env.get("PG_ADMIN_USER"), env.get("PG_ADMIN_PASSWORD"))) {
      DatabaseBootstrap bootstrap = new DatabaseBootstrap(RoleLimits.load(), env);
      bootstrap.apply(admin, layout);
      if (tenancy != null) {
        bootstrap.setTenancyModel(admin, tenancy);
      }
    }
  }

  /** Áp toàn bộ vai trò và quyền lên CSDL mà {@code admin} đang kết nối. */
  public void apply(Connection admin, DatabaseLayout layout) throws SQLException {
    String database = currentDatabase(admin);
    execute(
        admin,
        RoleBootstrapPlan.statements(database, layout, limits),
        RoleBootstrapPlan.roles(layout));
    LOG.info("Đã áp vai trò, quyền cho CSDL {} (bố trí {})", database, layout.code());
  }

  /**
   * Áp vai trò và quyền cho các schema đơn lẻ, không kèm ngoại lệ bố trí (xem {@link
   * RoleBootstrapPlan#schemaStatements}).
   */
  public void applySchemas(Connection admin, List<String> schemas) throws SQLException {
    List<String> statements = new java.util.ArrayList<>();
    List<String> roles = new java.util.ArrayList<>();
    for (String schema : schemas) {
      statements.addAll(RoleBootstrapPlan.schemaStatements(schema, limits));
      roles.addAll(RoleBootstrapPlan.roles(schema));
    }
    execute(admin, statements, roles);
  }

  /** Ghi mô hình thuê bao của CSDL hiện tại vào tham số mức CSDL {@code app.tenancy_model}. */
  public void setTenancyModel(Connection admin, String model) throws SQLException {
    try (Statement st = admin.createStatement()) {
      st.execute(RoleBootstrapPlan.tenancyModelStatement(currentDatabase(admin), model));
    }
  }

  private void execute(Connection admin, List<String> statements, List<String> roles)
      throws SQLException {
    boolean autoCommit = admin.getAutoCommit();
    admin.setAutoCommit(false);
    try (Statement st = admin.createStatement()) {
      for (String sql : statements) {
        st.execute(sql);
      }
      setPasswords(admin, roles);
      admin.commit();
    } catch (SQLException e) {
      admin.rollback();
      throw e;
    } finally {
      admin.setAutoCommit(autoCommit);
    }
  }

  private void setPasswords(Connection admin, List<String> roles) throws SQLException {
    for (String role : roles) {
      String password = passwords.get(role.toUpperCase(Locale.ROOT) + "_PASSWORD");
      if (password == null) {
        continue;
      }
      // pgjdbc băm SCRAM-SHA-256 phía client nên mật khẩu dạng rõ không đi qua máy chủ, nhật ký
      // câu lệnh hay pg_stat_statements.
      admin.unwrap(PGConnection.class).alterUserPassword(role, password.toCharArray(), null);
    }
  }

  private static String currentDatabase(Connection admin) throws SQLException {
    try (Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT current_database()")) {
      rs.next();
      return rs.getString(1);
    }
  }
}
