package vn.viettel.okrkpi.platform.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;

/**
 * MigrationRunner trên hai CSDL: CSDL thứ hai có migration lỗi thì CSDL thứ nhất vẫn hoàn tất, mã
 * thoát khác 0 (TL-03 L4596). Chạy bằng tài khoản {@code mig_runner_ok} sở hữu schema, đúng như Job
 * di trú thật.
 */
class MigrationRunnerIT {

  private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("td_mot");

  private static final String MIG_PASSWORD = "mat-khau-thu-nghiem";

  @BeforeAll
  static void prepare() throws SQLException {
    POSTGRES.start();
    try (Connection admin = adminConnection("td_mot");
        Statement st = admin.createStatement()) {
      st.execute("CREATE DATABASE td_hai");
    }
    // Vai trò và schema tạo bằng bootstrap như môi trường thật (vai trò dùng chung toàn cụm).
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles("runner_ok")) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", MIG_PASSWORD);
    }
    DatabaseBootstrap bootstrap = new DatabaseBootstrap(RoleLimits.load(), env);
    for (String db : List.of("td_mot", "td_hai")) {
      try (Connection admin = adminConnection(db)) {
        bootstrap.applySchemas(admin, List.of("runner_ok"));
      }
    }
    // CSDL thứ hai đã có sẵn bảng trùng tên nên migration V1 thất bại.
    try (Connection admin = adminConnection("td_hai");
        Statement st = admin.createStatement()) {
      st.execute("CREATE TABLE runner_ok.runner_probe (x int)");
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  private static String url(String db) {
    return POSTGRES.getJdbcUrl().replace("/td_mot", "/" + db);
  }

  private static Connection adminConnection(String db) throws SQLException {
    return DriverManager.getConnection(url(db), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @Test
  void csdlLoiKhongChanCsdlKhacVaMaThoatKhacKhong() throws SQLException {
    MigrationCredentials credentials =
        (jdbcUrl, schema) ->
            new MigrationCredentials.Credentials(SchemaNames.migrationRole(schema), MIG_PASSWORD);
    MigrationRunner runner = new MigrationRunner(credentials, MigrationOptions.defaults());

    // CSDL lỗi đứng trước để chứng minh CSDL sau vẫn chạy.
    List<MigrationRunner.DatabaseOutcome> outcomes =
        runner.run(List.of(url("td_hai"), url("td_mot")), List.of("runner_ok"), 5);

    assertThat(outcomes).hasSize(2);
    assertThat(outcomes.get(0).success()).isFalse();
    assertThat(outcomes.get(0).error()).contains("runner_ok");
    assertThat(outcomes.get(1).success()).isTrue();
    // V0_1..V0_3 (db/common), V0_20..V0_23 (db/common-business) + V1 của schema.
    assertThat(outcomes.get(1).appliedMigrations()).isEqualTo(8);
    assertThat(MigrationRunner.exitCode(outcomes)).isEqualTo(1);

    try (Connection c = adminConnection("td_mot");
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select tableowner from pg_tables"
                    + " where schemaname = 'runner_ok' and tablename = 'runner_probe'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("mig_runner_ok");
    }

    // Chạy lại trên CSDL đã xong: không áp thêm gì, mã thoát 0.
    List<MigrationRunner.DatabaseOutcome> again =
        runner.run(List.of(url("td_mot")), List.of("runner_ok"), 1);
    assertThat(again.get(0).appliedMigrations()).isZero();
    assertThat(MigrationRunner.exitCode(again)).isZero();
  }
}
