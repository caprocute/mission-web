package vn.viettel.okrkpi.archfixture.bad;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Vi phạm NEN-010: tự lấy kết nối, bỏ qua trình quản lý giao dịch đặt app.tenant_id. */
public class RawConnectionDao {

  private final DataSource dataSource;

  public RawConnectionDao(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void run() throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.createStatement().execute("select 1");
    }
  }
}
