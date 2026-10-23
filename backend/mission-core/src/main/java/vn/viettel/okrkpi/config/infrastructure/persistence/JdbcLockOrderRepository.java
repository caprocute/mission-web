package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.LockOrderRepository;
import vn.viettel.okrkpi.config.domain.LockCancelRequest;
import vn.viettel.okrkpi.config.domain.LockOrderStatus;
import vn.viettel.okrkpi.config.domain.LockStep;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link LockOrderRepository} trên JDBC ({@code config.period_lock_order}, {@code
 * config.period_lock_step}) trong giao dịch {@code configTransactionManager}.
 */
public class JdbcLockOrderRepository implements LockOrderRepository {

  private static final String COLUMNS =
      "id, tenant_id, period_id, status, basis_doc, planned_lock_at, requested_by, created_at,"
          + " approved_by, approved_at, started_by, started_at, saga_id, idempotency_key,"
          + " row_version, pause_requested, pause_requested_by, pause_requested_at,"
          + " cancel_requested_by, cancel_requested_at, cancel_reason, cancel_approved_by,"
          + " cancel_approved_at";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcLockOrderRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void insert(PeriodLockOrder o, UUID actorId) {
    try {
      jdbc.sql(
              """
              INSERT INTO config.period_lock_order
                (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                 period_id, requested_by, basis_doc, planned_lock_at, idempotency_key, status)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              """)
          .param(o.id())
          .param(o.tenantId().value())
          .param(ts(o.requestedAt()))
          .param(actorId)
          .param(ts(o.requestedAt()))
          .param(actorId)
          .param(o.rowVersion())
          .param(o.periodId().value())
          .param(o.requestedBy())
          .param(o.basisDoc())
          .param(ts(o.plannedLockAt()))
          .param(o.idempotencyKey())
          .param(o.status().name())
          .update();
    } catch (DataIntegrityViolationException e) {
      Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
      if (root instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
        throw new ActiveOrderExistsException(e);
      }
      throw e;
    }
  }

  @Override
  public boolean update(PeriodLockOrder o, int expectedVersion, UUID actorId) {
    LockCancelRequest cancel = o.cancelRequest();
    return jdbc.sql(
                """
                UPDATE config.period_lock_order
                   SET status = ?, approved_by = ?, approved_at = ?, started_by = ?,
                       started_at = ?, saga_id = ?, row_version = ?, updated_at = now(),
                       updated_by = ?, pause_requested = ?, pause_requested_by = ?,
                       pause_requested_at = ?, cancel_requested_by = ?, cancel_requested_at = ?,
                       cancel_reason = ?, cancel_approved_by = ?, cancel_approved_at = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(o.status().name())
            .param(o.approvedBy())
            .param(ts(o.approvedAt()))
            .param(o.startedBy())
            .param(ts(o.startedAt()))
            .param(o.sagaId())
            .param(o.rowVersion())
            .param(actorId)
            .param(o.pauseRequested())
            .param(o.pauseRequestedBy())
            .param(ts(o.pauseRequestedAt()))
            .param(cancel == null ? null : cancel.requestedBy())
            .param(cancel == null ? null : ts(cancel.requestedAt()))
            .param(cancel == null ? null : cancel.reason())
            .param(cancel == null ? null : cancel.approvedBy())
            .param(cancel == null ? null : ts(cancel.approvedAt()))
            .param(o.tenantId().value())
            .param(o.id())
            .param(expectedVersion)
            .update()
        == 1;
  }

  @Override
  public Optional<PeriodLockOrder> findById(TenantId tenantId, UUID id) {
    return one("WHERE tenant_id = ? AND id = ?", tenantId.value(), id);
  }

  @Override
  public Optional<PeriodLockOrder> lockById(TenantId tenantId, UUID id) {
    return one("WHERE tenant_id = ? AND id = ? FOR UPDATE", tenantId.value(), id);
  }

  @Override
  public Optional<PeriodLockOrder> active(TenantId tenantId, PeriodId periodId) {
    return one(
        "WHERE tenant_id = ? AND period_id = ? AND status <> 'DA_HUY'",
        tenantId.value(),
        periodId.value());
  }

  @Override
  public Optional<PeriodLockOrder> latest(TenantId tenantId, PeriodId periodId) {
    return one(
        "WHERE tenant_id = ? AND period_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
        tenantId.value(),
        periodId.value());
  }

  @Override
  public int countKeys(TenantId tenantId, String keyPrefix) {
    return jdbc.sql(
            "SELECT count(*) FROM config.period_lock_order WHERE tenant_id = ?"
                + " AND (idempotency_key = ? OR idempotency_key LIKE ?)")
        .param(tenantId.value())
        .param(keyPrefix)
        .param(keyPrefix + "-%")
        .query(Integer.class)
        .single();
  }

  @Override
  public List<LockStep> steps(TenantId tenantId, UUID lockOrderId) {
    return jdbc.sql(
            """
            SELECT step_no, name, status, started_at, finished_at, result_summary,
                   compensation_action, compensated_at
              FROM config.period_lock_step
             WHERE tenant_id = ? AND lock_order_id = ?
             ORDER BY step_no
            """)
        .param(tenantId.value())
        .param(lockOrderId)
        .query(
            (rs, i) ->
                new LockStep(
                    rs.getInt("step_no") - 1,
                    rs.getString("name"),
                    rs.getString("status"),
                    instant(rs, "started_at"),
                    instant(rs, "finished_at"),
                    summary(rs.getString("result_summary")),
                    rs.getString("compensation_action"),
                    instant(rs, "compensated_at")))
        .list();
  }

  private Optional<PeriodLockOrder> one(String where, Object... params) {
    JdbcClient.StatementSpec spec =
        jdbc.sql("SELECT " + COLUMNS + " FROM config.period_lock_order " + where);
    for (Object p : params) {
      spec = spec.param(p);
    }
    return spec.query(JdbcLockOrderRepository::map).optional();
  }

  private static PeriodLockOrder map(ResultSet rs, int row) throws SQLException {
    return new PeriodLockOrder(
            rs.getObject("id", UUID.class),
            TenantId.of(rs.getObject("tenant_id", UUID.class)),
            PeriodId.of(rs.getObject("period_id", UUID.class)),
            LockOrderStatus.valueOf(rs.getString("status")),
            rs.getString("basis_doc"),
            instant(rs, "planned_lock_at"),
            rs.getObject("requested_by", UUID.class),
            instant(rs, "created_at"),
            rs.getObject("approved_by", UUID.class),
            instant(rs, "approved_at"),
            rs.getObject("started_by", UUID.class),
            instant(rs, "started_at"),
            rs.getObject("saga_id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getInt("row_version"))
        .withControl(
            rs.getBoolean("pause_requested"),
            rs.getObject("pause_requested_by", UUID.class),
            instant(rs, "pause_requested_at"),
            cancelOf(rs));
  }

  private static LockCancelRequest cancelOf(ResultSet rs) throws SQLException {
    UUID by = rs.getObject("cancel_requested_by", UUID.class);
    if (by == null) {
      return null;
    }
    return new LockCancelRequest(
        by,
        instant(rs, "cancel_requested_at"),
        rs.getString("cancel_reason"),
        rs.getObject("cancel_approved_by", UUID.class),
        instant(rs, "cancel_approved_at"));
  }

  private Map<String, Object> summary(String raw) {
    if (raw == null) {
      return Map.of();
    }
    try {
      return json.readValue(raw, MAP);
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  private static Timestamp ts(Instant at) {
    return at == null ? null : Timestamp.from(at);
  }
}
