package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner.SignedHash;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishRepository;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest.Scope;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link PolicyPublishRepository} trên {@code policyDataSource} (CORE-05-09). */
public class JdbcPolicyPublishRepository implements PolicyPublishRepository {

  private static final TypeReference<List<Scope>> SCOPES = new TypeReference<>() {};

  private static final String COLUMNS =
      "id, policy_pack_version_id, period_id, scopes::text AS scopes, note, status, created_by,"
          + " created_at, decided_by, decided_at, decision_reason, row_version";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcPolicyPublishRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void insertRequest(TenantId tenant, PolicyPublishRequest r) {
    jdbc.sql(
            """
            INSERT INTO policy.publish_request
              (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
               policy_pack_version_id, period_id, scopes, note, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """)
        .param(r.id())
        .param(tenant.value())
        .param(Timestamp.from(r.submittedAt()))
        .param(r.submittedBy())
        .param(Timestamp.from(r.submittedAt()))
        .param(r.submittedBy())
        .param(r.rowVersion())
        .param(r.versionId())
        .param(r.periodId())
        .param(write(r.scopes()))
        .param(r.note())
        .param(r.status().name())
        .update();
  }

  @Override
  public Optional<PolicyPublishRequest> findPending(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy.publish_request WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " AND status = 'CHO_PHE_DUYET'")
        .param(tenant.value())
        .param(versionId)
        .query(this::request)
        .optional();
  }

  @Override
  public Optional<PolicyPublishRequest> findById(TenantId tenant, UUID requestId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM policy.publish_request WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(requestId)
        .query(this::request)
        .optional();
  }

  @Override
  public Optional<PolicyPublishRequest> findApproved(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy.publish_request WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " AND status = 'DA_DUYET' ORDER BY decided_at DESC LIMIT 1")
        .param(tenant.value())
        .param(versionId)
        .query(this::request)
        .optional();
  }

  @Override
  public boolean storeDecision(TenantId tenant, PolicyPublishRequest r, long expected) {
    return jdbc.sql(
                """
                UPDATE policy.publish_request
                   SET status = ?, decided_by = ?, decided_at = ?, decision_reason = ?,
                       updated_at = ?, updated_by = ?, row_version = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(r.status().name())
            .param(r.decidedBy())
            .param(Timestamp.from(r.decidedAt()))
            .param(r.decisionReason())
            .param(Timestamp.from(r.decidedAt()))
            .param(r.decidedBy())
            .param(r.rowVersion())
            .param(tenant.value())
            .param(r.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public boolean storeSubmitted(TenantId tenant, PolicyPackVersion v, long expected) {
    return jdbc.sql(
                """
                UPDATE policy.policy_pack_version
                   SET status = ?, effective_from = ?, updated_at = ?, updated_by = ?,
                       row_version = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(v.status().name())
            .param(v.effectiveFrom())
            .param(Timestamp.from(v.updatedAt()))
            .param(v.updatedBy())
            .param(v.rowVersion())
            .param(tenant.value())
            .param(v.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public boolean storeSignature(
      TenantId tenant, PolicyPackVersion v, SignedHash signed, long expected) {
    return jdbc.sql(
                """
                UPDATE policy.policy_pack_version
                   SET signature = ?, signer_cert_ref = ?, signed_at = ?, tsa_token = ?,
                       approved_by = ?, approved_at = ?, updated_at = ?, updated_by = ?,
                       row_version = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ? AND status = 'CHO_PHE_DUYET'
                   AND signature IS NULL AND created_by <> ?
                """)
            .param(signed.signature())
            .param(signed.certificateRef())
            .param(Timestamp.from(signed.signedAt()))
            .param(signed.tsaToken())
            .param(v.approvedBy())
            .param(Timestamp.from(v.approvedAt()))
            .param(Timestamp.from(v.updatedAt()))
            .param(v.updatedBy())
            .param(v.rowVersion())
            .param(tenant.value())
            .param(v.id())
            .param(expected)
            .param(v.approvedBy())
            .update()
        == 1;
  }

  private PolicyPublishRequest request(ResultSet rs, int i) throws SQLException {
    Timestamp decided = rs.getTimestamp("decided_at");
    return new PolicyPublishRequest(
        rs.getObject("id", UUID.class),
        rs.getObject("policy_pack_version_id", UUID.class),
        rs.getObject("period_id", UUID.class),
        read(rs.getString("scopes")),
        rs.getString("note"),
        PolicyPublishRequest.Status.valueOf(rs.getString("status")),
        rs.getObject("created_by", UUID.class),
        rs.getTimestamp("created_at").toInstant(),
        rs.getObject("decided_by", UUID.class),
        decided == null ? null : decided.toInstant(),
        rs.getString("decision_reason"),
        rs.getLong("row_version"));
  }

  private String write(List<Scope> scopes) {
    try {
      return json.writeValueAsString(scopes);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không ghi được phạm vi áp dụng", e);
    }
  }

  private List<Scope> read(String text) {
    try {
      return json.readValue(text, SCOPES);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không đọc được phạm vi áp dụng", e);
    }
  }
}
