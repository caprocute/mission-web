package vn.viettel.okrkpi.shared.observability;

import java.io.Serial;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import vn.viettel.okrkpi.shared.tenant.TenantSql;

/**
 * Gắn comment sqlcommenter ({@link SqlCommenter}) vào mọi câu SQL Hibernate sinh ra, và chặn câu
 * đặt {@code app.tenant_id} mức phiên (NEN-010, {@link TenantSql#requireTransactionLocal}).
 */
public class SqlCommenterStatementInspector implements StatementInspector {

  @Serial private static final long serialVersionUID = 1L;

  @Override
  public String inspect(String sql) {
    return SqlCommenter.comment(TenantSql.requireTransactionLocal(sql));
  }
}
