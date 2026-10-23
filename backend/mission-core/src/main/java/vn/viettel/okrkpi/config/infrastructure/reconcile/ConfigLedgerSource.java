package vn.viettel.okrkpi.config.infrastructure.reconcile;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.avro.Schema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerFields;
import vn.viettel.okrkpi.shared.infrastructure.reconcile.LedgerSource;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Nguồn R2 của MOD-08 (DL-12): EVT-09 {@code config.period.locked.v1} so với {@code config.period}
 * và nhật ký chuyển trạng thái {@code config.period_status_log}; {@code sys.audit.v1} ghi từ schema
 * này chỉ đối soát băm vì không mang trường dữ liệu nguồn.
 *
 * <p>Kỳ có thể mở lại hậu kiểm rồi khoá lại, nên {@code lockedAt} và {@code sagaId} so với dòng
 * nhật ký {@code DA_KHOA} cùng thời điểm, không so với {@code period.locked_at} hiện hành.
 */
public class ConfigLedgerSource implements LedgerSource {

  static final String LOCKED = EventTopics.EVT_09_KY_DA_KHOA;
  static final String AUDIT = EventTopics.SYS_AUDIT_V1;

  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager tm;

  public ConfigLedgerSource(JdbcTemplate jdbc, PlatformTransactionManager tm) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tm = Objects.requireNonNull(tm, "tm");
  }

  @Override
  public String schema() {
    return "config";
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
    return Map.of(LOCKED, KyDaKhoa.getClassSchema(), AUDIT, AuditEvent.getClassSchema());
  }

  @Override
  public List<String> verify(UUID aggregateId, List<SourceEvent> events) {
    List<String> out = new ArrayList<>();
    for (SourceEvent e : events) {
      if (LOCKED.equals(e.topic())) {
        locked(aggregateId, LedgerFields.of(e, out));
      }
    }
    return LedgerFields.distinct(out);
  }

  private void locked(UUID periodId, LedgerFields f) {
    if (!periodId.equals(f.uuid("periodId"))) {
      f.missing("periodId");
      return;
    }
    List<Map<String, Object>> period =
        jdbc.queryForList("SELECT period_bucket FROM config.period WHERE id = ?", periodId);
    if (period.isEmpty()) {
      f.missing("periodId");
      return;
    }
    f.sameInt("periodBucket", period.get(0).get("period_bucket"));
    // PostgreSQL làm tròn còn Avro cắt về micro giây, nên chấp nhận lệch một micro giây.
    Timestamp lockedAt = Timestamp.from(f.micros("lockedAt"));
    List<Map<String, Object>> log =
        jdbc.queryForList(
            "SELECT cause_ref FROM config.period_status_log WHERE period_id = ?"
                + " AND to_status = 'DA_KHOA' AND changed_at BETWEEN ?::timestamptz - interval '1 microsecond'"
                + " AND ?::timestamptz + interval '1 microsecond'",
            periodId,
            lockedAt,
            lockedAt);
    f.check("lockedAt", !log.isEmpty());
    UUID saga = f.uuid("sagaId");
    if (!log.isEmpty() && saga != null) {
      f.sameUuid("sagaId", log.get(0).get("cause_ref"));
    }
  }
}
