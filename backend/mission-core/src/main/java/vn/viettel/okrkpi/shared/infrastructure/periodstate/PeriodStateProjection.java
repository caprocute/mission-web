package vn.viettel.okrkpi.shared.infrastructure.periodstate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.vtokr.config.v1.KyDaHuyKhoa;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/**
 * Bản sao trạng thái kỳ {@code <schema>.period_state} của một module có dữ liệu thực hiện theo kỳ
 * ({@code okr}, {@code task}, {@code product}), nguồn của trigger {@code fn_guard_period_write}
 * (CORE-04-01, TL-03 "Bảo vệ dữ liệu kỳ đã khoá ở tầng CSDL"): EVT-09 → {@code DA_KHOA}, EVT-22 →
 * {@code MO_LAI_HAU_KIEM}, EVT-26 → {@code MO}.
 *
 * <p>Được gọi qua {@code InboxMessageProcessor} của chính schema nên khử trùng lặp và ghi nằm cùng
 * giao dịch, {@code app.tenant_id} đã đặt. Sự kiện đến muộn (thời điểm cũ hơn dòng đang có) bị bỏ
 * qua. Sự kiện "bắt đầu khoá" của saga chưa có trong danh mục (chỉ có dấu {@code
 * period.drain.marker}), nên {@code DANG_KHOA} chưa được chiếu (wiki trang 14 mục 51).
 */
public class PeriodStateProjection {

  private final JdbcTemplate jdbc;
  private final String upsertSql;

  public PeriodStateProjection(JdbcTemplate jdbc, String schema) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    if (!schema.matches("[a-z_]+")) {
      throw new IllegalArgumentException("Tên schema không hợp lệ: " + schema);
    }
    this.upsertSql =
        "INSERT INTO "
            + schema
            + ".period_state (tenant_id, period_id, status, source_event_id, updated_at)"
            + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id, period_id) DO UPDATE"
            + " SET status = excluded.status, source_event_id = excluded.source_event_id,"
            + " updated_at = excluded.updated_at"
            + " WHERE period_state.updated_at <= excluded.updated_at";
  }

  public void onLocked(KyDaKhoa event, InboxEnvelope<?> envelope) {
    apply(envelope, event.getPeriodId(), "DA_KHOA", event.getLockedAt());
  }

  public void onReopened(KyDaMoLaiHauKiem event, InboxEnvelope<?> envelope) {
    apply(envelope, event.getPeriodId(), "MO_LAI_HAU_KIEM", event.getExecutedAt());
  }

  public void onUnlocked(KyDaHuyKhoa event, InboxEnvelope<?> envelope) {
    apply(envelope, event.getPeriodId(), "MO", event.getUnlockedAt());
  }

  private void apply(InboxEnvelope<?> envelope, UUID periodId, String status, Instant at) {
    Instant when =
        at != null ? at : envelope.occurredAt() != null ? envelope.occurredAt() : Instant.now();
    jdbc.update(
        upsertSql,
        envelope.tenantId().value(),
        periodId,
        status,
        envelope.eventId(),
        Timestamp.from(when));
  }
}
