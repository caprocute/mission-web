package vn.viettel.okrkpi.product.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.product.application.evidence.port.AcceptanceRepository;
import vn.viettel.okrkpi.product.domain.evidence.CriterionResult;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link AcceptanceRepository} trên {@code product.acceptance} (CORE-03-07). */
public class JdbcAcceptanceRepository implements AcceptanceRepository {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcAcceptanceRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void insertAcceptance(TenantId tenant, NewAcceptance a) {
    ArrayNode criteria = json.createArrayNode();
    for (CriterionResult c : a.criteria()) {
      ObjectNode n = criteria.addObject();
      n.put("criterionCode", c.code().strip());
      n.put("passed", c.passed());
      if (c.note() != null) {
        n.put("note", c.note());
      }
    }
    String criteriaJson;
    try {
      criteriaJson = json.writeValueAsString(criteria);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    jdbc.sql(
            "INSERT INTO product.acceptance (id, tenant_id, created_by, updated_by, period_id,"
                + " period_bucket, evidence_version_id, task_id, reviewer_id, decision,"
                + " quality_level, revision_cause, is_objective_revision, revision_count,"
                + " decided_at, criteria_results, comment_enc, dek_id, on_behalf_of,"
                + " delegation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,"
                + " ?, ?, ?, ?)")
        .params(
            a.id(),
            tenant.value(),
            a.reviewerId(),
            a.reviewerId(),
            a.periodId(),
            a.periodBucket(),
            a.versionId(),
            a.taskId(),
            a.reviewerId(),
            a.decision(),
            a.qualityLevel(),
            a.revisionCause(),
            a.objectiveRevision(),
            a.revisionCount(),
            Timestamp.from(a.decidedAt()),
            criteriaJson,
            a.comment() == null ? null : a.comment().cipher(),
            a.comment() == null ? null : a.comment().dekId(),
            a.onBehalfOf(),
            a.delegationId())
        .update();
  }

  @Override
  public Set<String> priorCriteria(
      TenantId tenant, int periodBucket, UUID evidenceId, int versionNo) {
    Set<String> codes = new HashSet<>();
    jdbc.sql(
            "SELECT a.criteria_results::text FROM product.acceptance a JOIN"
                + " product.evidence_version v ON v.period_bucket = a.period_bucket AND v.tenant_id"
                + " = a.tenant_id AND v.id = a.evidence_version_id WHERE a.period_bucket = ? AND"
                + " a.tenant_id = ? AND v.evidence_id = ? AND v.version_no < ?")
        .params(periodBucket, tenant.value(), evidenceId, versionNo)
        .query(String.class)
        .list()
        .forEach(
            text -> {
              try {
                for (JsonNode n : json.readTree(text)) {
                  if (n.hasNonNull("criterionCode")) {
                    codes.add(n.get("criterionCode").asText());
                  }
                }
              } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
              }
            });
    return codes;
  }

  @Override
  public int priorRevisions(TenantId tenant, int periodBucket, UUID evidenceId, int versionNo) {
    return jdbc.sql(
            "SELECT count(*) FROM product.evidence_version WHERE period_bucket = ? AND tenant_id ="
                + " ? AND evidence_id = ? AND version_no < ? AND status = 'YEU_CAU_BO_SUNG'")
        .params(periodBucket, tenant.value(), evidenceId, versionNo)
        .query(Integer.class)
        .single();
  }

  @Override
  public Optional<StoredAcceptance> latestOfVersion(
      TenantId tenant, int periodBucket, UUID versionId) {
    return jdbc.sql(
            "SELECT id, evidence_version_id, decision, quality_level, criteria_results::text AS"
                + " criteria, revision_cause, is_objective_revision, revision_count, comment_enc,"
                + " reviewer_id, decided_at, on_behalf_of, delegation_id FROM product.acceptance"
                + " WHERE period_bucket = ? AND tenant_id = ? AND evidence_version_id = ? ORDER BY"
                + " decided_at DESC LIMIT 1")
        .params(periodBucket, tenant.value(), versionId)
        .query(
            (rs, i) ->
                new StoredAcceptance(
                    rs.getObject("id", UUID.class),
                    rs.getObject("evidence_version_id", UUID.class),
                    rs.getString("decision"),
                    rs.getBigDecimal("quality_level"),
                    criteria(rs.getString("criteria")),
                    rs.getString("revision_cause"),
                    rs.getBoolean("is_objective_revision"),
                    rs.getInt("revision_count"),
                    rs.getBytes("comment_enc"),
                    rs.getObject("reviewer_id", UUID.class),
                    rs.getTimestamp("decided_at").toInstant(),
                    rs.getObject("on_behalf_of", UUID.class),
                    rs.getObject("delegation_id", UUID.class)))
        .optional();
  }

  private List<CriterionResult> criteria(String text) {
    List<CriterionResult> out = new ArrayList<>();
    if (text == null) {
      return out;
    }
    try {
      for (JsonNode n : json.readTree(text)) {
        out.add(
            new CriterionResult(
                n.path("criterionCode").asText(),
                n.path("passed").asBoolean(),
                n.hasNonNull("note") ? n.get("note").asText() : null));
      }
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }
}
