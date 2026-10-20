package vn.viettel.okrkpi.platform.db.migration.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;

/**
 * Ma trận quyền sau khi bootstrap CSDL dữ liệu tenant (NEN-031, TL-03 "Không truy cập chéo và quyền
 * tối thiểu"). Bảng mẫu được tạo bằng chính vai trò {@code mig_<schema>} để chứng minh quyền mặc
 * định tự cấp cho đối tượng mới.
 */
class DatabaseBootstrapIT {

  private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("td_thu");

  private static final String PWD = "mat-khau-thu-nghiem";

  private static Map<String, String> passwords() {
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.TENANT_DATA)) {
      env.put(role.toUpperCase(java.util.Locale.ROOT) + "_PASSWORD", PWD);
    }
    return env;
  }

  @BeforeAll
  static void prepare() throws SQLException {
    POSTGRES.start();
    DatabaseBootstrap bootstrap = new DatabaseBootstrap(RoleLimits.load(), passwords());
    try (Connection admin = admin()) {
      bootstrap.apply(admin, DatabaseLayout.TENANT_DATA);
      // Chạy lần hai: idempotent.
      bootstrap.apply(admin, DatabaseLayout.TENANT_DATA);
    }
    // Bảng mẫu do mig_<schema> tạo sau bootstrap.
    exec("mig_task", "CREATE TABLE task.task_probe (id uuid PRIMARY KEY, title varchar(50))");
    exec("mig_task", "CREATE TABLE task.outbox_event (id uuid PRIMARY KEY)");
    exec("mig_appraisal", "CREATE TABLE appraisal.score_probe (id uuid PRIMARY KEY)");
    exec("mig_okr", "CREATE TABLE okr.objective_probe (id uuid PRIMARY KEY)");
    exec(
        "mig_keyring",
        "CREATE TABLE keyring.dek (id uuid PRIMARY KEY, status varchar(16) NOT NULL)");
    exec("mig_org_replica", "CREATE TABLE org_replica.unit_probe (id uuid PRIMARY KEY)");
    exec("mig_modulith", "CREATE TABLE modulith.event_publication (id uuid PRIMARY KEY)");
    // Chạy lại sau migration để cấp SELECT outbox_event cho cdc_task.
    try (Connection admin = admin()) {
      bootstrap.apply(admin, DatabaseLayout.TENANT_DATA);
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection as(String role) throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), role, PWD);
  }

  private static void exec(String role, String sql) throws SQLException {
    try (Connection c = as(role);
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static boolean allowed(String role, String sql) {
    try {
      exec(role, sql);
      return true;
    } catch (SQLException e) {
      assertThat(e.getSQLState()).as("lỗi của %s khi chạy %s", role, sql).isEqualTo("42501");
      return false;
    }
  }

  @Test
  void svcTaskChiCoDmlTrenSchemaCuaMinh() {
    assertThat(allowed("svc_task", "INSERT INTO task.task_probe VALUES (gen_random_uuid(), 'a')"))
        .isTrue();
    assertThat(allowed("svc_task", "SELECT * FROM task.task_probe")).isTrue();
    assertThat(allowed("svc_task", "CREATE TABLE task.tu_tao (id int)")).isFalse();
    assertThat(allowed("svc_task", "SELECT * FROM appraisal.score_probe")).isFalse();
    assertThat(allowed("svc_task", "SELECT * FROM okr.objective_probe")).isFalse();
  }

  @Test
  void roChiDoc() {
    assertThat(allowed("ro_okr", "SELECT * FROM okr.objective_probe")).isTrue();
    assertThat(allowed("ro_okr", "INSERT INTO okr.objective_probe VALUES (gen_random_uuid())"))
        .isFalse();
  }

  @Test
  void cdcChiDocOutbox() {
    assertThat(allowed("cdc_task", "SELECT * FROM task.outbox_event")).isTrue();
    assertThat(allowed("cdc_task", "SELECT * FROM task.task_probe")).isFalse();
  }

  @Test
  void keyringChiSelectInsertTruSvcKeyring() {
    assertThat(
            allowed(
                "svc_task",
                "INSERT INTO keyring.dek VALUES ('00000000-0000-7000-8000-000000000001', 'ACTIVE')"))
        .isTrue();
    assertThat(allowed("svc_task", "SELECT * FROM keyring.dek")).isTrue();
    assertThat(allowed("svc_task", "UPDATE keyring.dek SET status = 'RETIRED'")).isFalse();
    assertThat(allowed("svc_evidence", "SELECT * FROM keyring.dek")).isTrue();
    assertThat(allowed("svc_keyring", "UPDATE keyring.dek SET status = 'DECRYPT_ONLY'")).isTrue();
  }

  @Test
  void orgReplicaChiSvcOrgReplicaGhi() {
    assertThat(allowed("svc_task", "SELECT * FROM org_replica.unit_probe")).isTrue();
    assertThat(allowed("svc_task", "INSERT INTO org_replica.unit_probe VALUES (gen_random_uuid())"))
        .isFalse();
    assertThat(
            allowed(
                "svc_org_replica", "INSERT INTO org_replica.unit_probe VALUES (gen_random_uuid())"))
        .isTrue();
    // org-service (SVC-04) không thuộc mission-core nên không đọc org_replica.
    assertThat(allowed("svc_org", "SELECT * FROM org_replica.unit_probe")).isFalse();
  }

  @Test
  void modulithChoMoiMoDunMissionCore() {
    assertThat(
            allowed("svc_okr", "INSERT INTO modulith.event_publication VALUES (gen_random_uuid())"))
        .isTrue();
    assertThat(allowed("svc_okr", "DELETE FROM modulith.event_publication")).isTrue();
    assertThat(allowed("svc_scoring", "SELECT * FROM modulith.event_publication")).isFalse();
  }

  @Test
  void khongVaiTroNaoVuotRlsHaySieuNguoiDung() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT rolname FROM pg_roles WHERE rolname ~ '^(mig|svc|ro|cdc)_'"
                    + " AND (rolbypassrls OR rolsuper OR rolcreaterole OR rolcreatedb"
                    + " OR rolinherit OR (rolreplication AND rolname !~ '^cdc_'))")) {
      List<String> bad = new ArrayList<>();
      while (rs.next()) {
        bad.add(rs.getString(1));
      }
      assertThat(bad).isEmpty();
    }
  }

  @Test
  void thamSoPhienCuaVaiTroUngDung() throws SQLException {
    try (Connection c = as("svc_task");
        Statement st = c.createStatement()) {
      assertThat(show(st, "statement_timeout")).isEqualTo("5s");
      assertThat(show(st, "idle_in_transaction_session_timeout")).isEqualTo("10s");
      assertThat(show(st, "search_path")).isEqualTo("task");
    }
  }

  @Test
  void schemaThuocMigVaPublicKhongCoQuyen() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT n.nspname, r.rolname FROM pg_namespace n JOIN pg_roles r ON r.oid ="
                    + " n.nspowner WHERE n.nspname = ANY (ARRAY["
                    + quoted(DatabaseLayout.TENANT_DATA.schemas())
                    + "])")) {
      int count = 0;
      while (rs.next()) {
        count++;
        assertThat(rs.getString(2)).isEqualTo("mig_" + rs.getString(1));
      }
      assertThat(count).isEqualTo(15);
    }
    assertThat(allowed("svc_task", "CREATE TABLE public.lau (id int)")).isFalse();
  }

  @Test
  void vaiTroCuaBoTriKhacKhongKetNoiDuoc() throws SQLException {
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      st.execute("CREATE DATABASE cell_shared");
    }
    String url = POSTGRES.getJdbcUrl().replace("/td_thu", "/cell_shared");
    Map<String, String> env = new HashMap<>();
    env.put("SVC_NOTIFY_PASSWORD", PWD);
    try (Connection c =
        DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(c, DatabaseLayout.CELL_SHARED);
    }
    try (Connection c = DriverManager.getConnection(url, "svc_notify", PWD)) {
      assertThat(c.isValid(1)).isTrue();
    }
    // svc_task không thuộc bố trí cell_shared: CONNECT của PUBLIC đã bị thu hồi.
    assertThatThrownBy(() -> DriverManager.getConnection(url, "svc_task", PWD).close())
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void inMaTranQuyen() throws SQLException, IOException {
    List<String> lines = new ArrayList<>();
    lines.add("| Vai trò | Schema | USAGE | SELECT | INSERT | UPDATE | DELETE | CREATE |");
    lines.add("|---|---|---|---|---|---|---|---|");
    Map<String, String> probe =
        Map.of(
            "task", "task.task_probe",
            "appraisal", "appraisal.score_probe",
            "okr", "okr.objective_probe",
            "keyring", "keyring.dek",
            "org_replica", "org_replica.unit_probe",
            "modulith", "modulith.event_publication");
    try (Connection c = admin();
        Statement st = c.createStatement()) {
      for (String role :
          List.of(
              "mig_task",
              "svc_task",
              "ro_task",
              "cdc_task",
              "svc_okr",
              "svc_org_replica",
              "svc_keyring",
              "svc_org")) {
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(probe).entrySet()) {
          String q =
              "SELECT has_schema_privilege('%1$s','%2$s','USAGE'),".formatted(role, e.getKey())
                  + " has_table_privilege('%1$s','%2$s','SELECT'), has_table_privilege('%1$s','%2$s','INSERT'),"
                      .formatted(role, e.getValue())
                  + " has_table_privilege('%1$s','%2$s','UPDATE'), has_table_privilege('%1$s','%2$s','DELETE'),"
                      .formatted(role, e.getValue())
                  + " has_schema_privilege('%1$s','%2$s','CREATE')".formatted(role, e.getKey());
          try (ResultSet rs = st.executeQuery(q)) {
            rs.next();
            StringBuilder sb = new StringBuilder("| " + role + " | " + e.getKey() + " |");
            for (int i = 1; i <= 6; i++) {
              sb.append(rs.getBoolean(i) ? " có |" : " – |");
            }
            lines.add(sb.toString());
          }
        }
      }
    }
    Path out = Path.of("target", "role-matrix.md");
    Files.createDirectories(out.getParent());
    Files.write(out, lines, StandardCharsets.UTF_8);
    assertThat(lines).hasSizeGreaterThan(40);
  }

  private static String show(Statement st, String name) throws SQLException {
    try (ResultSet rs = st.executeQuery("SHOW " + name)) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static String quoted(List<String> values) {
    return String.join(",", values.stream().map(v -> "'" + v + "'").toList());
  }
}
