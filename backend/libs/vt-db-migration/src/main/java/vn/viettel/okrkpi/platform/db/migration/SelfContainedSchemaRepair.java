package vn.viettel.okrkpi.platform.db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gỡ phần dùng chung V0_x ({@code db/common}, {@code db/common-business}) khỏi một schema tự chứa
 * đã lỡ chạy {@code MigrationRunner} theo cách cũ: lịch sử Flyway có các phiên bản {@code 0.x}, V1
 * của service thất bại vì trùng {@code outbox_event} rồi được hoàn tác, còn Flyway của service thì
 * từ chối khởi động vì gặp phiên bản không có trong thư mục migration của nó.
 *
 * <p>Chỉ gỡ khi lịch sử chưa có phiên bản nào từ 1 trở lên (service chưa từng chạy trên schema này)
 * và các bảng dùng chung còn rỗng; ngược lại báo lỗi để người vận hành xử lý tay, không xoá dữ
 * liệu. Toàn bộ chạy trong một giao dịch nên lần chạy lại sau sự cố vẫn an toàn; schema không có
 * V0_x thì không làm gì.
 */
final class SelfContainedSchemaRepair {

  private static final Logger LOG = LoggerFactory.getLogger(SelfContainedSchemaRepair.class);

  /** Bảng do V0_20..V0_24 tạo (NEN-032). */
  static final List<String> SHARED_TABLES =
      List.of("outbox_event", "inbox_processed", "inbox_aggregate_version", "idempotency_key");

  /** Hàm do V0_1..V0_3, V0_21, V0_23 tạo. */
  static final List<String> SHARED_FUNCTIONS =
      List.of(
          "fn_enable_tenant_rls",
          "fn_create_period_partitions",
          "fn_protect_partitions",
          "fn_purge_inbox_processed",
          "fn_run_partition_maintenance");

  private SelfContainedSchemaRepair() {}

  /**
   * Gỡ phần V0_x nếu có.
   *
   * @return số dòng lịch sử V0_x đã xoá; 0 khi schema không cần sửa
   * @throws IllegalStateException khi schema đã có migration riêng hoặc bảng dùng chung có dữ liệu
   */
  static int repair(Connection connection, String schema) throws SQLException {
    SchemaNames.require(schema);
    String history = schema + "." + SchemaMigrator.HISTORY_TABLE;
    if (!exists(connection, history)) {
      return 0;
    }
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try (Statement st = connection.createStatement()) {
      int common = count(st, "SELECT count(*) FROM " + history + " WHERE version LIKE '0.%'");
      if (common == 0) {
        connection.rollback();
        return 0;
      }
      int own =
          count(
              st,
              "SELECT count(*) FROM "
                  + history
                  + " WHERE version IS NOT NULL AND version <> '0' AND version NOT LIKE '0.%'");
      if (own > 0) {
        throw new IllegalStateException(
            "Schema "
                + schema
                + " vừa có phần dùng chung V0_x vừa có migration riêng; cần xử lý tay");
      }
      List<String> tables = new ArrayList<>();
      for (String table : SHARED_TABLES) {
        String qualified = schema + "." + table;
        if (!exists(connection, qualified)) {
          continue;
        }
        // Chủ bảng là mig_<schema>; bỏ FORCE để đếm không bị RLS che, giao dịch hoàn tác nếu lỗi.
        st.execute("ALTER TABLE " + qualified + " NO FORCE ROW LEVEL SECURITY");
        if (count(st, "SELECT count(*) FROM (SELECT 1 FROM " + qualified + " LIMIT 1) t") > 0) {
          throw new IllegalStateException(
              "Bảng " + qualified + " đã có dữ liệu; không tự gỡ phần dùng chung V0_x");
        }
        tables.add(qualified);
      }
      dropPartmanConfig(connection, st, tables);
      for (String table : tables) {
        st.execute("DROP TABLE " + table + " CASCADE");
      }
      for (String function : functions(connection, schema)) {
        st.execute("DROP FUNCTION " + function);
      }
      int removed = st.executeUpdate("DELETE FROM " + history + " WHERE version LIKE '0.%'");
      connection.commit();
      LOG.warn(
          "Schema {} tự chứa nhưng đã có phần dùng chung V0_x: đã gỡ {} bảng và {} dòng lịch sử",
          schema,
          tables.size(),
          removed);
      return removed;
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }

  /** Cấu hình và bảng mẫu pg_partman mà {@code partman.create_parent} của V0_20, V0_22 để lại. */
  private static void dropPartmanConfig(Connection connection, Statement st, List<String> tables)
      throws SQLException {
    if (tables.isEmpty() || !exists(connection, "partman.part_config")) {
      return;
    }
    List<String> templates = new ArrayList<>();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT template_table FROM partman.part_config"
                + " WHERE parent_table = ANY (?) AND template_table IS NOT NULL")) {
      ps.setArray(1, connection.createArrayOf("text", tables.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          templates.add(rs.getString(1));
        }
      }
    }
    try (PreparedStatement ps =
        connection.prepareStatement(
            "DELETE FROM partman.part_config WHERE parent_table = ANY (?)")) {
      ps.setArray(1, connection.createArrayOf("text", tables.toArray()));
      ps.executeUpdate();
    }
    for (String template : templates) {
      try (PreparedStatement ps = connection.prepareStatement("SELECT to_regclass(?)::text")) {
        ps.setString(1, template);
        try (ResultSet rs = ps.executeQuery()) {
          // to_regclass trả tên đã trích dẫn đúng, ghép vào câu lệnh an toàn.
          if (rs.next() && rs.getString(1) != null) {
            st.execute("DROP TABLE " + rs.getString(1));
          }
        }
      }
    }
  }

  private static List<String> functions(Connection connection, String schema) throws SQLException {
    List<String> out = new ArrayList<>();
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT p.oid::regprocedure::text FROM pg_proc p"
                + " JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = ? AND p.proname = ANY (?)")) {
      ps.setString(1, schema);
      ps.setArray(2, connection.createArrayOf("text", SHARED_FUNCTIONS.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static boolean exists(Connection connection, String relation) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      ps.setString(1, relation);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  private static int count(Statement st, String sql) throws SQLException {
    try (ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
