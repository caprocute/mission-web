package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.port.SyncRunStore;
import vn.viettel.okrkpi.org.domain.OrgConflictException;

/** DV-04-07 · {@code org.sync_run}. */
@Repository
public class JdbcSyncRunStore implements SyncRunStore {

  private static final String COLS = "id, connector, status, started_at, finished_at, records, changes, errors, "
      + "dead_letters, reason";

  private final JdbcClient jdbc;

  public JdbcSyncRunStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void start(UUID id, String connector, Actor actor) {
    // Chỉ mục duy nhất một phần chặn hai lần chạy cùng nguồn; điểm lưu để lỗi không làm hỏng giao dịch bao ngoài.
    jdbc.sql("SAVEPOINT sync_run_start").update();
    try {
      jdbc.sql("INSERT INTO org.sync_run (id, tenant_id, connector, created_by, updated_by) "
              + "VALUES (:id, :tenant, :c, :actor, :actor)")
          .param("id", id).param("tenant", Sql.tenant()).param("c", connector).param("actor", actor.actorId())
          .update();
      jdbc.sql("RELEASE SAVEPOINT sync_run_start").update();
    } catch (DuplicateKeyException e) {
      jdbc.sql("ROLLBACK TO SAVEPOINT sync_run_start").update();
      throw new OrgConflictException("ORG-409-SYNC-RUNNING", "Nguồn này đang có lần đồng bộ chưa kết thúc.");
    }
  }

  @Override
  public Optional<Run> find(UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM org.sync_run WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", Sql.tenant()).param("id", id).query(JdbcSyncRunStore::map).optional();
  }

  @Override
  public void addBatch(UUID id, int records, int changes, int errors, Actor actor) {
    jdbc.sql("UPDATE org.sync_run SET records = records + :r, changes = changes + :c, errors = errors + :e, "
            + "updated_at = now(), updated_by = :actor, row_version = row_version + 1 "
            + "WHERE tenant_id = :tenant AND id = :id")
        .param("r", records).param("c", changes).param("e", errors).param("actor", actor.actorId())
        .param("tenant", Sql.tenant()).param("id", id)
        .update();
  }

  @Override
  public void finish(UUID id, String status, int deadLetters, String reason, Actor actor) {
    jdbc.sql("UPDATE org.sync_run SET status = :s, dead_letters = :d, reason = :reason, finished_at = now(), "
            + "updated_at = now(), updated_by = :actor, row_version = row_version + 1 "
            + "WHERE tenant_id = :tenant AND id = :id")
        .param("s", status).param("d", deadLetters)
        .param("reason", reason == null ? null : reason.substring(0, Math.min(500, reason.length())))
        .param("actor", actor.actorId()).param("tenant", Sql.tenant()).param("id", id)
        .update();
  }

  @Override
  public List<Run> startedTogether(UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM org.sync_run WHERE tenant_id = :tenant AND (started_at, created_by) = "
            + "(SELECT started_at, created_by FROM org.sync_run WHERE tenant_id = :tenant AND id = :id) ORDER BY id")
        .param("tenant", Sql.tenant()).param("id", id).query(JdbcSyncRunStore::map).list();
  }

  @Override
  public List<Run> page(String connector, OffsetDateTime beforeStarted, UUID beforeId, int limit) {
    StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM org.sync_run WHERE tenant_id = :tenant");
    Map<String, Object> p = new HashMap<>();
    p.put("tenant", Sql.tenant());
    if (connector != null) {
      sql.append(" AND connector = :c");
      p.put("c", connector);
    }
    if (beforeStarted != null) {
      sql.append(" AND (started_at, id) < (:bs, :bid)");
      p.put("bs", beforeStarted);
      p.put("bid", beforeId);
    }
    sql.append(" ORDER BY started_at DESC, id DESC LIMIT :limit");
    p.put("limit", limit);
    return jdbc.sql(sql.toString()).params(p).query(JdbcSyncRunStore::map).list();
  }

  private static Run map(ResultSet rs, int n) throws SQLException {
    return new Run(Sql.uuid(rs, "id"), rs.getString("connector"), rs.getString("status"), Sql.ts(rs, "started_at"),
        Sql.ts(rs, "finished_at"), rs.getInt("records"), rs.getInt("changes"), rs.getInt("errors"),
        rs.getInt("dead_letters"), rs.getString("reason"));
  }
}
