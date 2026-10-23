package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.port.WorkflowTemplateStore;
import vn.viettel.okrkpi.workflow.domain.template.TemplateStatus;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;

/**
 * {@link WorkflowTemplateStore} trên {@code flowable.wf_template} (TL-03, cột bổ sung BS-01 ở V7).
 * Mọi tenant đọc được bảng; chỉ ghi dòng của tenant trong ngữ cảnh (RLS).
 */
public class JdbcWorkflowTemplateStore implements WorkflowTemplateStore {

  private static final String COLUMNS =
      "id, tenant_id, template_code, version_no, status, bpmn_xml, bpmn_sha256,"
          + " param_schema::text AS param_schema, test_suite_ref, policy_pack_version_id,"
          + " flowable_deployment_id, approved_by, signature, created_by, updated_by, updated_at,"
          + " row_version";
  private static final String CURSOR_KEY =
      "(template_code || '@' || lpad((999999 - version_no)::text, 6, '0'))";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final RowMapper<TemplateVersion> mapper = this::map;

  public JdbcWorkflowTemplateStore(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public Optional<TemplateVersion> find(UUID id) {
    bind();
    return jdbc
        .query("SELECT " + COLUMNS + " FROM flowable.wf_template WHERE id = ?", mapper, id)
        .stream()
        .findFirst();
  }

  @Override
  public int maxVersion(String templateCode) {
    bind();
    Integer max =
        jdbc.queryForObject(
            "SELECT coalesce(max(version_no), 0) FROM flowable.wf_template WHERE template_code = ?",
            Integer.class,
            templateCode);
    return max == null ? 0 : max;
  }

  @Override
  public boolean hasOpenDraft(String templateCode) {
    bind();
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM flowable.wf_template WHERE template_code = ?"
                + " AND status IN ('NHAP', 'DAT_KIEM_THU', 'CHO_PHE_DUYET')",
            Integer.class,
            templateCode);
    return n != null && n > 0;
  }

  @Override
  public Optional<TemplateVersion> findReleased(String templateCode, int versionNo) {
    bind();
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM flowable.wf_template WHERE template_code = ? AND version_no = ?"
                + " AND status IN ('HIEU_LUC', 'NGUNG') ORDER BY updated_at DESC LIMIT 1",
            mapper,
            templateCode,
            versionNo)
        .stream()
        .findFirst();
  }

  @Override
  public List<TemplateVersion> page(TemplateStatus status, String q, String afterKey, int limit) {
    bind();
    StringBuilder sql =
        new StringBuilder("SELECT " + COLUMNS + " FROM flowable.wf_template WHERE true");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status.name());
    }
    if (q != null && !q.isBlank()) {
      sql.append(" AND template_code ILIKE ?");
      args.add("%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    }
    if (afterKey != null && !afterKey.isBlank()) {
      sql.append(" AND ").append(CURSOR_KEY).append(" > ?");
      args.add(afterKey);
    }
    sql.append(" ORDER BY template_code, version_no DESC, id LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), mapper, args.toArray());
  }

  @Override
  public Map<UUID, Integer> testCounts(Collection<UUID> templateIds) {
    if (templateIds.isEmpty()) {
      return Map.of();
    }
    bind();
    Map<UUID, Integer> result = new HashMap<>();
    jdbc.query(
        "SELECT DISTINCT ON (wf_template_id) wf_template_id, total_cases"
            + " FROM flowable.wf_template_test_run WHERE wf_template_id IN ("
            + placeholders(templateIds.size())
            + ") AND status IN ('HOAN_TAT', 'THAT_BAI') ORDER BY wf_template_id, created_at DESC",
        rs -> {
          result.put(rs.getObject(1, UUID.class), rs.getInt(2));
        },
        templateIds.toArray());
    return result;
  }

  @Override
  public Map<UUID, String> mergeRequestUrls(Collection<UUID> templateIds) {
    if (templateIds.isEmpty()) {
      return Map.of();
    }
    bind();
    Map<UUID, String> result = new HashMap<>();
    jdbc.query(
        "SELECT wf_template_id, merge_request_url FROM flowable.wf_template_release"
            + " WHERE merge_request_url IS NOT NULL AND wf_template_id IN ("
            + placeholders(templateIds.size())
            + ")",
        rs -> {
          result.put(rs.getObject(1, UUID.class), rs.getString(2));
        },
        templateIds.toArray());
    return result;
  }

  @Override
  public TemplateVersion insert(TemplateVersion v) {
    TenantBinding.bind(jdbc, v.tenantId());
    jdbc.update(
        "INSERT INTO flowable.wf_template (id, tenant_id, template_code, version_no, bpmn_xml,"
            + " bpmn_sha256, policy_pack_version_id, status, param_schema, test_suite_ref,"
            + " created_by, updated_by, row_version)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 0)",
        v.id(),
        v.tenantId(),
        v.templateCode(),
        v.versionNo(),
        v.bpmnXml(),
        v.bpmnSha256(),
        v.policyPackVersionId(),
        v.status().name(),
        write(v.paramSchema()),
        v.testSuiteRef(),
        v.createdBy(),
        v.updatedBy());
    return find(v.id()).orElseThrow();
  }

  @Override
  public Optional<TemplateVersion> update(TemplateVersion v, long expectedRowVersion) {
    TenantBinding.bind(jdbc, v.tenantId());
    int rows =
        jdbc.update(
            "UPDATE flowable.wf_template SET status = ?, bpmn_xml = ?, bpmn_sha256 = ?,"
                + " param_schema = ?::jsonb, test_suite_ref = ?, approved_by = ?, signature = ?,"
                + " flowable_deployment_id = ?, updated_by = ?, updated_at = now(),"
                + " row_version = row_version + 1"
                + " WHERE id = ? AND tenant_id = ? AND row_version = ?",
            v.status().name(),
            v.bpmnXml(),
            v.bpmnSha256(),
            write(v.paramSchema()),
            v.testSuiteRef(),
            v.approvedBy(),
            v.signature(),
            v.flowableDeploymentId(),
            v.updatedBy(),
            v.id(),
            v.tenantId(),
            expectedRowVersion);
    return rows == 1 ? find(v.id()) : Optional.empty();
  }

  private void bind() {
    TenantBinding.bind(jdbc, TenantContext.require().value());
  }

  static String placeholders(int n) {
    return String.join(", ", java.util.Collections.nCopies(n, "?"));
  }

  private TemplateVersion map(ResultSet rs, int row) throws SQLException {
    Timestamp updated = rs.getTimestamp("updated_at");
    return new TemplateVersion(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("template_code"),
        rs.getInt("version_no"),
        TemplateStatus.valueOf(rs.getString("status")),
        rs.getString("bpmn_xml"),
        rs.getBytes("bpmn_sha256"),
        read(rs.getString("param_schema")),
        rs.getString("test_suite_ref"),
        rs.getObject("policy_pack_version_id", UUID.class),
        rs.getString("flowable_deployment_id"),
        rs.getObject("approved_by", UUID.class),
        rs.getBytes("signature"),
        rs.getObject("created_by", UUID.class),
        rs.getObject("updated_by", UUID.class),
        updated == null ? null : updated.toInstant(),
        rs.getLong("row_version"));
  }

  private Map<String, Object> read(String text) {
    if (text == null) {
      return Map.of();
    }
    try {
      return json.readValue(text, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("param_schema không đọc được", e);
    }
  }

  private String write(Map<String, Object> value) {
    try {
      return json.writeValueAsString(value == null ? Map.of() : value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("param_schema không ghi được", e);
    }
  }
}
