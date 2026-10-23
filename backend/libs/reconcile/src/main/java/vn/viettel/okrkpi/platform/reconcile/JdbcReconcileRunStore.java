package vn.viettel.okrkpi.platform.reconcile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ghi và đọc {@code <schema>.reconcile_run}. Bảng có RLS theo tenant nên bên gọi mở giao dịch trong
 * ngữ cảnh tenant ({@code app.tenant_id}); lớp này không tự đặt ngữ cảnh. Mã lần chạy tất định
 * (theo job, phạm vi, cửa sổ) nên hai bản sao của job chạy cùng lúc không ghi trùng: dòng thứ hai
 * bị bỏ qua và {@link #insert} trả {@code false}.
 */
public final class JdbcReconcileRunStore {

  private static final Pattern SCHEMA = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

  private final JdbcTemplate jdbc;
  private final String table;

  public JdbcReconcileRunStore(JdbcTemplate jdbc, String schema) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    if (schema == null || !SCHEMA.matcher(schema).matches()) {
      throw new IllegalArgumentException("Tên schema không hợp lệ: " + schema);
    }
    this.table = schema + ".reconcile_run";
  }

  public String table() {
    return table;
  }

  /** Thêm lần chạy; {@code false} khi mã đã có (lần chạy khác đã nhận). */
  public boolean insert(ReconcileRun r) {
    return jdbc.update(
            "INSERT INTO "
                + table
                + " (id, tenant_id, job, entity_type, started_at, finished_at, buckets_total,"
                + " buckets_mismatch, rows_repaired, status, detail)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (id) DO NOTHING",
            r.id(),
            r.tenantId(),
            r.job(),
            r.entityType(),
            Timestamp.from(r.startedAt()),
            r.finishedAt() == null ? null : Timestamp.from(r.finishedAt()),
            r.bucketsTotal(),
            r.bucketsMismatch(),
            r.rowsRepaired(),
            r.status().name(),
            r.detail())
        == 1;
  }

  /** Kết thúc lần chạy; {@code detail} (JSON) được gộp vào chi tiết đã có. */
  public void finish(
      UUID id, RunStatus status, int mismatched, int repaired, String detail, Instant at) {
    if (status == RunStatus.DANG_CHAY) {
      throw new IllegalArgumentException("Trạng thái kết thúc không thể là DANG_CHAY");
    }
    jdbc.update(
        "UPDATE "
            + table
            + " SET status = ?, buckets_mismatch = ?, rows_repaired = ?, finished_at = ?,"
            + " detail = detail || ?::jsonb WHERE id = ?",
        status.name(),
        mismatched,
        repaired,
        Timestamp.from(at),
        detail == null || detail.isBlank() ? "{}" : detail,
        id);
  }

  /**
   * Lần chạy của {@code job} còn {@code DANG_CHAY} và bắt đầu trước {@code before}, khoá {@code FOR
   * UPDATE SKIP LOCKED} để hai bản sao của job không cùng xử lý một lần chạy.
   */
  public List<ReconcileRun> pending(String job, Instant before, int limit) {
    return jdbc.query(
        "SELECT * FROM "
            + table
            + " WHERE job = ? AND status = 'DANG_CHAY' AND started_at < ?"
            + " ORDER BY started_at LIMIT ? FOR UPDATE SKIP LOCKED",
        JdbcReconcileRunStore::map,
        job,
        Timestamp.from(before),
        limit);
  }

  public Optional<ReconcileRun> find(UUID id) {
    return jdbc
        .query("SELECT * FROM " + table + " WHERE id = ?", JdbcReconcileRunStore::map, id)
        .stream()
        .findFirst();
  }

  /** Lần chạy đã kết thúc gần nhất của {@code job} trước {@code before} (cùng tenant theo RLS). */
  public Optional<ReconcileRun> lastFinished(String job, String entityType, Instant before) {
    return jdbc
        .query(
            "SELECT * FROM "
                + table
                + " WHERE job = ? AND entity_type = ? AND status <> 'DANG_CHAY' AND started_at < ?"
                + " ORDER BY started_at DESC LIMIT 1",
            JdbcReconcileRunStore::map,
            job,
            entityType,
            Timestamp.from(before))
        .stream()
        .findFirst();
  }

  private static ReconcileRun map(ResultSet rs, int row) throws SQLException {
    Timestamp finished = rs.getTimestamp("finished_at");
    return new ReconcileRun(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("job"),
        rs.getString("entity_type"),
        rs.getTimestamp("started_at").toInstant(),
        finished == null ? null : finished.toInstant(),
        (Integer) rs.getObject("buckets_total"),
        (Integer) rs.getObject("buckets_mismatch"),
        (Integer) rs.getObject("rows_repaired"),
        RunStatus.valueOf(rs.getString("status")),
        rs.getString("detail"));
  }
}
