package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.port.TemplateTestRunStore;
import vn.viettel.okrkpi.workflow.domain.template.TestCaseResult;
import vn.viettel.okrkpi.workflow.domain.template.TestReport;
import vn.viettel.okrkpi.workflow.domain.template.TestRun;
import vn.viettel.okrkpi.workflow.domain.template.TestRunStatus;

/** {@link TemplateTestRunStore} trên {@code flowable.wf_template_test_run} (bảng bổ sung V7). */
public class JdbcTemplateTestRunStore implements TemplateTestRunStore {

  private static final String COLUMNS =
      "id, tenant_id, wf_template_id, bpmn_sha256, status, binding_count, sql_budget,"
          + " results_json::text AS results_json, created_by, updated_at";
  private static final TypeReference<List<TestCaseResult>> CASES = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final RowMapper<TestRun> mapper = this::map;
  private final int defaultBudget;

  public JdbcTemplateTestRunStore(JdbcTemplate jdbc, ObjectMapper json, int defaultBudget) {
    this.jdbc = jdbc;
    this.json = json;
    this.defaultBudget = defaultBudget;
  }

  @Override
  public void insert(TestRun run) {
    TenantBinding.bind(jdbc, run.tenantId());
    jdbc.update(
        "INSERT INTO flowable.wf_template_test_run (id, tenant_id, wf_template_id, bpmn_sha256,"
            + " status, sql_budget, created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        run.id(),
        run.tenantId(),
        run.templateId(),
        run.bpmnSha256(),
        run.status().name(),
        defaultBudget,
        run.requestedBy(),
        run.requestedBy());
  }

  @Override
  public Optional<TestRun> find(UUID id) {
    bind();
    return jdbc
        .query("SELECT " + COLUMNS + " FROM flowable.wf_template_test_run WHERE id = ?", mapper, id)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TestRun> active(UUID templateId, byte[] sha256) {
    bind();
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM flowable.wf_template_test_run WHERE wf_template_id = ? AND bpmn_sha256 = ?"
                + " AND status IN ('CHO', 'DANG_CHAY') ORDER BY created_at DESC LIMIT 1",
            mapper,
            templateId,
            sha256)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<TestRun> latestFinished(UUID templateId) {
    bind();
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM flowable.wf_template_test_run WHERE wf_template_id = ?"
                + " AND status IN ('HOAN_TAT', 'THAT_BAI') ORDER BY created_at DESC LIMIT 1",
            mapper,
            templateId)
        .stream()
        .findFirst();
  }

  @Override
  public void markRunning(UUID id) {
    bind();
    jdbc.update(
        "UPDATE flowable.wf_template_test_run SET status = 'DANG_CHAY', updated_at = now(),"
            + " row_version = row_version + 1 WHERE id = ? AND status = 'CHO'",
        id);
  }

  @Override
  public void finish(UUID id, TestRunStatus status, TestReport report) {
    bind();
    jdbc.update(
        "UPDATE flowable.wf_template_test_run SET status = ?, total_cases = ?, passed_cases = ?,"
            + " binding_count = ?, max_sql_per_completion = ?, sql_budget = ?,"
            + " results_json = ?::jsonb, updated_at = now(), row_version = row_version + 1"
            + " WHERE id = ?",
        status.name(),
        report.total(),
        report.passedCount(),
        report.bindingCount(),
        report.maxSqlPerCompletion(),
        report.sqlBudget(),
        write(report.cases()),
        id);
  }

  private void bind() {
    TenantBinding.bind(jdbc, TenantContext.require().value());
  }

  private TestRun map(ResultSet rs, int row) throws SQLException {
    TestRunStatus status = TestRunStatus.valueOf(rs.getString("status"));
    TestReport report =
        status.terminal()
            ? new TestReport(
                read(rs.getString("results_json")),
                rs.getInt("binding_count"),
                rs.getInt("sql_budget"))
            : null;
    Timestamp updated = rs.getTimestamp("updated_at");
    return new TestRun(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("wf_template_id", UUID.class),
        rs.getBytes("bpmn_sha256"),
        status,
        report,
        rs.getObject("created_by", UUID.class),
        updated == null ? null : updated.toInstant());
  }

  private List<TestCaseResult> read(String text) {
    try {
      return text == null ? List.of() : json.readValue(text, CASES);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("results_json không đọc được", e);
    }
  }

  private String write(List<TestCaseResult> cases) {
    try {
      return json.writeValueAsString(cases);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Kết quả test không ghi được", e);
    }
  }
}
