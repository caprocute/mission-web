package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.BindingDraftStore;
import vn.viettel.okrkpi.workflow.domain.binding.BindingDraft;
import vn.viettel.okrkpi.workflow.domain.binding.BindingScope;
import vn.viettel.okrkpi.workflow.domain.binding.BindingStatus;
import vn.viettel.okrkpi.workflow.domain.binding.LintIssue;
import vn.viettel.okrkpi.workflow.domain.binding.LintResult;
import vn.viettel.okrkpi.workflow.domain.binding.TestSummary;
import vn.viettel.okrkpi.workflow.infrastructure.template.TemplateCatalog;

/**
 * {@link BindingDraftStore} trên {@code flowable.wf_binding_draft} (bảng bổ sung của DV-06-08) và
 * {@code flowable.wf_binding} (TL-03). Kích hoạt ghi đè dòng {@code wf_binding} cùng khoá (tenant,
 * loại quy trình, khối đối tượng, luồng, loại kỳ) và ghi phiên bản gói vào {@code
 * policy_pack_version_id} (V10, khoá duy nhất của TL-03 có cột này), nhớ giá trị cũ trong {@code
 * replaced_json} để gỡ kích hoạt trả lại đúng dòng cũ; dòng {@code wf_template} được tra theo mã và
 * phiên bản mẫu (mọi tenant đọc được), thiếu thì đăng ký cho tenant từ danh mục mẫu đóng gói.
 */
@Repository
public class JdbcBindingDraftStore implements BindingDraftStore {

  private static final String COLUMNS =
      "id, tenant_id, policy_pack_version_id, process_kind, template_code, template_version_no,"
          + " scope_json::text AS scope_json, params_json::text AS params_json,"
          + " lint_json::text AS lint_json, tests_json::text AS tests_json, status, row_version";
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TemplateCatalog catalog;
  private final RowMapper<BindingDraft> mapper = this::map;

  public JdbcBindingDraftStore(JdbcTemplate jdbc, ObjectMapper json, TemplateCatalog catalog) {
    this.jdbc = jdbc;
    this.json = json;
    this.catalog = catalog;
  }

  @Override
  public List<BindingDraft> list(UUID tenantId, UUID packVersionId) {
    TenantBinding.bind(jdbc, tenantId);
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM flowable.wf_binding_draft"
            + " WHERE tenant_id = ? AND policy_pack_version_id = ? ORDER BY process_kind",
        mapper,
        tenantId,
        packVersionId);
  }

  @Override
  public Optional<BindingDraft> find(UUID tenantId, UUID packVersionId, String processKind) {
    TenantBinding.bind(jdbc, tenantId);
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM flowable.wf_binding_draft"
                + " WHERE tenant_id = ? AND policy_pack_version_id = ? AND process_kind = ?",
            mapper,
            tenantId,
            packVersionId,
            processKind)
        .stream()
        .findFirst();
  }

  @Override
  public BindingDraft insert(BindingDraft d, UUID actor) {
    TenantBinding.bind(jdbc, d.tenantId());
    jdbc.update(
        "INSERT INTO flowable.wf_binding_draft (id, tenant_id, policy_pack_version_id, process_kind,"
            + " template_code, template_version_no, scope_json, params_json, lint_json, tests_json,"
            + " status, created_by, updated_by, row_version)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, 'NHAP', ?, ?, 1)",
        d.id(),
        d.tenantId(),
        d.packVersionId(),
        d.processKind(),
        d.templateKey(),
        d.templateVersionNo(),
        write(scope(d.scope())),
        write(d.params()),
        write(lint(d.lint())),
        write(tests(d.tests())),
        actor,
        actor);
    return find(d.tenantId(), d.packVersionId(), d.processKind()).orElseThrow();
  }

  @Override
  public Optional<BindingDraft> update(BindingDraft d, long expected, UUID actor) {
    TenantBinding.bind(jdbc, d.tenantId());
    int rows =
        jdbc.update(
            "UPDATE flowable.wf_binding_draft SET template_code = ?, template_version_no = ?,"
                + " scope_json = ?::jsonb, params_json = ?::jsonb, lint_json = ?::jsonb,"
                + " tests_json = ?::jsonb, updated_by = ?, updated_at = now(),"
                + " row_version = row_version + 1"
                + " WHERE id = ? AND tenant_id = ? AND row_version = ? AND status = 'NHAP'",
            d.templateKey(),
            d.templateVersionNo(),
            write(scope(d.scope())),
            write(d.params()),
            write(lint(d.lint())),
            write(tests(d.tests())),
            actor,
            d.id(),
            d.tenantId(),
            expected);
    return rows == 1 ? find(d.tenantId(), d.packVersionId(), d.processKind()) : Optional.empty();
  }

  @Override
  public List<BindingDraft> activate(UUID tenantId, UUID packVersionId, UUID actor) {
    TenantBinding.bind(jdbc, tenantId);
    List<Map<String, Object>> pending =
        jdbc.queryForList(
            "SELECT id, process_kind, template_code, template_version_no, scope_json::text AS scope,"
                + " params_json::text AS params FROM flowable.wf_binding_draft"
                + " WHERE tenant_id = ? AND policy_pack_version_id = ? AND status <> 'HIEU_LUC'"
                + " ORDER BY process_kind FOR UPDATE",
            tenantId,
            packVersionId);
    for (Map<String, Object> draft : pending) {
      activateOne(tenantId, packVersionId, draft, actor);
    }
    return list(tenantId, packVersionId);
  }

  private void activateOne(
      UUID tenantId, UUID packVersionId, Map<String, Object> draft, UUID actor) {
    UUID draftId = (UUID) draft.get("id");
    String kind = (String) draft.get("process_kind");
    JsonNode scope = read((String) draft.get("scope"));
    UUID subjectGroup = uuid(scope.path("subjectGroupId"));
    String flow = text(scope.path("flow"));
    String periodType = text(scope.path("periodType"));
    UUID templateId =
        templateRow(
            tenantId,
            (String) draft.get("template_code"),
            ((Number) draft.get("template_version_no")).intValue(),
            actor);
    ObjectNode bindingScope =
        ((ObjectNode) scope.deepCopy()).put("policyPackVersionId", packVersionId.toString());
    List<Map<String, Object>> existing =
        jdbc.queryForList(
            "SELECT id, wf_template_id, policy_pack_version_id, scope_json::text AS scope,"
                + " params_json::text AS params FROM flowable.wf_binding WHERE tenant_id = ? AND process_kind = ?"
                + " AND subject_group_id IS NOT DISTINCT FROM ? AND flow IS NOT DISTINCT FROM ?"
                + " AND period_type IS NOT DISTINCT FROM ? FOR UPDATE",
            tenantId,
            kind,
            subjectGroup,
            flow,
            periodType);
    UUID bindingId;
    String replaced = null;
    if (existing.isEmpty()) {
      bindingId = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO flowable.wf_binding (id, tenant_id, policy_pack_version_id, process_kind,"
              + " subject_group_id, flow, period_type, wf_template_id, scope_json, params_json,"
              + " created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
          bindingId,
          tenantId,
          packVersionId,
          kind,
          subjectGroup,
          flow,
          periodType,
          templateId,
          bindingScope.toString(),
          draft.get("params"),
          actor,
          actor);
    } else {
      Map<String, Object> row = existing.get(0);
      bindingId = (UUID) row.get("id");
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("wfTemplateId", row.get("wf_template_id").toString());
      Object oldVersion = row.get("policy_pack_version_id");
      snapshot.put("policyPackVersionId", oldVersion == null ? null : oldVersion.toString());
      snapshot.put("scope", row.get("scope") == null ? null : read((String) row.get("scope")));
      snapshot.put("params", row.get("params") == null ? null : read((String) row.get("params")));
      List<UUID> previous =
          jdbc.queryForList(
              "SELECT id FROM flowable.wf_binding_draft WHERE tenant_id = ? AND wf_binding_id = ?"
                  + " AND status = 'HIEU_LUC' AND id <> ?",
              UUID.class,
              tenantId,
              bindingId,
              draftId);
      snapshot.put("draftId", previous.isEmpty() ? null : previous.get(0).toString());
      replaced = write(snapshot);
      jdbc.update(
          "UPDATE flowable.wf_binding SET wf_template_id = ?, policy_pack_version_id = ?,"
              + " scope_json = ?::jsonb, params_json = ?::jsonb, updated_by = ?, updated_at = now(),"
              + " row_version = row_version + 1 WHERE id = ? AND tenant_id = ?",
          templateId,
          packVersionId,
          bindingScope.toString(),
          draft.get("params"),
          actor,
          bindingId,
          tenantId);
      for (UUID other : previous) {
        jdbc.update(
            // Giữ replaced_json của dòng bị thay: gỡ phiên bản mới thì dòng này hiệu lực lại.
            "UPDATE flowable.wf_binding_draft SET status = 'NGUNG', wf_binding_id = NULL,"
                + " updated_by = ?, updated_at = now(),"
                + " row_version = row_version + 1 WHERE id = ? AND tenant_id = ?",
            actor,
            other,
            tenantId);
      }
    }
    jdbc.update(
        "UPDATE flowable.wf_binding_draft SET status = 'HIEU_LUC', wf_binding_id = ?,"
            + " replaced_json = ?::jsonb, activated_at = now(), updated_by = ?, updated_at = now(),"
            + " row_version = row_version + 1 WHERE id = ? AND tenant_id = ?",
        bindingId,
        replaced,
        actor,
        draftId,
        tenantId);
  }

  @Override
  public List<BindingDraft> deactivate(UUID tenantId, UUID packVersionId, UUID actor) {
    TenantBinding.bind(jdbc, tenantId);
    List<Map<String, Object>> active =
        jdbc.queryForList(
            "SELECT id, wf_binding_id, replaced_json::text AS replaced"
                + " FROM flowable.wf_binding_draft WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " AND status = 'HIEU_LUC' ORDER BY process_kind FOR UPDATE",
            tenantId,
            packVersionId);
    for (Map<String, Object> draft : active) {
      UUID bindingId = (UUID) draft.get("wf_binding_id");
      String replaced = (String) draft.get("replaced");
      if (replaced == null) {
        jdbc.update(
            "DELETE FROM flowable.wf_binding WHERE id = ? AND tenant_id = ?", bindingId, tenantId);
      } else {
        JsonNode old = read(replaced);
        jdbc.update(
            "UPDATE flowable.wf_binding SET wf_template_id = ?, policy_pack_version_id = ?,"
                + " scope_json = ?::jsonb, params_json = ?::jsonb, updated_by = ?, updated_at = now(),"
                + " row_version = row_version + 1 WHERE id = ? AND tenant_id = ?",
            UUID.fromString(old.path("wfTemplateId").asText()),
            uuid(old.path("policyPackVersionId")),
            nullableJson(old.path("scope")),
            nullableJson(old.path("params")),
            actor,
            bindingId,
            tenantId);
        UUID previous = uuid(old.path("draftId"));
        if (previous != null) {
          jdbc.update(
              "UPDATE flowable.wf_binding_draft SET status = 'HIEU_LUC', wf_binding_id = ?,"
                  + " updated_by = ?, updated_at = now(), row_version = row_version + 1"
                  + " WHERE id = ? AND tenant_id = ? AND status = 'NGUNG'",
              bindingId,
              actor,
              previous,
              tenantId);
        }
      }
      jdbc.update(
          "UPDATE flowable.wf_binding_draft SET status = 'NGUNG', wf_binding_id = NULL,"
              + " replaced_json = NULL, updated_by = ?, updated_at = now(),"
              + " row_version = row_version + 1 WHERE id = ? AND tenant_id = ?",
          actor,
          draft.get("id"),
          tenantId);
    }
    return list(tenantId, packVersionId);
  }

  /** Dòng {@code wf_template} của mẫu; thiếu thì đăng ký cho tenant từ danh mục đóng gói. */
  private UUID templateRow(UUID tenantId, String code, int versionNo, UUID actor) {
    List<UUID> found =
        jdbc.queryForList(
            "SELECT id FROM flowable.wf_template WHERE template_code = ? AND version_no = ?"
                + " AND status = 'HIEU_LUC' ORDER BY (tenant_id = ?) DESC, created_at LIMIT 1",
            UUID.class,
            code,
            versionNo,
            tenantId);
    if (!found.isEmpty()) {
      return found.get(0);
    }
    String xml = catalog.bpmnResources().get("processes/" + code + ".bpmn20.xml");
    if (xml == null) {
      throw new IllegalStateException("Mẫu luồng không có trong danh mục: " + code);
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO flowable.wf_template (id, tenant_id, template_code, version_no, bpmn_xml,"
            + " bpmn_sha256, status, created_by, updated_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, 'HIEU_LUC', ?, ?)",
        id,
        tenantId,
        code,
        versionNo,
        xml,
        sha256(xml),
        actor,
        actor);
    return id;
  }

  private BindingDraft map(ResultSet rs, int row) throws SQLException {
    JsonNode scope = read(rs.getString("scope_json"));
    JsonNode lint = read(rs.getString("lint_json"));
    JsonNode tests = read(rs.getString("tests_json"));
    List<LintIssue> issues = new ArrayList<>();
    lint.path("issues")
        .forEach(
            i ->
                issues.add(
                    new LintIssue(
                        i.path("rule").asText(),
                        text(i.path("elementId")),
                        i.path("severity").asText(LintIssue.ERROR),
                        i.path("message").asText())));
    return new BindingDraft(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("policy_pack_version_id", UUID.class),
        rs.getString("process_kind"),
        rs.getString("template_code"),
        rs.getInt("template_version_no"),
        new BindingScope(
            text(scope.path("flow")),
            uuid(scope.path("subjectGroupId")),
            text(scope.path("periodType")),
            uuid(scope.path("fromPeriodId"))),
        json.convertValue(read(rs.getString("params_json")), MAP),
        new LintResult(lint.path("passed").asBoolean(true), issues),
        tests.isObject()
            ? new TestSummary(tests.path("passed").asInt(), tests.path("total").asInt())
            : null,
        BindingStatus.valueOf(rs.getString("status")),
        rs.getLong("row_version"));
  }

  private static Map<String, Object> scope(BindingScope s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("flow", s.flow());
    m.put("subjectGroupId", s.subjectGroupId() == null ? null : s.subjectGroupId().toString());
    m.put("periodType", s.periodType());
    m.put("fromPeriodId", s.fromPeriodId().toString());
    return m;
  }

  private static Map<String, Object> lint(LintResult lint) {
    List<Map<String, Object>> issues = new ArrayList<>();
    for (LintIssue i : lint.issues()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rule", i.rule());
      m.put("elementId", i.elementId());
      m.put("severity", i.severity());
      m.put("message", i.message());
      issues.add(m);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("passed", lint.passed());
    m.put("issues", issues);
    return m;
  }

  private static Map<String, Object> tests(TestSummary t) {
    return t == null ? Map.of() : Map.of("passed", t.passed(), "total", t.total());
  }

  private String nullableJson(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() ? null : node.toString();
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Không ghi được tham số luồng", e);
    }
  }

  private JsonNode read(String raw) {
    try {
      return raw == null ? json.nullNode() : json.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Dữ liệu tham số luồng hỏng", e);
    }
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
  }

  private static UUID uuid(JsonNode node) {
    String v = text(node);
    return v == null || v.isBlank() ? null : UUID.fromString(v);
  }

  private static byte[] sha256(String xml) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(xml.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
