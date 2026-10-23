package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.oversight.application.port.ReopenProgressRepository;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenFollowUp;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenProgress;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho tiến độ hậu kiểm (CORE-07-09): {@code oversight.reopen_progress} và khoá {@code followUp} của
 * {@code oversight.reopen_request.scope_json} ({@code seededAt}, {@code completedAt}, {@code
 * relockRequestedAt}, {@code incompleteAt}, {@code pendingProfiles}). Ghi {@code followUp} bằng
 * phép gộp JSON nên các khoá khác của {@code scope_json} giữ nguyên.
 */
public class JdbcReopenProgressRepository implements ReopenProgressRepository {

  private static final String COLUMNS =
      "profile_id, recomputed_at, score_result_id, forms_json::text AS forms_json, completed_at";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcReopenProgressRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public List<UUID> executedNotSeeded(TenantId tenant, int limit) {
    return jdbc.sql(
            """
            SELECT id FROM oversight.reopen_request
             WHERE tenant_id = ? AND status = 'DA_THUC_HIEN' AND relocked_at IS NULL
               AND (scope_json #>> '{followUp,seededAt}') IS NULL
             ORDER BY executed_at, id LIMIT ?
            """)
        .param(tenant.value())
        .param(limit)
        .query(UUID.class)
        .list();
  }

  @Override
  public void seed(TenantId tenant, ReopenRequest r, Collection<UUID> profileIds, UUID actorId) {
    for (UUID profile : profileIds) {
      jdbc.sql(
              """
              INSERT INTO oversight.reopen_progress (id, created_by, updated_by, period_id,
                     period_bucket, reopen_request_id, profile_id)
              VALUES (?, ?, ?, ?, ?, ?, ?)
              ON CONFLICT (period_bucket, tenant_id, reopen_request_id, profile_id) DO NOTHING
              """)
          .param(Ids.newUuidV7())
          .param(actorId)
          .param(actorId)
          .param(r.periodId())
          .param(r.periodBucket())
          .param(r.id())
          .param(profile)
          .update();
    }
    ObjectNode f = json.createObjectNode();
    f.put("seededAt", Instant.now().toString());
    f.put("profiles", profileIds.size());
    mergeFollowUp(tenant, r, f, actorId);
  }

  @Override
  public List<ReopenProgress> of(TenantId tenant, ReopenRequest r) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM oversight.reopen_progress WHERE period_bucket = ? AND tenant_id = ?"
                + " AND reopen_request_id = ? ORDER BY profile_id")
        .param(r.periodBucket())
        .param(tenant.value())
        .param(r.id())
        .query(
            (rs, n) ->
                new ReopenProgress(
                    rs.getObject("profile_id", UUID.class),
                    instant(rs.getTimestamp("recomputed_at")),
                    rs.getObject("score_result_id", UUID.class),
                    forms(rs.getString("forms_json")),
                    instant(rs.getTimestamp("completed_at"))))
        .list();
  }

  @Override
  public List<UUID> openRequestsOf(TenantId tenant, UUID profileId) {
    return jdbc.sql(
            """
            SELECT r.id FROM oversight.reopen_progress p
              JOIN oversight.reopen_request r ON r.period_bucket = p.period_bucket
                   AND r.tenant_id = p.tenant_id AND r.id = p.reopen_request_id
             WHERE p.tenant_id = ? AND p.profile_id = ?
               AND r.status = 'DA_THUC_HIEN' AND r.relocked_at IS NULL
             ORDER BY r.executed_at, r.id
            """)
        .param(tenant.value())
        .param(profileId)
        .query(UUID.class)
        .list();
  }

  @Override
  public void save(TenantId tenant, ReopenRequest r, ReopenProgress p, UUID actorId) {
    jdbc.sql(
            """
            UPDATE oversight.reopen_progress
               SET recomputed_at = ?, score_result_id = ?, forms_json = ?::jsonb, completed_at = ?,
                   row_version = row_version + 1, updated_at = now(), updated_by = ?
             WHERE period_bucket = ? AND tenant_id = ? AND reopen_request_id = ? AND profile_id = ?
            """)
        .param(ts(p.recomputedAt()))
        .param(p.scoreResultId())
        .param(formsJson(p.forms()))
        .param(ts(p.completedAt()))
        .param(actorId)
        .param(r.periodBucket())
        .param(tenant.value())
        .param(r.id())
        .param(p.profileId())
        .update();
  }

  @Override
  public ReopenFollowUp followUp(TenantId tenant, ReopenRequest r) {
    String raw =
        jdbc.sql(
                "SELECT (scope_json -> 'followUp')::text FROM oversight.reopen_request"
                    + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
            .param(r.periodBucket())
            .param(tenant.value())
            .param(r.id())
            .query(String.class)
            .optional()
            .orElse(null);
    if (raw == null) {
      return ReopenFollowUp.NONE;
    }
    JsonNode n = read(raw);
    return new ReopenFollowUp(
        instant(n, "completedAt"),
        instant(n, "relockRequestedAt"),
        instant(n, "incompleteAt"),
        n.path("pendingProfiles").asInt(0));
  }

  @Override
  public void saveFollowUp(TenantId tenant, ReopenRequest r, ReopenFollowUp f, UUID actorId) {
    ObjectNode o = json.createObjectNode();
    put(o, "completedAt", f.completedAt());
    put(o, "relockRequestedAt", f.relockRequestedAt());
    put(o, "incompleteAt", f.incompleteAt());
    o.put("pendingProfiles", f.pendingProfiles());
    mergeFollowUp(tenant, r, o, actorId);
  }

  private void mergeFollowUp(TenantId tenant, ReopenRequest r, ObjectNode f, UUID actorId) {
    jdbc.sql(
            """
            UPDATE oversight.reopen_request
               SET scope_json = jsonb_set(scope_json, '{followUp}',
                       coalesce(scope_json -> 'followUp', '{}'::jsonb) || ?::jsonb),
                   updated_at = now(), updated_by = ?
             WHERE period_bucket = ? AND tenant_id = ? AND id = ?
            """)
        .param(write(f))
        .param(actorId)
        .param(r.periodBucket())
        .param(tenant.value())
        .param(r.id())
        .update();
  }

  private Map<UUID, ReopenProgress.Form> forms(String raw) {
    Map<UUID, ReopenProgress.Form> out = new LinkedHashMap<>();
    JsonNode n = read(raw == null ? "{}" : raw);
    Iterator<Map.Entry<String, JsonNode>> it = n.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode v = e.getValue();
      out.put(
          UUID.fromString(e.getKey()),
          new ReopenProgress.Form(
              v.path("code").asText(null), instant(v, "generatedAt"), instant(v, "signedAt")));
    }
    return out;
  }

  private String formsJson(Map<UUID, ReopenProgress.Form> forms) {
    ObjectNode root = json.createObjectNode();
    forms.forEach(
        (id, f) -> {
          ObjectNode o = root.putObject(id.toString());
          o.put("code", f.code());
          put(o, "generatedAt", f.generatedAt());
          put(o, "signedAt", f.signedAt());
        });
    return write(root);
  }

  private String write(JsonNode n) {
    try {
      return json.writeValueAsString(n);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không tuần tự hoá được tiến độ hậu kiểm", e);
    }
  }

  private JsonNode read(String s) {
    try {
      return json.readTree(s);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Tiến độ hậu kiểm hỏng", e);
    }
  }

  private static void put(ObjectNode o, String name, Instant v) {
    if (v != null) {
      o.put(name, v.toString());
    }
  }

  private static Instant instant(JsonNode n, String name) {
    JsonNode v = n.get(name);
    return v == null || v.isNull() ? null : Instant.parse(v.asText());
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
