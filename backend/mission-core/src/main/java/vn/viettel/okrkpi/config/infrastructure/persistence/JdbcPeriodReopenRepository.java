package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.PeriodReopenRepository;
import vn.viettel.okrkpi.config.domain.PeriodReopen;
import vn.viettel.okrkpi.config.domain.ReopenScope;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.period_reopen} bằng JDBC (CORE-08-06); RLS lọc theo {@code app.tenant_id}. */
public class JdbcPeriodReopenRepository implements PeriodReopenRepository {

  private static final String COLUMNS =
      "id, tenant_id, period_id, scope, scope_ids, opened_at, open_until, relock_started_at,"
          + " relocked_at";

  private final JdbcClient jdbc;

  public JdbcPeriodReopenRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public boolean insertIfAbsent(PeriodReopen r, UUID actorId) {
    return jdbc.sql(
                """
                INSERT INTO config.period_reopen
                  (id, tenant_id, created_by, updated_by, period_id, scope, scope_ids, opened_at,
                   open_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """)
            .param(r.id())
            .param(r.tenantId().value())
            .param(actorId)
            .param(actorId)
            .param(r.periodId().value())
            .param(r.scope().kind().name())
            .param(r.scope().ids().toArray(UUID[]::new))
            .param(Timestamp.from(r.openedAt()))
            .param(Timestamp.from(r.openUntil()))
            .update()
        == 1;
  }

  @Override
  public Optional<PeriodReopen> findById(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM config.period_reopen WHERE tenant_id = ? AND id = ?")
        .param(tenantId.value())
        .param(id)
        .query(JdbcPeriodReopenRepository::map)
        .optional();
  }

  @Override
  public Optional<PeriodReopen> findOpen(TenantId tenantId, PeriodId periodId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.period_reopen WHERE tenant_id = ? AND period_id = ?"
                + " AND relocked_at IS NULL")
        .param(tenantId.value())
        .param(periodId.value())
        .query(JdbcPeriodReopenRepository::map)
        .optional();
  }

  @Override
  public List<PeriodReopen> dueForRelock(TenantId tenantId, Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.period_reopen WHERE tenant_id = ? AND relocked_at IS NULL"
                + " AND relock_started_at IS NULL AND open_until <= ? ORDER BY open_until LIMIT ?")
        .param(tenantId.value())
        .param(Timestamp.from(now))
        .param(limit)
        .query(JdbcPeriodReopenRepository::map)
        .list();
  }

  @Override
  public boolean markRelockStarted(TenantId tenantId, UUID id, Instant at, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE config.period_reopen
                   SET relock_started_at = ?, updated_at = ?, updated_by = ?,
                       row_version = row_version + 1
                 WHERE tenant_id = ? AND id = ? AND relock_started_at IS NULL
                   AND relocked_at IS NULL
                """)
            .param(Timestamp.from(at))
            .param(Timestamp.from(at))
            .param(actorId)
            .param(tenantId.value())
            .param(id)
            .update()
        == 1;
  }

  @Override
  public boolean markRelocked(TenantId tenantId, UUID id, Instant at, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE config.period_reopen
                   SET relocked_at = ?, relock_started_at = coalesce(relock_started_at, ?),
                       updated_at = ?, updated_by = ?, row_version = row_version + 1
                 WHERE tenant_id = ? AND id = ? AND relocked_at IS NULL
                """)
            .param(Timestamp.from(at))
            .param(Timestamp.from(at))
            .param(Timestamp.from(at))
            .param(actorId)
            .param(tenantId.value())
            .param(id)
            .update()
        == 1;
  }

  private static PeriodReopen map(ResultSet rs, int row) throws SQLException {
    Array ids = rs.getArray("scope_ids");
    List<UUID> scopeIds = ids == null ? List.of() : Arrays.asList((UUID[]) ids.getArray());
    return new PeriodReopen(
        rs.getObject("id", UUID.class),
        TenantId.of(rs.getObject("tenant_id", UUID.class)),
        PeriodId.of(rs.getObject("period_id", UUID.class)),
        ReopenScope.of(rs.getString("scope"), scopeIds),
        instant(rs, "opened_at"),
        instant(rs, "open_until"),
        instant(rs, "relock_started_at"),
        instant(rs, "relocked_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
