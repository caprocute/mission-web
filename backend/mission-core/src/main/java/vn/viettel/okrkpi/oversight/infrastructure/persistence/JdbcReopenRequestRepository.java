package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenBasis;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScopeItem;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho {@code oversight.reopen_request} bằng JDBC (CORE-07-07). {@code scope_json} giữ {@code
 * items}, {@code basis}, {@code units}, {@code openWorkingDays} và {@code cancellation}; {@code
 * scope}, {@code scope_ids} là bản chuẩn hoá để lọc (TL-03).
 */
public class JdbcReopenRequestRepository implements ReopenRequestRepository {

  private static final String COLUMNS =
      "id, period_id, period_bucket, code, scope, scope_ids, scope_json::text AS scope_json, reason,"
          + " basis_ref, audit_kind, requested_by, approvals_required, status, open_until,"
          + " executed_at, relocked_at, created_at, row_version";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcReopenRequestRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void lockCodes(TenantId tenant, int year) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("oversight.reopen_request.code:" + tenant.value() + ":" + year)
        .query()
        .singleValue();
  }

  @Override
  public List<String> codesOfYear(TenantId tenant, int year) {
    return jdbc.sql("SELECT code FROM oversight.reopen_request WHERE tenant_id = ? AND code LIKE ?")
        .param(tenant.value())
        .param("MLK-" + year + "-%")
        .query(String.class)
        .list();
  }

  @Override
  public List<ReopenRequest> ofPeriod(TenantId tenant, UUID periodId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM oversight.reopen_request WHERE tenant_id = ? AND period_id = ?")
        .param(tenant.value())
        .param(periodId)
        .query(mapper())
        .list();
  }

  @Override
  public void insert(TenantId tenant, ReopenRequest r, UUID actorId) {
    Timestamp now = Timestamp.from(r.createdAt());
    jdbc.sql(
            """
            INSERT INTO oversight.reopen_request
              (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
               period_id, period_bucket, code, scope, scope_ids, scope_json, reason, basis_ref,
               audit_kind, requested_by, approvals_required, status, open_until)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
            """)
        .param(r.id())
        .param(tenant.value())
        .param(now)
        .param(actorId)
        .param(now)
        .param(actorId)
        .param(r.rowVersion())
        .param(r.periodId())
        .param(r.periodBucket())
        .param(r.code())
        .param(r.scope().name())
        .param(uuidArray(r.scopeIds()))
        .param(scopeJson(r))
        .param(r.reason())
        .param(r.basis().ref())
        .param(r.auditKind())
        .param(r.requestedBy())
        .param(r.approvalsRequired())
        .param(r.status().name())
        .param(Timestamp.from(r.openUntil()))
        .update();
  }

  @Override
  public Optional<ReopenRequest> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM oversight.reopen_request WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(id)
        .query(mapper())
        .optional();
  }

  @Override
  public boolean cancel(TenantId tenant, ReopenRequest r, int expectedVersion, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE oversight.reopen_request
                   SET status = ?, scope_json = ?::jsonb, row_version = ?,
                       updated_at = now(), updated_by = ?
                 WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(r.status().name())
            .param(scopeJson(r))
            .param(r.rowVersion())
            .param(actorId)
            .param(r.periodBucket())
            .param(tenant.value())
            .param(r.id())
            .param(expectedVersion)
            .update()
        == 1;
  }

  @Override
  public boolean basisExists(TenantId tenant, UUID periodId, ReopenBasis basis) {
    String table =
        switch (basis.kind()) {
          case KHIEU_NAI -> "oversight.complaint";
          case CANH_BAO -> "oversight.anomaly_alert";
          case HAU_KIEM -> "oversight.post_audit";
          case QUYET_DINH -> null;
        };
    if (table == null || basis.id() == null) {
      return false;
    }
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM "
                + table
                + " WHERE tenant_id = ? AND period_id = ? AND id = ?)")
        .param(tenant.value())
        .param(periodId)
        .param(basis.id())
        .query(Boolean.class)
        .single();
  }

  @Override
  public List<Adjustment> complaintAdjustments(TenantId tenant, UUID complaintId) {
    Optional<String> raw =
        jdbc.sql(
                """
                SELECT adjustment_json::text FROM oversight.complaint_response
                 WHERE tenant_id = ? AND complaint_id = ? AND record_kind = 'KET_LUAN'
                   AND adjustment_json IS NOT NULL
                 ORDER BY responded_at DESC LIMIT 1
                """)
            .param(tenant.value())
            .param(complaintId)
            .query(String.class)
            .optional();
    if (raw.isEmpty()) {
      return List.of();
    }
    JsonNode root = read(raw.get());
    JsonNode list = root.has("items") ? root.get("items") : root.path("adjustments");
    List<Adjustment> out = new ArrayList<>();
    for (JsonNode a : list) {
      out.add(
          new Adjustment(
              text(a, "targetKey"), text(a, "oldValue"), text(a, "newValue"), text(a, "reason")));
    }
    return out;
  }

  @Override
  public List<Approval> approvals(TenantId tenant, int periodBucket, UUID requestId) {
    return jdbc.sql(
            """
            SELECT id, approver_id, approver_role, decision, mfa_acr, decided_at, sign_request_id,
                   comment
              FROM oversight.reopen_approval
             WHERE period_bucket = ? AND tenant_id = ? AND reopen_request_id = ?
             ORDER BY decided_at, id
            """)
        .param(periodBucket)
        .param(tenant.value())
        .param(requestId)
        .query(
            (rs, n) ->
                new Approval(
                    rs.getObject("id", UUID.class),
                    rs.getObject("approver_id", UUID.class),
                    rs.getString("approver_role"),
                    rs.getString("decision"),
                    rs.getString("mfa_acr"),
                    instant(rs, "decided_at"),
                    rs.getObject("sign_request_id", UUID.class),
                    rs.getString("comment")))
        .list();
  }

  @Override
  public List<ReopenRequest> page(TenantId tenant, Filter f, After after, int fetchSize) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(" FROM oversight.reopen_request r WHERE r.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant.value());
    if (f.periodId() != null) {
      sql.append(" AND r.period_id = ?");
      params.add(f.periodId());
    }
    if (f.statuses() != null && !f.statuses().isEmpty()) {
      sql.append(" AND r.status = ANY (?::text[])");
      params.add(f.statuses().stream().map(Enum::name).collect(Collectors.joining(",", "{", "}")));
    }
    if (!f.everything()) {
      sql.append(
          " AND (r.requested_by = ? OR EXISTS (SELECT 1 FROM oversight.reopen_approval a"
              + " WHERE a.period_bucket = r.period_bucket AND a.tenant_id = r.tenant_id"
              + " AND a.reopen_request_id = r.id AND a.approver_id = ?)");
      params.add(f.viewer());
      params.add(f.viewer());
      if (f.units() != null && !f.units().isEmpty()) {
        sql.append(" OR jsonb_exists_any(r.scope_json -> 'units', ?::text[])");
        params.add(uuidArray(f.units()));
      }
      sql.append(")");
    }
    if (after != null) {
      sql.append(" AND (r.created_at, r.id) < (?, ?)");
      params.add(Timestamp.from(after.createdAt()));
      params.add(after.id());
    }
    sql.append(" ORDER BY r.created_at DESC, r.id DESC LIMIT ?");
    params.add(fetchSize);
    return jdbc.sql(sql.toString()).params(params).query(mapper()).list();
  }

  private RowMapper<ReopenRequest> mapper() {
    return (ResultSet rs, int row) -> {
      JsonNode scope = read(rs.getString("scope_json"));
      List<ReopenScopeItem> items = new ArrayList<>();
      for (JsonNode i : scope.path("items")) {
        List<String> fields = new ArrayList<>();
        i.path("fields").forEach(x -> fields.add(x.asText()));
        items.add(
            new ReopenScopeItem(
                text(i, "table"),
                uuid(i, "profileId"),
                uuid(i, "unitId"),
                uuid(i, "recordId"),
                fields));
      }
      List<UUID> units = new ArrayList<>();
      scope.path("units").forEach(u -> units.add(UUID.fromString(u.asText())));
      JsonNode b = scope.path("basis");
      ReopenBasis basis =
          b.has("kind")
              ? new ReopenBasis(
                  ReopenBasis.Kind.valueOf(b.get("kind").asText()), uuid(b, "id"), text(b, "code"))
              : basisFromRef(rs.getString("basis_ref"));
      java.sql.Array ids = rs.getArray("scope_ids");
      List<UUID> scopeIds = new ArrayList<>();
      if (ids != null) {
        for (Object o : (Object[]) ids.getArray()) {
          scopeIds.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
        }
      }
      return new ReopenRequest(
          rs.getObject("id", UUID.class),
          rs.getObject("period_id", UUID.class),
          rs.getInt("period_bucket"),
          rs.getString("code"),
          ReopenScope.valueOf(rs.getString("scope")),
          scopeIds,
          items,
          units,
          basis,
          rs.getString("audit_kind"),
          rs.getString("reason"),
          rs.getObject("requested_by", UUID.class),
          rs.getInt("approvals_required"),
          ReopenStatus.valueOf(rs.getString("status")),
          scope.path("openWorkingDays").asInt(0),
          instant(rs, "open_until"),
          instant(rs, "executed_at"),
          instant(rs, "relocked_at"),
          text(scope.path("cancellation"), "reason"),
          instant(rs, "created_at"),
          rs.getInt("row_version"));
    };
  }

  /** Dòng tạo ngoài API (dữ liệu cũ) chỉ có {@code basis_ref} dạng {@code KIND:giá trị}. */
  private static ReopenBasis basisFromRef(String ref) {
    if (ref == null || ref.indexOf(':') < 0) {
      return new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, ref);
    }
    String kind = ref.substring(0, ref.indexOf(':'));
    String value = ref.substring(ref.indexOf(':') + 1);
    try {
      ReopenBasis.Kind k = ReopenBasis.Kind.valueOf(kind);
      try {
        return new ReopenBasis(k, UUID.fromString(value), null);
      } catch (IllegalArgumentException e) {
        return new ReopenBasis(k, null, value);
      }
    } catch (IllegalArgumentException e) {
      return new ReopenBasis(ReopenBasis.Kind.QUYET_DINH, null, ref);
    }
  }

  private String scopeJson(ReopenRequest r) {
    ObjectNode root = json.createObjectNode();
    ArrayNode items = root.putArray("items");
    for (ReopenScopeItem i : r.items()) {
      ObjectNode o = items.addObject();
      o.put("table", i.table());
      putUuid(o, "profileId", i.profileId());
      putUuid(o, "unitId", i.unitId());
      putUuid(o, "recordId", i.recordId());
      ArrayNode fields = o.putArray("fields");
      i.fields().forEach(fields::add);
    }
    ObjectNode basis = root.putObject("basis");
    basis.put("kind", r.basis().kind().name());
    putUuid(basis, "id", r.basis().id());
    if (r.basis().code() != null) {
      basis.put("code", r.basis().code());
    }
    ArrayNode units = root.putArray("units");
    r.units().forEach(u -> units.add(u.toString()));
    root.put("openWorkingDays", r.openWorkingDays());
    if (r.cancelReason() != null) {
      root.putObject("cancellation").put("reason", r.cancelReason());
    }
    try {
      return json.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không tuần tự hoá được scope_json", e);
    }
  }

  private JsonNode read(String s) {
    try {
      return s == null ? json.createObjectNode() : json.readTree(s);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("scope_json hỏng", e);
    }
  }

  private static void putUuid(ObjectNode o, String name, UUID v) {
    if (v != null) {
      o.put(name, v.toString());
    }
  }

  private static String text(JsonNode n, String name) {
    JsonNode v = n.get(name);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static UUID uuid(JsonNode n, String name) {
    String v = text(n, name);
    return v == null ? null : UUID.fromString(v);
  }

  private static String uuidArray(Collection<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
