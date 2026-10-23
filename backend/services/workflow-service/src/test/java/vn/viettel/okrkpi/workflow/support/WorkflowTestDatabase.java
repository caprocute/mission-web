package vn.viettel.okrkpi.workflow.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL 17 dùng chung cho các kiểm thử tích hợp của workflow-service. Ứng dụng kết nối bằng
 * {@code svc_flowable} ({@code NOBYPASSRLS}) để RLS của bảng {@code wf_*} có hiệu lực thật; Flyway
 * chạy bằng tài khoản chủ sở hữu như Job di trú (TL-03 "ứng dụng không chạy DDL").
 */
public final class WorkflowTestDatabase {

  /** Mật khẩu chỉ dùng trong container kiểm thử. */
  public static final String SVC_PASSWORD = "svc-kiem-thu";

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("mission_wf")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=200");

  private WorkflowTestDatabase() {}

  /** Khởi động container (một lần) và tạo vai trò ứng dụng trước khi Flyway chạy. */
  public static synchronized PostgreSQLContainer<?> start() {
    if (!POSTGRES.isRunning()) {
      POSTGRES.start();
      try (Connection c =
              DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          Statement st = c.createStatement()) {
        st.execute(
            "CREATE ROLE svc_flowable LOGIN NOINHERIT NOBYPASSRLS PASSWORD '" + SVC_PASSWORD + "'");
        st.execute("CREATE ROLE ro_flowable LOGIN NOINHERIT NOBYPASSRLS");
        st.execute("CREATE ROLE cdc_flowable LOGIN NOINHERIT");
      } catch (SQLException e) {
        throw new IllegalStateException("Không khởi tạo được vai trò CSDL kiểm thử", e);
      }
    }
    return POSTGRES;
  }

  /** Thuộc tính kết nối: ứng dụng dùng {@code svc_flowable}, Flyway dùng chủ sở hữu. */
  public static void register(DynamicPropertyRegistry registry) {
    start();
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "svc_flowable");
    registry.add("spring.datasource.password", () -> SVC_PASSWORD);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("spring.flyway.schemas", () -> "flowable");
    registry.add("spring.flyway.default-schema", () -> "flowable");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/flowable");
    registry.add("flowable.database-schema", () -> "flowable");
    registry.add("flowable.database-schema-update", () -> "false");
    registry.add("flowable.async-executor-activate", () -> "false");
    registry.add("flowable.check-process-definitions", () -> "false");
    registry.add("management.server.port", () -> "0");
    // Giao dịch mặc định 5 s của vt-platform-starter (TL-01) dễ hết hạn khi cả bộ IT chạy chung
    // trên máy kiểm thử chậm (kích hoạt ràng buộc chép nhiều bảng): chỉ nới ở kiểm thử, không đo
    // hiệu năng ở đây (YC-PT-19); production giữ 5 s.
    registry.add("spring.transaction.default-timeout", () -> "30s");
    registry.add("okrkpi.inbox.listener-auto-startup", () -> "false");
    registry.add("springdoc.api-docs.enabled", () -> "true");
    registry.add(
        "springdoc.packages-to-scan", () -> "vn.viettel.okrkpi.workflow.infrastructure.web");
  }

  /** JDBC bằng tài khoản chủ sở hữu (bỏ qua RLS), chỉ để chuẩn bị và kiểm dữ liệu. */
  public static JdbcTemplate owner() {
    start();
    SingleConnectionDataSource ds =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    return new JdbcTemplate(ds);
  }
}
