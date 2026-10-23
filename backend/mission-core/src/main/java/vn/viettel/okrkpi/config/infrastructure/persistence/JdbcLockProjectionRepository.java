package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.LockProjectionRepository;
import vn.viettel.okrkpi.config.domain.LockBatch;
import vn.viettel.okrkpi.config.domain.LockCommand;
import vn.viettel.okrkpi.config.domain.LockEvent;
import vn.viettel.okrkpi.config.domain.LockFailure;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link LockProjectionRepository} trên JDBC ({@code config.period_lock_batch}, {@code
 * period_lock_failure}, {@code period_lock_event}, {@code period_lock_command}) trong giao dịch
 * {@code configTransactionManager}; RLS theo {@code app.tenant_id} và điều kiện tenant tường minh.
 */
public class JdbcLockProjectionRepository implements LockProjectionRepository {

  private final JdbcClient jdbc;

  public JdbcLockProjectionRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<LockBatch> batches(
      TenantId tenantId, UUID lockOrderId, String status, UUID afterId, int fetchSize) {
    return jdbc.sql(
            """
            SELECT id, unit_id, profile_count, status, attempts, finished_at
              FROM config.period_lock_batch
             WHERE tenant_id = :tenant AND lock_order_id = :order
               AND (CAST(:status AS varchar) IS NULL OR status = :status)
               AND (CAST(:after AS uuid) IS NULL OR id > :after)
             ORDER BY id
             LIMIT :limit
            """)
        .param("tenant", tenantId.value())
        .param("order", lockOrderId)
        .param("status", status)
        .param("after", afterId)
        .param("limit", fetchSize)
        .query(
            (rs, i) ->
                new LockBatch(
                    rs.getObject("id", UUID.class),
                    rs.getObject("unit_id", UUID.class),
                    rs.getInt("profile_count"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    instant(rs, "finished_at")))
        .list();
  }

  @Override
  public BatchTotals batchTotals(TenantId tenantId, UUID lockOrderId) {
    return jdbc.sql(
            """
            SELECT count(*) AS batches,
                   coalesce(sum(profile_count), 0) AS profiles,
                   coalesce(sum(profile_count) FILTER (WHERE status = 'XONG'), 0) AS scored
              FROM config.period_lock_batch
             WHERE tenant_id = ? AND lock_order_id = ?
            """)
        .param(tenantId.value())
        .param(lockOrderId)
        .query(
            (rs, i) ->
                new BatchTotals(rs.getInt("batches"), rs.getInt("profiles"), rs.getInt("scored")))
        .single();
  }

  @Override
  public List<LockFailure> openFailures(
      TenantId tenantId, UUID lockOrderId, UUID afterProfileId, int fetchSize) {
    return jdbc.sql(
            """
            SELECT profile_id, person_id, unit_id, batch_id, error, attempts, last_attempt_at
              FROM config.period_lock_failure
             WHERE tenant_id = :tenant AND lock_order_id = :order AND resolved_at IS NULL
               AND (CAST(:after AS uuid) IS NULL OR profile_id > :after)
             ORDER BY profile_id
             LIMIT :limit
            """)
        .param("tenant", tenantId.value())
        .param("order", lockOrderId)
        .param("after", afterProfileId)
        .param("limit", fetchSize)
        .query(
            (rs, i) ->
                new LockFailure(
                    rs.getObject("profile_id", UUID.class),
                    rs.getObject("person_id", UUID.class),
                    rs.getObject("unit_id", UUID.class),
                    rs.getObject("batch_id", UUID.class),
                    rs.getString("error"),
                    rs.getInt("attempts"),
                    instant(rs, "last_attempt_at")))
        .list();
  }

  @Override
  public int countOpenFailures(TenantId tenantId, UUID lockOrderId) {
    return jdbc.sql(
            "SELECT count(*) FROM config.period_lock_failure"
                + " WHERE tenant_id = ? AND lock_order_id = ? AND resolved_at IS NULL")
        .param(tenantId.value())
        .param(lockOrderId)
        .query(Integer.class)
        .single();
  }

  @Override
  public List<UUID> openFailuresAmong(
      TenantId tenantId, UUID lockOrderId, Collection<UUID> profileIds) {
    if (profileIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT profile_id FROM config.period_lock_failure
             WHERE tenant_id = :tenant AND lock_order_id = :order AND resolved_at IS NULL
               AND profile_id IN (:ids)
             ORDER BY profile_id
            """)
        .param("tenant", tenantId.value())
        .param("order", lockOrderId)
        .param("ids", List.copyOf(profileIds))
        .query(UUID.class)
        .list();
  }

  @Override
  public List<LockEvent> events(
      TenantId tenantId, UUID lockOrderId, Instant beforeAt, UUID beforeId, int fetchSize) {
    return jdbc.sql(
            """
            SELECT id, occurred_at, kind, event, evt_code, actor_id, ledger_entry_id
              FROM config.period_lock_event
             WHERE tenant_id = :tenant AND lock_order_id = :order
               AND (CAST(:at AS timestamptz) IS NULL
                    OR (occurred_at, id) < (CAST(:at AS timestamptz), CAST(:id AS uuid)))
             ORDER BY occurred_at DESC, id DESC
             LIMIT :limit
            """)
        .param("tenant", tenantId.value())
        .param("order", lockOrderId)
        .param("at", ts(beforeAt))
        .param("id", beforeId)
        .param("limit", fetchSize)
        .query(
            (rs, i) ->
                new LockEvent(
                    rs.getObject("id", UUID.class),
                    instant(rs, "occurred_at"),
                    rs.getString("kind"),
                    rs.getString("event"),
                    rs.getString("evt_code"),
                    rs.getObject("actor_id", UUID.class),
                    rs.getObject("ledger_entry_id", UUID.class)))
        .list();
  }

  @Override
  public void appendEvent(TenantId tenantId, UUID lockOrderId, LockEvent e) {
    jdbc.sql(
            """
            INSERT INTO config.period_lock_event
              (id, tenant_id, lock_order_id, occurred_at, kind, event, evt_code, actor_id,
               ledger_entry_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .param(e.id())
        .param(tenantId.value())
        .param(lockOrderId)
        .param(ts(e.at()))
        .param(e.kind())
        .param(truncate(e.event(), 1000))
        .param(e.evt())
        .param(e.actorId())
        .param(e.ledgerEntryId())
        .update();
  }

  @Override
  public void upsertBatch(TenantId tenantId, UUID lockOrderId, LockBatch b) {
    jdbc.sql(
            """
            INSERT INTO config.period_lock_batch
              (id, tenant_id, lock_order_id, unit_id, profile_count, status, attempts, finished_at,
               updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (id) DO UPDATE
               SET profile_count = EXCLUDED.profile_count, status = EXCLUDED.status,
                   attempts = EXCLUDED.attempts, finished_at = EXCLUDED.finished_at,
                   updated_at = now()
            """)
        .param(b.id())
        .param(tenantId.value())
        .param(lockOrderId)
        .param(b.unitId())
        .param(b.profileCount())
        .param(b.status())
        .param(b.attempts())
        .param(ts(b.finishedAt()))
        .update();
  }

  @Override
  public void upsertFailure(TenantId tenantId, UUID lockOrderId, LockFailure f) {
    jdbc.sql(
            """
            INSERT INTO config.period_lock_failure
              (id, tenant_id, lock_order_id, profile_id, person_id, unit_id, batch_id, error,
               attempts, last_attempt_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (lock_order_id, profile_id) DO UPDATE
               SET batch_id = EXCLUDED.batch_id, error = EXCLUDED.error,
                   attempts = EXCLUDED.attempts, last_attempt_at = EXCLUDED.last_attempt_at,
                   resolved_at = NULL
            """)
        .param(Ids.newUuidV7())
        .param(tenantId.value())
        .param(lockOrderId)
        .param(f.profileId())
        .param(f.personId())
        .param(f.unitId())
        .param(f.batchId())
        .param(truncate(f.error(), 500))
        .param(f.attempts())
        .param(ts(f.lastAttemptAt()))
        .update();
  }

  @Override
  public int resolveFailures(
      TenantId tenantId, UUID lockOrderId, Collection<UUID> profileIds, Instant at) {
    if (profileIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            """
            UPDATE config.period_lock_failure SET resolved_at = :at
             WHERE tenant_id = :tenant AND lock_order_id = :order AND resolved_at IS NULL
               AND profile_id IN (:ids)
            """)
        .param("at", ts(at))
        .param("tenant", tenantId.value())
        .param("order", lockOrderId)
        .param("ids", List.copyOf(profileIds))
        .update();
  }

  @Override
  public Optional<CommandRow> command(TenantId tenantId, UUID commandId) {
    return jdbc.sql(
            """
            SELECT id, lock_order_id, command, status, created_by, created_at, updated_at
              FROM config.period_lock_command
             WHERE tenant_id = ? AND id = ?
            """)
        .param(tenantId.value())
        .param(commandId)
        .query(
            (rs, i) ->
                new CommandRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("lock_order_id", UUID.class),
                    LockCommand.valueOf(rs.getString("command")),
                    rs.getString("status"),
                    rs.getObject("created_by", UUID.class),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at")))
        .optional();
  }

  @Override
  public int finishCommands(
      TenantId tenantId, UUID lockOrderId, LockCommand command, String status, Instant at) {
    return jdbc.sql(
            """
            UPDATE config.period_lock_command
               SET status = ?, finished_at = ?, updated_at = ?
             WHERE tenant_id = ? AND lock_order_id = ? AND command = ?
               AND status IN ('CHO', 'DANG_CHAY')
            """)
        .param(status)
        .param(ts(at))
        .param(ts(at))
        .param(tenantId.value())
        .param(lockOrderId)
        .param(command.name())
        .update();
  }

  private static String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  private static Timestamp ts(Instant at) {
    return at == null ? null : Timestamp.from(at);
  }
}
