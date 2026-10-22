package vn.viettel.okrkpi.esign.infrastructure.sign;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Liệt kê yêu cầu đang chờ xác nhận của mọi tenant qua hàm {@code esign.fn_yeu_cau_cho_xac_nhan}
 * (chỉ mã và tenant); worker xử lý từng yêu cầu trong ngữ cảnh tenant của nó.
 */
public class PendingRequestScanner {

  private final JdbcClient jdbc;

  public PendingRequestScanner(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<Pending> scan(int limit) {
    return jdbc.sql("SELECT id, tenant_id FROM esign.fn_yeu_cau_cho_xac_nhan(?)")
        .param(limit)
        .query(
            (rs, i) ->
                new Pending(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)))
        .list();
  }

  /** Một yêu cầu đang chờ. */
  public record Pending(UUID id, UUID tenantId) {}
}
