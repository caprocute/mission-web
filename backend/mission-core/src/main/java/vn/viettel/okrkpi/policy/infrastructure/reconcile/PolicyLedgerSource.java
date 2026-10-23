package vn.viettel.okrkpi.policy.infrastructure.reconcile;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.avro.Schema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerSource;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Nguồn R2 của MOD-05 (DL-12): {@code sys.audit.v1} ghi từ {@code policy.outbox_event} chỉ đối soát
 * băm vì không mang trường dữ liệu nguồn. EVT-13 {@code policy.pack.published.v1} chưa được
 * mission-core phát nên chưa có trong danh mục; khi có nơi phát thì bổ sung ở đây.
 */
public class PolicyLedgerSource implements LedgerSource {

  static final String AUDIT = EventTopics.SYS_AUDIT_V1;

  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager tm;

  public PolicyLedgerSource(JdbcTemplate jdbc, PlatformTransactionManager tm) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tm = Objects.requireNonNull(tm, "tm");
  }

  @Override
  public String schema() {
    return "policy";
  }

  @Override
  public JdbcTemplate jdbc() {
    return jdbc;
  }

  @Override
  public PlatformTransactionManager tm() {
    return tm;
  }

  @Override
  public Map<String, Schema> topics() {
    return Map.of(AUDIT, AuditEvent.getClassSchema());
  }

  @Override
  public List<String> verify(UUID aggregateId, List<SourceEvent> events) {
    return List.of();
  }
}
