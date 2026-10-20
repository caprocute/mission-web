package vn.viettel.okrkpi.shared.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * {@link DataSourceTransactionManager} đặt {@code app.tenant_id} cục bộ giao dịch ngay sau {@code
 * BEGIN} (TL-01 chặng bốn "ConnectionPreparer"). Dùng cho {@code JdbcClient}, {@code JdbcTemplate},
 * jOOQ.
 */
public class TenantDataSourceTransactionManager extends DataSourceTransactionManager {

  private static final long serialVersionUID = 1L;

  private final String setting;

  public TenantDataSourceTransactionManager(DataSource dataSource, TenantProperties properties) {
    super(dataSource);
    this.setting = properties.dbSetting();
  }

  @Override
  protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
      throws SQLException {
    super.prepareTransactionalConnection(con, definition);
    TenantSql.apply(con, setting);
  }
}
