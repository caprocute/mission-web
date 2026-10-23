package vn.viettel.okrkpi.integ.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.port.SyncRuns;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@code integ.sync_run}; lần chạy cần nhận qua hàm quét liên tenant {@code
 * fn_lan_dong_bo_can_chay}.
 */
public class JdbcSyncRuns implements SyncRuns {

  private static final String COLUMNS =
      "id, connector_id, kind, status, retry_of, pages, messages, duplicates, dead_letters,"
          + " error_detail, requested_at, started_at, finished_at";

  private final JdbcClient jdbc;

  public JdbcSyncRuns(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insert(
      TenantId tenant, UUID id, UUID connectorId, Kind kind, UUID retryOf, Instant at) {
    // Chỉ mục duy nhất một phần giữ mỗi kết nối một lần chạy đang mở; ON CONFLICT để giao dịch
    // không hỏng khi trùng.
    return jdbc.sql(
                "INSERT INTO integ.sync_run (id, tenant_id, connector_id, kind, retry_of,"
                    + " requested_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
            .params(id, tenant.value(), connectorId, kind.name(), retryOf, Timestamp.from(at))
            .update()
        == 1;
  }

  @Override
  public Optional<Run> find(TenantId tenant, UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM integ.sync_run WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query((rs, i) -> run(rs))
        .optional();
  }

  @Override
  public Optional<Run> open(TenantId tenant, UUID connectorId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM integ.sync_run WHERE tenant_id = ? AND connector_id = ?"
                + " AND status IN ('CHO', 'DANG_CHAY')")
        .params(tenant.value(), connectorId)
        .query((rs, i) -> run(rs))
        .optional();
  }

  @Override
  public Optional<Instant> lastRequested(TenantId tenant, UUID connectorId, Kind kind) {
    return jdbc.sql(
            "SELECT max(requested_at) FROM integ.sync_run WHERE tenant_id = ? AND connector_id = ?"
                + " AND kind = ?")
        .params(tenant.value(), connectorId, kind.name())
        .query((rs, i) -> instant(rs, 1))
        .optional();
  }

  @Override
  public List<Due> due(int limit, int leaseSeconds) {
    return jdbc.sql("SELECT tenant_id, id FROM integ.fn_lan_dong_bo_can_chay(?, ?)")
        .params(limit, leaseSeconds)
        .query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
        .list();
  }

  @Override
  public boolean claim(TenantId tenant, UUID id, int leaseSeconds, Instant at) {
    Timestamp now = Timestamp.from(at);
    return jdbc.sql(
                "UPDATE integ.sync_run SET status = 'DANG_CHAY',"
                    + " started_at = coalesce(started_at, ?), heartbeat_at = ?"
                    + " WHERE tenant_id = ? AND id = ? AND (status = 'CHO' OR (status = 'DANG_CHAY'"
                    + " AND heartbeat_at < ?::timestamptz - make_interval(secs => ?)))")
            .params(now, now, tenant.value(), id, now, leaseSeconds)
            .update()
        == 1;
  }

  @Override
  public void progress(
      TenantId tenant, UUID id, int pages, int messages, int duplicates, Instant at) {
    jdbc.sql(
            "UPDATE integ.sync_run SET pages = ?, messages = ?, duplicates = ?, heartbeat_at = ?"
                + " WHERE tenant_id = ? AND id = ?")
        .params(pages, messages, duplicates, Timestamp.from(at), tenant.value(), id)
        .update();
  }

  @Override
  public void finish(
      TenantId tenant, UUID id, Status status, int deadLetters, String error, Instant at) {
    jdbc.sql(
            "UPDATE integ.sync_run SET status = ?, dead_letters = ?, error_detail = ?,"
                + " finished_at = ?, heartbeat_at = ? WHERE tenant_id = ? AND id = ?")
        .params(
            status.name(),
            deadLetters,
            error == null ? null : error.substring(0, Math.min(error.length(), 500)),
            Timestamp.from(at),
            Timestamp.from(at),
            tenant.value(),
            id)
        .update();
  }

  private static Run run(ResultSet rs) throws SQLException {
    return new Run(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        Kind.valueOf(rs.getString(3)),
        Status.valueOf(rs.getString(4)),
        rs.getObject(5, UUID.class),
        rs.getInt(6),
        rs.getInt(7),
        rs.getInt(8),
        rs.getInt(9),
        rs.getString(10),
        instant(rs, 11),
        instant(rs, 12),
        instant(rs, 13));
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
