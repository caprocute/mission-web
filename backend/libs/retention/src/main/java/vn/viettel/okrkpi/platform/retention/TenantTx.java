package vn.viettel.okrkpi.platform.retention;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chạy một khối lệnh trong một giao dịch có {@code app.tenant_id} đặt cục bộ (RLS FORCE của TL-03),
 * giống cách {@code DekService} của NEN-015 làm; hợp với PgBouncer chế độ giao dịch.
 */
final class TenantTx {

  @FunctionalInterface
  interface Work<T> {
    T apply(Connection conn) throws SQLException;
  }

  private TenantTx() {}

  static <T> T run(JdbcTemplate jdbc, UUID tenant, Work<T> work) {
    return jdbc.execute(
        (ConnectionCallback<T>)
            conn -> {
              boolean auto = conn.getAutoCommit();
              conn.setAutoCommit(false);
              try {
                try (PreparedStatement ps =
                    conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                  ps.setString(1, tenant.toString());
                  ps.executeQuery().close();
                }
                T out = work.apply(conn);
                conn.commit();
                return out;
              } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
              } finally {
                conn.setAutoCommit(auto);
              }
            });
  }
}
