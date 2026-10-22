package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Quét tác vụ đang chờ của mọi tenant qua hàm {@code docgen.fn_tac_vu_cho_sinh} (chỉ mã, tenant).
 */
public class PendingJobScanner {

  private final JdbcClient jdbc;

  public PendingJobScanner(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Tác vụ đang chờ, cùng tác vụ đang sinh mà lượt thuê đã quá {@code lease}. */
  public List<Pending> scan(int limit, Duration lease) {
    return jdbc.sql("SELECT id, tenant_id FROM docgen.fn_tac_vu_cho_sinh(?, ?)")
        .param(limit)
        .param(lease.toSeconds())
        .query(
            (rs, i) ->
                new Pending(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)))
        .list();
  }

  /** Độ dài hàng đợi cho KEDA. */
  public long queueLength() {
    return jdbc.sql("SELECT docgen.fn_do_dai_hang_doi()").query(Long.class).single();
  }

  /** Một tác vụ đang chờ. */
  public record Pending(UUID id, UUID tenantId) {}
}
