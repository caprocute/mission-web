package vn.viettel.okrkpi.semgrep.good;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** Mẫu mã Java ĐÚNG quy ước NEN-034 lớp 4 (cục bộ giao dịch, không rò qua PgBouncer). */
public final class GoodTenantContext {

  private GoodTenantContext() {}

  public static void goodSetConfigTrue(Connection conn, String tenantId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      ps.setString(1, tenantId);
      ps.execute();
    }
  }

  public static void goodSetLocal(Connection conn, String tenantId) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
    }
  }
}
