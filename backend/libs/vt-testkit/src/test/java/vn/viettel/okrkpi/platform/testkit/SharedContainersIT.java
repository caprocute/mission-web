package vn.viettel.okrkpi.platform.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Kiểm thử tích hợp với Testcontainers (tự động bỏ qua khi máy chạy không có Docker daemon nhờ
 * {@code disabledWithoutDocker = true}).
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedContainersIT {

  @Test
  void shouldBootstrapPostgresSchemasAndEnforceRoleConstraints() throws SQLException {
    PostgreSQLContainer<?> pg = PostgresContainerSupport.sharedPostgres();
    assertThat(pg.isRunning()).isTrue();

    try (Connection adminConn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Statement stmt = adminConn.createStatement()) {
      try (ResultSet rs =
          stmt.executeQuery("SELECT rolbypassrls FROM pg_roles WHERE rolname = 'svc_task'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getBoolean(1)).isFalse();
      }
    }

    // svc_task không được phép CREATE TABLE (ứng dụng không có quyền DDL)
    try (Connection svcTaskConn =
            DriverManager.getConnection(
                pg.getJdbcUrl(),
                PostgresContainerSupport.svcUsername("task"),
                PostgresContainerSupport.DEFAULT_PASSWORD);
        Statement stmt = svcTaskConn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SHOW statement_timeout")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("5s");
      }

      assertThatThrownBy(() -> stmt.execute("CREATE TABLE task.unauthorized_ddl (id int)"))
          .isInstanceOf(SQLException.class);
    }
  }
}
