package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.oversight.application.port.PostAuditRepository;
import vn.viettel.okrkpi.oversight.domain.postaudit.PostAudit;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đợt hậu kiểm trên {@code oversight.post_audit} (CORE-07-09). */
public class JdbcPostAuditRepository implements PostAuditRepository {

  private static final String COLUMNS =
      "id, period_id, period_bucket, audit_kind, target_type, target_id, basis, inspector_id,"
          + " findings_enc, dek_id, conclusion, reopen_request_id, evidence_object_ids, opened_at,"
          + " closed_at, row_version";

  private final JdbcClient jdbc;

  public JdbcPostAuditRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void insert(TenantId tenant, PostAudit a, UUID actorId) {
    Timestamp now = Timestamp.from(a.openedAt());
    jdbc.sql(
            """
            INSERT INTO oversight.post_audit
              (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
               period_id, period_bucket, audit_kind, target_type, target_id, basis, inspector_id,
               opened_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .param(a.id())
        .param(tenant.value())
        .param(now)
        .param(actorId)
        .param(now)
        .param(actorId)
        .param(a.rowVersion())
        .param(a.periodId())
        .param(a.periodBucket())
        .param(a.auditKind())
        .param(a.targetType())
        .param(a.targetId())
        .param(a.basis())
        .param(a.inspectorId())
        .param(now)
        .update();
  }

  @Override
  public Optional<PostAudit> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM oversight.post_audit WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(id)
        .query(mapper())
        .optional();
  }

  @Override
  public Optional<PostAudit> lock(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM oversight.post_audit WHERE tenant_id = ? AND id = ? FOR UPDATE")
        .param(tenant.value())
        .param(id)
        .query(mapper())
        .optional();
  }

  @Override
  public boolean close(TenantId tenant, PostAudit c, int expectedVersion, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE oversight.post_audit
                   SET findings_enc = ?, dek_id = ?, conclusion = ?, reopen_request_id = ?,
                       evidence_object_ids = ?::uuid[], closed_at = ?, row_version = ?,
                       updated_at = ?, updated_by = ?
                 WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(c.findingsCipher())
            .param(c.dekId())
            .param(c.conclusion())
            .param(c.reopenRequestId())
            .param(uuidArray(c.evidenceObjectIds()))
            .param(Timestamp.from(c.closedAt()))
            .param(c.rowVersion())
            .param(Timestamp.from(c.closedAt()))
            .param(actorId)
            .param(c.periodBucket())
            .param(tenant.value())
            .param(c.id())
            .param(expectedVersion)
            .update()
        == 1;
  }

  @Override
  public List<PostAudit> list(TenantId tenant, Filter f, After after, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT " + COLUMNS + " FROM oversight.post_audit WHERE tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant.value());
    if (f.periodId() != null) {
      sql.append(" AND period_id = ?");
      params.add(f.periodId());
    }
    if (f.open() != null) {
      sql.append(f.open() ? " AND closed_at IS NULL" : " AND closed_at IS NOT NULL");
    }
    if (f.inspectorId() != null) {
      // Phạm vi Cd: đợt do chính người gọi lập hoặc đối tượng là đơn vị trong cây đơn vị.
      sql.append(
          " AND (inspector_id = ? OR (target_type = 'DON_VI' AND target_id = ANY(?::uuid[])))");
      params.add(f.inspectorId());
      params.add(uuidArray(f.units()));
    }
    if (after != null) {
      sql.append(" AND (opened_at, id) < (?, ?)");
      params.add(Timestamp.from(after.openedAt()));
      params.add(after.id());
    }
    sql.append(" ORDER BY opened_at DESC, id DESC LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query(mapper()).list();
  }

  @Override
  public boolean reopenRequestOfPeriod(TenantId tenant, UUID reopenRequestId, UUID periodId) {
    return jdbc.sql(
                "SELECT count(*) FROM oversight.reopen_request"
                    + " WHERE tenant_id = ? AND id = ? AND period_id = ?")
            .param(tenant.value())
            .param(reopenRequestId)
            .param(periodId)
            .query(Long.class)
            .single()
        > 0;
  }

  private static RowMapper<PostAudit> mapper() {
    return (rs, n) ->
        new PostAudit(
            rs.getObject("id", UUID.class),
            rs.getObject("period_id", UUID.class),
            rs.getInt("period_bucket"),
            rs.getString("audit_kind"),
            rs.getString("target_type"),
            rs.getObject("target_id", UUID.class),
            rs.getString("basis"),
            rs.getObject("inspector_id", UUID.class),
            rs.getBytes("findings_enc"),
            rs.getObject("dek_id", UUID.class),
            rs.getString("conclusion"),
            rs.getObject("reopen_request_id", UUID.class),
            uuids(rs.getArray("evidence_object_ids")),
            instant(rs, "opened_at"),
            instant(rs, "closed_at"),
            rs.getInt("row_version"));
  }

  private static List<UUID> uuids(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object[] values = (Object[]) array.getArray();
    return Arrays.stream(values).map(v -> (UUID) v).toList();
  }

  private static String uuidArray(Collection<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
