package vn.viettel.okrkpi.esign.infrastructure.sign;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Liệt kê tài liệu chờ ký của các lô đã được xác nhận ở mọi tenant qua hàm {@code
 * esign.fn_muc_ky_lo_cho} (DV-10-06), và đếm độ dài hàng đợi cho metric co giãn worker.
 */
public class BatchItemScanner {

  private final JdbcClient jdbc;

  public BatchItemScanner(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Pending> scan(int limit) {
    return jdbc.sql("SELECT id, sign_request_id, tenant_id FROM esign.fn_muc_ky_lo_cho(?)")
        .param(limit)
        .query(
            (rs, i) ->
                new Pending(
                    rs.getObject("id", UUID.class),
                    rs.getObject("sign_request_id", UUID.class),
                    rs.getObject("tenant_id", UUID.class)))
        .list();
  }

  /** Số tài liệu đang chờ ký của mọi lô đã được xác nhận. */
  public long queueLength() {
    return jdbc.sql("SELECT esign.fn_dem_muc_ky_lo_cho()").query(Long.class).single();
  }

  /** Một tài liệu chờ ký. */
  public record Pending(UUID itemId, UUID requestId, UUID tenantId) {}
}
