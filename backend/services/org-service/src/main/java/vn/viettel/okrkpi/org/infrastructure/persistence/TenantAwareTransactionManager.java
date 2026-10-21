package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;

/**
 * Đặt {@code app.tenant_id} bằng {@code set_config(..., true)} (tương đương {@code SET LOCAL}) ngay khi giao dịch
 * bắt đầu, kể cả giao dịch chỉ đọc; tự huỷ khi COMMIT hoặc ROLLBACK nên an toàn với PgBouncer chế độ transaction.
 */
public class TenantAwareTransactionManager extends JdbcTransactionManager {

  public TenantAwareTransactionManager(DataSource dataSource) {
    super(dataSource);
    setEnforceReadOnly(true);
  }

  @Override
  protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
      throws SQLException {
    super.prepareTransactionalConnection(con, definition);
    try (PreparedStatement ps = con.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      ps.setString(1, TenantContextHolder.require().toString());
      ps.execute();
    }
  }
}
