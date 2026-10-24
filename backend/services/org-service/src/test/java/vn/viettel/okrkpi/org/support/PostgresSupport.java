package vn.viettel.okrkpi.org.support;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Một PostgreSQL 17 dùng chung cho mọi kiểm thử tích hợp (Testcontainers). Khởi tạo cụm giống TL-03: vai trò
 * {@code mig_org}, {@code svc_org} (NOINHERIT, NOBYPASSRLS), {@code ro_org}, {@code cdc_org}, schema {@code org} do
 * {@code mig_org} sở hữu; Flyway chạy bằng {@code mig_org}. Mật khẩu sinh ngẫu nhiên lúc chạy.
 */
public final class PostgresSupport {

  public static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17")
      .withDatabaseName("td_test")
      .withUrlParam("reWriteBatchedInserts", "true")
      .withCommand("postgres", "-c", "max_connections=400", "-c", "fsync=off");

  public static final String SVC_PASSWORD = random();
  public static final String MIG_PASSWORD = random();
  public static final String RO_PASSWORD = random();

  private static boolean started;
  private static String jdbcUrl;
  private static String adminUser;
  private static String adminPass;

  private PostgresSupport() {
  }

  public static synchronized void start() {
    if (started) {
      return;
    }
    String extUrl = System.getenv("TEST_PG_JDBC_URL");
    if (extUrl != null && !extUrl.isBlank()) {
      jdbcUrl = extUrl;
      adminUser = System.getenv().getOrDefault("TEST_PG_USER", "mission");
      adminPass = System.getenv().getOrDefault("TEST_PG_PASSWORD", "");
    } else {
      PG.start();
      jdbcUrl = PG.getJdbcUrl();
      adminUser = PG.getUsername();
      adminPass = PG.getPassword();
    }
    try (Connection c = admin(); Statement s = c.createStatement()) {
      s.execute("DROP SCHEMA IF EXISTS org CASCADE");
      s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mig_org') THEN "
          + "CREATE ROLE mig_org LOGIN NOINHERIT; END IF; END $$");
      s.execute("ALTER ROLE mig_org WITH LOGIN NOINHERIT PASSWORD '" + MIG_PASSWORD + "'");
      s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_org') THEN "
          + "CREATE ROLE svc_org LOGIN NOINHERIT NOBYPASSRLS; END IF; END $$");
      s.execute("ALTER ROLE svc_org WITH LOGIN NOINHERIT NOBYPASSRLS PASSWORD '" + SVC_PASSWORD + "'");
      s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ro_org') THEN "
          + "CREATE ROLE ro_org LOGIN NOINHERIT NOBYPASSRLS; END IF; END $$");
      s.execute("ALTER ROLE ro_org WITH LOGIN NOINHERIT NOBYPASSRLS PASSWORD '" + RO_PASSWORD + "'");
      s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cdc_org') THEN "
          + "CREATE ROLE cdc_org LOGIN REPLICATION NOINHERIT; END IF; END $$");
      s.execute("CREATE SCHEMA org AUTHORIZATION mig_org");
      s.execute("REVOKE ALL ON SCHEMA org FROM PUBLIC");
      s.execute("CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    Flyway.configure()
        .dataSource(jdbcUrl, "mig_org", MIG_PASSWORD)
        .schemas("org").defaultSchema("org").createSchemas(false)
        .locations("classpath:db/migration/org")
        .load()
        .migrate();
    started = true;
  }

  public static String jdbcUrl() {
    start();
    return jdbcUrl;
  }

  public static String adminUsername() {
    start();
    return adminUser;
  }

  public static String adminPassword() {
    start();
    return adminPass;
  }

  public static Connection admin() throws SQLException {
    return DriverManager.getConnection(jdbcUrl, adminUser, adminPass);
  }

  public static Connection as(String role, String password) throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), role, password);
  }

  private static String random() {
    byte[] b = new byte[18];
    new SecureRandom().nextBytes(b);
    return HexFormat.of().formatHex(b);
  }
}
