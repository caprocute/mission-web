package vn.viettel.okrkpi.platform.db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;

/**
 * RLS chuẩn và phân vùng theo kỳ ở hai chế độ bridge và pool (NEN-033; TL-03 "Bảo mật theo dòng",
 * "Phân vùng", "Bố trí theo mô hình pool"). Mỗi chế độ là một CSDL riêng trong cùng container.
 */
class TenantRlsPartitionIT {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(MigrationConventionsTest.POSTGRES_IMAGE)
          .withDatabaseName("td_bridge");

  private static final String SCHEMA = "rls_demo";
  private static final String PWD = "mat-khau-thu-nghiem";
  private static final UUID TENANT_A = UUID.fromString("01927a00-0000-7000-8000-00000000000a");
  private static final UUID TENANT_B = UUID.fromString("01927a00-0000-7000-8000-00000000000b");
  private static final Map<String, Integer> CREATED = new HashMap<>();

  @BeforeAll
  static void prepare() throws SQLException {
    POSTGRES.start();
    try (Connection admin = admin("td_bridge");
        Statement st = admin.createStatement()) {
      st.execute("CREATE DATABASE td_pool");
    }
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(SCHEMA)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PWD);
    }
    DatabaseBootstrap bootstrap = new DatabaseBootstrap(RoleLimits.load(), env);
    SchemaMigrator migrator =
        new SchemaMigrator(
            new MigrationOptions("classpath:db/common", "classpath:db/test-rls/", false));
    for (String db : List.of("td_bridge", "td_pool")) {
      try (Connection admin = admin(db)) {
        bootstrap.applySchemas(admin, List.of(SCHEMA));
        if (db.equals("td_pool")) {
          bootstrap.setTenancyModel(admin, "pool");
        }
      }
      migrator.migrate(url(db), SCHEMA, new MigrationCredentials.Credentials("mig_" + SCHEMA, PWD));
      try (Connection svc = as(db, "svc_rls_demo")) {
        CREATED.put(db + ":20271", createPartitions(svc, 20271));
        CREATED.put(db + ":20271:again", createPartitions(svc, 20271));
        CREATED.put(db + ":20272", createPartitions(svc, 20272));
        insert(svc, TENANT_A, TENANT_A, 20271, "Việc A quý 1");
        insert(svc, TENANT_A, TENANT_A, 20272, "Việc A quý 2");
        insert(svc, TENANT_B, TENANT_B, 20271, "Việc B quý 1");
      }
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  private static String url(String db) {
    return POSTGRES.getJdbcUrl().replace("/td_bridge", "/" + db);
  }

  private static Connection admin(String db) throws SQLException {
    return DriverManager.getConnection(url(db), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection as(String db, String role) throws SQLException {
    return DriverManager.getConnection(url(db), role, PWD);
  }

  private static int createPartitions(Connection c, int bucket) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT rls_demo.fn_create_period_partitions('rls_demo.sample_task', ?)")) {
      ps.setInt(1, bucket);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /** Mở giao dịch tường minh, đặt ngữ cảnh tenant bằng set_config(..., true) như TL-03 quy định. */
  private static void inTenant(Connection c, String tenant, SqlWork work) throws SQLException {
    c.setAutoCommit(false);
    try {
      if (tenant != null) {
        try (PreparedStatement ps =
            c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
          ps.setString(1, tenant);
          ps.execute();
        }
      }
      work.run(c);
      c.commit();
    } catch (SQLException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  private static void insert(Connection c, UUID context, UUID rowTenant, int bucket, String title)
      throws SQLException {
    inTenant(
        c,
        context == null ? null : context.toString(),
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO rls_demo.sample_task (id, tenant_id, period_bucket, title)"
                      + " VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, rowTenant);
            ps.setInt(3, bucket);
            ps.setString(4, title);
            ps.executeUpdate();
          }
        });
  }

  private static long count(Connection c, String tenant, String sql) throws SQLException {
    long[] result = new long[1];
    inTenant(
        c,
        tenant,
        conn -> {
          try (Statement st = conn.createStatement();
              ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            result[0] = rs.getLong(1);
          }
        });
    return result[0];
  }

  private static String sqlState(Throwable t) {
    return t instanceof SQLException e ? e.getSQLState() : null;
  }

  @ParameterizedTest
  @ValueSource(strings = {"td_bridge", "td_pool"})
  void taoPhanVungTheoMoHinhVaGoiLaiKhongLoi(String db) throws SQLException {
    int expected = db.equals("td_pool") ? 17 : 1;
    assertThat(CREATED.get(db + ":20271")).isEqualTo(expected);
    assertThat(CREATED.get(db + ":20272")).isEqualTo(expected);
    assertThat(CREATED.get(db + ":20271:again")).isZero();
    try (Connection c = admin(db);
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'rls_demo' AND c.relispartition AND c.relkind = 'r'")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(db.equals("td_pool") ? 32 : 2);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"td_bridge", "td_pool"})
  void tenantChiThayDongCuaMinh(String db) throws SQLException {
    try (Connection svc = as(db, "svc_rls_demo")) {
      assertThat(count(svc, TENANT_A.toString(), "SELECT count(*) FROM rls_demo.sample_task"))
          .isEqualTo(2);
      assertThat(count(svc, TENANT_B.toString(), "SELECT count(*) FROM rls_demo.sample_task"))
          .isEqualTo(1);
      // Cập nhật dòng của B trong ngữ cảnh A không chạm dòng nào.
      long[] updated = new long[1];
      inTenant(
          svc,
          TENANT_A.toString(),
          c -> {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE rls_demo.sample_task SET title = 'x' WHERE tenant_id = ?")) {
              ps.setObject(1, TENANT_B);
              updated[0] = ps.executeUpdate();
            }
          });
      assertThat(updated[0]).isZero();
    }
    try (Connection ro = as(db, "ro_rls_demo")) {
      assertThat(count(ro, TENANT_A.toString(), "SELECT count(*) FROM rls_demo.sample_task"))
          .isEqualTo(2);
      assertThatThrownBy(() -> insert(ro, TENANT_A, TENANT_A, 20271, "ro ghi"))
          .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"td_bridge", "td_pool"})
  void khongGhiDuocDongMangTenantKhac(String db) throws SQLException {
    try (Connection svc = as(db, "svc_rls_demo")) {
      assertThatThrownBy(() -> insert(svc, TENANT_A, TENANT_B, 20271, "Ghi hộ B"))
          .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"))
          .hasMessageContaining("row-level security");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"td_bridge", "td_pool"})
  void thieuNguCanhTenantThiDocRongGhiLoi(String db) throws SQLException {
    try (Connection svc = as(db, "svc_rls_demo")) {
      assertThat(count(svc, null, "SELECT count(*) FROM rls_demo.sample_task")).isZero();
      assertThat(count(svc, "", "SELECT count(*) FROM rls_demo.sample_task")).isZero();
      assertThatThrownBy(() -> insert(svc, null, TENANT_A, 20271, "Không ngữ cảnh"))
          .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
      // Tự động cam kết (không giao dịch tường minh) cũng không có ngữ cảnh.
      try (Statement st = svc.createStatement();
          ResultSet rs = st.executeQuery("SELECT count(*) FROM rls_demo.sample_task")) {
        rs.next();
        assertThat(rs.getLong(1)).isZero();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"td_bridge", "td_pool"})
  void khongTruyCapTrucTiepPhanVungCon(String db) throws SQLException {
    List<String> partitions = new ArrayList<>(List.of("rls_demo.sample_task_p20271"));
    if (db.equals("td_pool")) {
      partitions.add("rls_demo.sample_task_p20271_h00");
      partitions.add("rls_demo.sample_task_p20272_h15");
    }
    try (Connection svc = as(db, "svc_rls_demo")) {
      for (String p : partitions) {
        assertThatThrownBy(() -> count(svc, TENANT_A.toString(), "SELECT count(*) FROM " + p))
            .as(p)
            .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
      }
    }
    // Lớp thứ hai: kể cả vai trò có quyền đọc phân vùng con (ví dụ cấp nhầm), RLS vẫn lọc.
    try (Connection admin = admin(db);
        Statement st = admin.createStatement()) {
      st.execute("GRANT SELECT ON " + partitions.get(0) + " TO ro_rls_demo");
    }
    try (Connection ro = as(db, "ro_rls_demo")) {
      assertThat(count(ro, TENANT_A.toString(), "SELECT count(*) FROM " + partitions.get(0)))
          .isEqualTo(1);
      assertThat(count(ro, null, "SELECT count(*) FROM " + partitions.get(0))).isZero();
    } finally {
      try (Connection admin = admin(db);
          Statement st = admin.createStatement()) {
        st.execute("REVOKE SELECT ON " + partitions.get(0) + " FROM ro_rls_demo");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"td_bridge", "td_pool"})
  void chuSoHuuCungChiuRlsVaKyChuaCoPhanVungThiLoi(String db) throws SQLException {
    try (Connection mig = as(db, "mig_rls_demo")) {
      // FORCE ROW LEVEL SECURITY: chủ sở hữu không có chính sách nên không thấy dòng nào.
      assertThat(count(mig, TENANT_A.toString(), "SELECT count(*) FROM rls_demo.sample_task"))
          .isZero();
    }
    try (Connection svc = as(db, "svc_rls_demo")) {
      assertThatThrownBy(() -> insert(svc, TENANT_A, TENANT_A, 20273, "Kỳ chưa mở"))
          .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"))
          .hasMessageContaining("no partition");
      assertThatThrownBy(() -> createPartitions(svc, 20275))
          .hasMessageContaining("period_bucket 20275");
      assertThatThrownBy(
              () -> {
                try (Statement st = svc.createStatement()) {
                  st.execute("SELECT rls_demo.fn_enable_tenant_rls('rls_demo.sample_task')");
                }
              })
          .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"td_bridge", "td_pool"})
  void truyVanCoPeriodBucketChiQuetMotPhanVung(String db) throws Exception {
    String sql =
        db.equals("td_pool")
            ? "EXPLAIN (FORMAT JSON) SELECT * FROM rls_demo.sample_task WHERE period_bucket = 20271"
                + " AND tenant_id = '"
                + TENANT_A
                + "'"
            : "EXPLAIN (FORMAT JSON) SELECT * FROM rls_demo.sample_task WHERE period_bucket = 20271";
    List<String> relations = new ArrayList<>();
    try (Connection svc = as(db, "svc_rls_demo")) {
      inTenant(
          svc,
          TENANT_A.toString(),
          c -> {
            try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
              rs.next();
              try {
                collectRelations(new ObjectMapper().readTree(rs.getString(1)), relations);
              } catch (java.io.IOException e) {
                throw new SQLException(e);
              }
            }
          });
    }
    assertThat(relations).hasSize(1);
    assertThat(relations.get(0)).startsWith("sample_task_p20271");
  }

  private static void collectRelations(JsonNode node, List<String> out) {
    if (node.isObject() && node.has("Relation Name")) {
      out.add(node.get("Relation Name").asText());
    }
    node.forEach(child -> collectRelations(child, out));
  }
}
