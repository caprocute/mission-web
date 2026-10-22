package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.PeriodConflictException;
import vn.viettel.okrkpi.config.application.port.PeriodRepository;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodMilestones;
import vn.viettel.okrkpi.config.domain.PeriodSchedule;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.domain.PeriodTransition;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link PeriodRepository} trên JDBC ({@code config.period}, {@code config.period_status_log}),
 * chạy trong giao dịch của {@code configTransactionManager} nên RLS theo {@code app.tenant_id}; mọi
 * câu lệnh vẫn lọc thêm {@code tenant_id}.
 *
 * <p>Dấu "đã phát sự kiện mở kỳ" ghi vào {@code config.idempotency_key} với người gọi là tài khoản
 * hệ thống (UUID toàn số 0) và khoá {@code ky-mo:<id kỳ>}; bảng giữ khoá 48 đến 72 giờ, đủ cho cửa
 * sổ hai ngày của tác vụ mở kỳ (suy luận, wiki trang 14).
 */
public class JdbcPeriodRepository implements PeriodRepository {

  static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
  private static final String COLUMNS =
      "id, tenant_id, created_at, created_by, row_version, code, name, kind, flow,"
          + " parent_period_id, start_date, end_date, plan_deadline, submission_deadline, status,"
          + " locked_at, locked_by, lock_saga_id, reopen_count, milestones";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcPeriodRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void insert(Period p) {
    try {
      jdbc.sql(
              """
              INSERT INTO config.period
                (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version, code,
                 name, kind, flow, parent_period_id, start_date, end_date, period_bucket,
                 plan_deadline, submission_deadline, status, reopen_count, milestones)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
              """)
          .param(p.id().value())
          .param(p.tenantId().value())
          .param(ts(p.createdAt()))
          .param(p.createdBy())
          .param(ts(p.createdAt()))
          .param(p.createdBy())
          .param(p.rowVersion())
          .param(p.code())
          .param(p.name())
          .param(p.kind().name())
          .param(p.flow().name())
          .param(p.parentId() == null ? null : p.parentId().value())
          .param(p.startDate())
          .param(p.endDate())
          .param(p.bucket())
          .param(p.schedule().planDeadline())
          .param(p.schedule().submissionDeadline())
          .param(p.state().name())
          .param(p.reopenCount())
          .param(milestones(p.schedule().milestones()))
          .update();
    } catch (DataIntegrityViolationException e) {
      throw conflict(e);
    }
  }

  @Override
  public boolean updateState(Period p, int expectedVersion, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE config.period
                   SET status = ?, locked_at = ?, locked_by = ?, lock_saga_id = ?,
                       reopen_count = ?, row_version = ?, updated_at = now(),
                       updated_by = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(p.state().name())
            .param(ts(p.lockedAt()))
            .param(p.lockedBy())
            .param(p.lockSagaId())
            .param(p.reopenCount())
            .param(p.rowVersion())
            .param(actorId)
            .param(p.tenantId().value())
            .param(p.id().value())
            .param(expectedVersion)
            .update()
        == 1;
  }

  @Override
  public void appendStatusLog(TenantId tenantId, PeriodTransition t) {
    jdbc.sql(
            """
            INSERT INTO config.period_status_log
              (id, tenant_id, period_id, from_status, to_status, cause_type, cause_ref, actor_id,
               changed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .param(t.logId())
        .param(tenantId.value())
        .param(t.periodId())
        .param(t.from().name())
        .param(t.to().name())
        .param(t.cause().name())
        .param(t.causeRef())
        .param(t.actorId())
        .param(ts(t.changedAt()))
        .update();
  }

  @Override
  public Optional<Period> findById(TenantId tenantId, PeriodId periodId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM config.period WHERE tenant_id = ? AND id = ?")
        .param(tenantId.value())
        .param(periodId.value())
        .query((rs, i) -> period(rs))
        .optional();
  }

  @Override
  public boolean existsById(TenantId tenantId, PeriodId periodId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM config.period WHERE tenant_id = ? AND id = ?)")
        .param(tenantId.value())
        .param(periodId.value())
        .query(Boolean.class)
        .single();
  }

  @Override
  public boolean overlaps(
      TenantId tenantId, PeriodKind kind, PeriodFlow flow, LocalDate start, LocalDate end) {
    return jdbc.sql(
            """
            SELECT EXISTS (
              SELECT 1 FROM config.period
               WHERE tenant_id = ? AND kind = ? AND flow = ?
                 AND daterange(start_date, end_date, '[]') && daterange(?, ?, '[]'))
            """)
        .param(tenantId.value())
        .param(kind.name())
        .param(flow.name())
        .param(start)
        .param(end)
        .query(Boolean.class)
        .single();
  }

  @Override
  public List<Period> covering(TenantId tenantId, PeriodKind kind, LocalDate day) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.period WHERE tenant_id = ? AND (?::text IS NULL OR kind = ?)"
                + " AND start_date <= ? AND end_date >= ? ORDER BY start_date DESC, id DESC")
        .param(tenantId.value())
        .param(kind == null ? null : kind.name())
        .param(kind == null ? null : kind.name())
        .param(day)
        .param(day)
        .query((rs, i) -> period(rs))
        .list();
  }

  @Override
  public List<Period> startingBetween(TenantId tenantId, LocalDate from, LocalDate to) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.period WHERE tenant_id = ? AND start_date BETWEEN ? AND ?"
                + " ORDER BY start_date, id")
        .param(tenantId.value())
        .param(from)
        .param(to)
        .query((rs, i) -> period(rs))
        .list();
  }

  @Override
  public List<Period> list(TenantId tenantId, Filter f, Keyset after, int fetchSize) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(" FROM config.period WHERE tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenantId.value());
    if (f != null && f.year() != null) {
      sql.append(" AND period_bucket / 10 = ?");
      params.add(f.year());
    }
    if (f != null && f.kind() != null) {
      sql.append(" AND kind = ?");
      params.add(f.kind().name());
    }
    if (f != null && f.flow() != null) {
      sql.append(" AND flow = ?");
      params.add(f.flow().name());
    }
    if (f != null && f.state() != null) {
      sql.append(" AND status = ?");
      params.add(f.state().name());
    }
    if (after != null) {
      sql.append(" AND (start_date, id) < (?, ?)");
      params.add(after.startDate());
      params.add(after.id());
    }
    sql.append(" ORDER BY start_date DESC, id DESC LIMIT ?");
    params.add(fetchSize);
    return jdbc.sql(sql.toString()).params(params).query((rs, i) -> period(rs)).list();
  }

  @Override
  public boolean markOpeningAnnounced(TenantId tenantId, PeriodId periodId) {
    String key = "ky-mo:" + periodId.value();
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("config.period.opened:" + tenantId.value() + ":" + periodId.value())
        .query((rs, i) -> 1)
        .single();
    boolean seen =
        jdbc.sql(
                """
                SELECT EXISTS (
                  SELECT 1 FROM config.idempotency_key
                   WHERE tenant_id = ? AND actor_id = ? AND idem_key = ?)
                """)
            .param(tenantId.value())
            .param(SYSTEM_ACTOR)
            .param(key)
            .query(Boolean.class)
            .single();
    if (seen) {
      return false;
    }
    return jdbc.sql(
                """
                INSERT INTO config.idempotency_key
                  (tenant_id, actor_id, idem_key, request_hash, status, resource_ref)
                VALUES (?, ?, ?, ?, 'XONG', ?)
                ON CONFLICT DO NOTHING
                """)
            .param(tenantId.value())
            .param(SYSTEM_ACTOR)
            .param(key)
            .param(new byte[0])
            .param("period:" + periodId.value())
            .update()
        == 1;
  }

  private Period period(ResultSet rs) throws SQLException {
    UUID parent = rs.getObject("parent_period_id", UUID.class);
    PeriodMilestones m = parseMilestones(rs.getString("milestones"));
    return Period.builder()
        .id(PeriodId.of(rs.getObject("id", UUID.class)))
        .tenantId(TenantId.of(rs.getObject("tenant_id", UUID.class)))
        .code(rs.getString("code"))
        .name(rs.getString("name"))
        .kind(PeriodKind.valueOf(rs.getString("kind")))
        .flow(PeriodFlow.valueOf(rs.getString("flow")))
        .parentId(parent == null ? null : PeriodId.of(parent))
        .startDate(rs.getObject("start_date", LocalDate.class))
        .endDate(rs.getObject("end_date", LocalDate.class))
        .schedule(
            new PeriodSchedule(
                rs.getObject("plan_deadline", LocalDate.class),
                rs.getObject("submission_deadline", LocalDate.class),
                m))
        .created(rs.getObject("created_by", UUID.class), instant(rs, "created_at"))
        .state(PeriodState.valueOf(rs.getString("status")))
        .locked(
            instant(rs, "locked_at"),
            rs.getObject("locked_by", UUID.class),
            rs.getObject("lock_saga_id", UUID.class))
        .reopenCount(rs.getInt("reopen_count"))
        .rowVersion(rs.getInt("row_version"))
        .build();
  }

  String milestones(PeriodMilestones m) {
    ObjectNode node = json.createObjectNode();
    put(node, "planApprovalDeadline", m.planApprovalDeadline());
    put(node, "evidenceDeadline", m.evidenceDeadline());
    put(node, "acceptanceBacklogDeadline", m.acceptanceBacklogDeadline());
    put(node, "plannedLockDate", m.plannedLockDate());
    return node.toString();
  }

  PeriodMilestones parseMilestones(String value) {
    if (value == null) {
      return PeriodMilestones.NONE;
    }
    try {
      JsonNode n = json.readTree(value);
      return new PeriodMilestones(
          date(n, "planApprovalDeadline"),
          date(n, "evidenceDeadline"),
          date(n, "acceptanceBacklogDeadline"),
          date(n, "plannedLockDate"));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cột milestones của kỳ hỏng", e);
    }
  }

  private static void put(ObjectNode node, String field, LocalDate value) {
    if (value != null) {
      node.put(field, value.toString());
    }
  }

  private static LocalDate date(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : LocalDate.parse(v.asText());
  }

  private static PeriodConflictException conflict(DataIntegrityViolationException e) {
    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
    String state = root instanceof SQLException sql ? sql.getSQLState() : null;
    if ("23P01".equals(state)) {
      return new PeriodConflictException(PeriodConflictException.Kind.OVERLAP, e);
    }
    if ("23505".equals(state)) {
      return new PeriodConflictException(PeriodConflictException.Kind.DUPLICATE_CODE, e);
    }
    throw e;
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
