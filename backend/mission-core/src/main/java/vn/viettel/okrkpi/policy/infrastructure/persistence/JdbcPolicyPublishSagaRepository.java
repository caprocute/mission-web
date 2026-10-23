package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishSagaRepository;
import vn.viettel.okrkpi.policy.domain.PolicyBinding;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest.Scope;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga.Context;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link PolicyPublishSagaRepository} trên {@code policyDataSource} (CORE-05-10). */
public class JdbcPolicyPublishSagaRepository implements PolicyPublishSagaRepository {

  private static final String COLUMNS =
      "id, policy_pack_version_id, publish_request_id, status, current_step_no, attempts,"
          + " last_error, context::text AS context, created_by, started_at, finished_at,"
          + " updated_by, updated_at, row_version";

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final PolicyPackRepository packs;

  public JdbcPolicyPublishSagaRepository(
      JdbcClient jdbc, ObjectMapper json, PolicyPackRepository packs) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
    this.packs = Objects.requireNonNull(packs, "packs");
  }

  @Override
  public void insert(TenantId tenant, PolicyPublishSaga s) {
    jdbc.sql(
            """
            INSERT INTO policy.publish_saga
              (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
               policy_pack_version_id, publish_request_id, status, current_step_no, step_code,
               attempts, last_error, context, started_at, finished_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """)
        .param(s.id())
        .param(tenant.value())
        .param(Timestamp.from(s.startedAt()))
        .param(s.startedBy())
        .param(Timestamp.from(s.updatedAt()))
        .param(s.updatedBy())
        .param(s.rowVersion())
        .param(s.versionId())
        .param(s.requestId())
        .param(s.status().name())
        .param(s.step().no())
        .param(s.step().name())
        .param(s.attempts())
        .param(s.lastError())
        .param(write(s.context()))
        .param(Timestamp.from(s.startedAt()))
        .param(s.finishedAt() == null ? null : Timestamp.from(s.finishedAt()))
        .update();
  }

  @Override
  public boolean store(TenantId tenant, PolicyPublishSaga s, long expected) {
    return jdbc.sql(
                """
                UPDATE policy.publish_saga
                   SET status = ?, current_step_no = ?, step_code = ?, attempts = ?,
                       last_error = ?, context = ?::jsonb, finished_at = ?, updated_at = ?,
                       updated_by = ?, row_version = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(s.status().name())
            .param(s.step().no())
            .param(s.step().name())
            .param(s.attempts())
            .param(s.lastError())
            .param(write(s.context()))
            .param(s.finishedAt() == null ? null : Timestamp.from(s.finishedAt()))
            .param(Timestamp.from(s.updatedAt()))
            .param(s.updatedBy())
            .param(s.rowVersion())
            .param(tenant.value())
            .param(s.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public Optional<PolicyPublishSaga> find(TenantId tenant, UUID sagaId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM policy.publish_saga WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(sagaId)
        .query(this::saga)
        .optional();
  }

  @Override
  public Optional<PolicyPublishSaga> findLatest(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy.publish_saga WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " ORDER BY started_at DESC, id DESC LIMIT 1")
        .param(tenant.value())
        .param(versionId)
        .query(this::saga)
        .optional();
  }

  @Override
  public Optional<BindingRow> findBinding(TenantId tenant, UUID periodId, Scope scope) {
    return jdbc.sql(
            """
            SELECT id, policy_pack_version_id, is_frozen FROM policy.policy_binding
             WHERE tenant_id = ? AND period_id = ? AND flow = ?
               AND subject_group_id IS NOT DISTINCT FROM ?
               AND org_unit_id IS NOT DISTINCT FROM ?
            """)
        .param(tenant.value())
        .param(periodId)
        .param(scope.flow())
        .param(scope.subjectGroupId())
        .param(scope.orgUnitId())
        .query(
            (rs, i) ->
                new BindingRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("policy_pack_version_id", UUID.class),
                    rs.getBoolean("is_frozen")))
        .optional();
  }

  @Override
  public void insertBinding(
      TenantId tenant, UUID bindingId, UUID versionId, UUID periodId, Scope scope, UUID actor) {
    jdbc.sql(
            """
            INSERT INTO policy.policy_binding
              (id, tenant_id, created_by, updated_by, policy_pack_version_id, period_id, flow,
               subject_group_id, org_unit_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .param(bindingId)
        .param(tenant.value())
        .param(actor)
        .param(actor)
        .param(versionId)
        .param(periodId)
        .param(scope.flow())
        .param(scope.subjectGroupId())
        .param(scope.orgUnitId())
        .update();
  }

  @Override
  public boolean rebind(TenantId tenant, UUID bindingId, UUID versionId, UUID actor) {
    return jdbc.sql(
                """
                UPDATE policy.policy_binding
                   SET policy_pack_version_id = ?, updated_at = now(), updated_by = ?,
                       row_version = row_version + 1
                 WHERE tenant_id = ? AND id = ? AND NOT is_frozen
                """)
            .param(versionId)
            .param(actor)
            .param(tenant.value())
            .param(bindingId)
            .update()
        == 1;
  }

  @Override
  public boolean unbind(TenantId tenant, UUID bindingId) {
    return jdbc.sql(
                "DELETE FROM policy.policy_binding WHERE tenant_id = ? AND id = ? AND NOT is_frozen")
            .param(tenant.value())
            .param(bindingId)
            .update()
        == 1;
  }

  @Override
  public List<PolicyBinding> bindings(TenantId tenant, UUID versionId) {
    return packs.bindings(tenant, versionId);
  }

  private PolicyPublishSaga saga(ResultSet rs, int i) throws SQLException {
    Timestamp finished = rs.getTimestamp("finished_at");
    return new PolicyPublishSaga(
        rs.getObject("id", UUID.class),
        rs.getObject("policy_pack_version_id", UUID.class),
        rs.getObject("publish_request_id", UUID.class),
        PolicyPublishSaga.Status.valueOf(rs.getString("status")),
        PolicyPublishSaga.Step.of(rs.getInt("current_step_no")),
        rs.getInt("attempts"),
        rs.getString("last_error"),
        read(rs.getString("context")),
        rs.getObject("created_by", UUID.class),
        rs.getTimestamp("started_at").toInstant(),
        finished == null ? null : finished.toInstant(),
        rs.getObject("updated_by", UUID.class),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getLong("row_version"));
  }

  private String write(Context context) {
    try {
      return json.writeValueAsString(context);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không ghi được ngữ cảnh saga", e);
    }
  }

  private Context read(String text) {
    try {
      Context c = json.readValue(text, Context.class);
      return c == null ? Context.empty() : c;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không đọc được ngữ cảnh saga", e);
    }
  }
}
