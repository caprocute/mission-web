package vn.viettel.okrkpi.semgrep.bad;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Mẫu mã Java SAI để chứng minh luật Semgrep NEN-034 lớp 4 bắt được mọi biến thể đặt tenant mức phiên. */
public final class BadTenantContext {

  private BadTenantContext() {}

  public static void badSetWithoutLocal(Connection conn, String tenantId) throws SQLException {
    try (Statement st = conn.createStatement()) {
      // ruleid: mission-no-session-set-app-param
      st.execute("SET app.tenant_id = '" + tenantId + "'");
    }
  }

  public static void badSetSession(Connection conn, String tenantId) throws SQLException {
    try (Statement st = conn.createStatement()) {
      // ruleid: mission-no-session-set-app-param, mission-no-set-session
      st.execute("SET SESSION app.tenant_id = '" + tenantId + "'");
    }
  }

  public static void badSetConfigFalse(Connection conn, String tenantId) throws SQLException {
    try (Statement st = conn.createStatement()) {
      // ruleid: mission-no-set-config-is-local-false
      st.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
    }
  }
}
