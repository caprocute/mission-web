package vn.viettel.okrkpi.shared.observability;

import java.io.Serial;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/** Gắn comment sqlcommenter ({@link SqlCommenter}) vào mọi câu SQL Hibernate sinh ra. */
public class SqlCommenterStatementInspector implements StatementInspector {

  @Serial private static final long serialVersionUID = 1L;

  @Override
  public String inspect(String sql) {
    return SqlCommenter.comment(sql);
  }
}
