package vn.viettel.okrkpi.appraisal.infrastructure.listener;

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
 * Consumer cập nhật bản sao trạng thái kỳ {@code <schema>.period_state} mà trigger {@code
 * fn_guard_period_write} tra trước mỗi lần ghi (CORE-04-01, TL-03 "Bảo vệ dữ liệu kỳ đã khoá ở tầng
 * CSDL"): EVT-09 đặt {@code DA_KHOA}, EVT-22 đặt {@code MO_LAI_HAU_KIEM}, EVT-26 trả về {@code MO}.
 * Mỗi schema {@code okr}, {@code task}, {@code product} có một thể hiện chạy qua {@code
 * InboxMessageProcessor} của chính schema đó, nên inbox khử trùng lặp và dòng trạng thái nằm trong
 * cùng giao dịch, bằng vai trò {@code svc_<schema>} và RLS của tenant trong sự kiện.
 *
 * <p>Ba sự kiện đi trên ba topic nên không có thứ tự chung; dòng chỉ được ghi đè bởi sự kiện xảy ra
 * muộn hơn ({@code updated_at} giữ thời điểm của sự kiện), để EVT-22 đến trễ không mở lại một kỳ đã
 * khoá lại. Sự kiện bắt đầu khoá của saga ({@code DANG_KHOA}) chưa có trong danh mục sự kiện nên
 * trạng thái này chỉ do lớp ứng dụng kiểm (wiki trang 14).
 */
public class PeriodStateListener {

  /** Hậu tố tên consumer trong {@code <schema>.inbox_processed}. */
  public static final String CONSUMER_SUFFIX = ".period-state";

  private final String schema;
  private final JdbcTemplate jdbc;
  private final String upsertSql;

  public PeriodStateListener(String schema, JdbcTemplate jdbc) {
    if (!schema.matches("okr|task|product")) {
      throw new IllegalArgumentException("Schema không có period_state: " + schema);
    }
    this.schema = schema;
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.upsertSql =
        "INSERT INTO "
            + schema
            + ".period_state AS ps (tenant_id, period_id, status, source_event_id, updated_at)"
            + " VALUES (?, ?, ?, ?, ?)"
            + " ON CONFLICT (tenant_id, period_id) DO UPDATE"
            + " SET status = EXCLUDED.status, source_event_id = EXCLUDED.source_event_id,"
            + " updated_at = EXCLUDED.updated_at"
            + " WHERE ps.updated_at <= EXCLUDED.updated_at";
  }

  public String schema() {
    return schema;
  }

  public String consumer() {
    return schema + CONSUMER_SUFFIX;
  }

  public void onLocked(KyDaKhoa event, InboxEnvelope<?> envelope) {
    apply(envelope, event.getPeriodId(), "DA_KHOA", event.getLockedAt());
  }

  public void onUnlocked(KyDaHuyKhoa event, InboxEnvelope<?> envelope) {
    apply(envelope, event.getPeriodId(), "MO", event.getUnlockedAt());
  }

  public void onReopened(KyDaMoLaiHauKiem event, InboxEnvelope<?> envelope) {
    apply(envelope, event.getPeriodId(), "MO_LAI_HAU_KIEM", event.getExecutedAt());
  }

  private void apply(InboxEnvelope<?> envelope, UUID periodId, String status, Instant at) {
    Instant effective = at != null ? at : envelope.occurredAt();
    jdbc.update(
        upsertSql,
        envelope.tenantId().value(),
        periodId,
        status,
        envelope.eventId(),
        Timestamp.from(effective != null ? effective : Instant.now()));
  }
}
