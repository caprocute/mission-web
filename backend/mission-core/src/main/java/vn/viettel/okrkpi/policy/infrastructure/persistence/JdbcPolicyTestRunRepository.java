package vn.viettel.okrkpi.policy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.policy.application.port.PolicyTestRunRepository;
import vn.viettel.okrkpi.policy.domain.PolicyTestRun;
import vn.viettel.okrkpi.policy.domain.TestRunResult;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link PolicyTestRunRepository} trên {@code policy.test_run} và {@code policy.test_case_result},
 * ghi {@code tenant_id} tường minh. Số thứ tự lần chạy đếm theo gói, theo thứ tự UUIDv7.
 */
public class JdbcPolicyTestRunRepository implements PolicyTestRunRepository {

  private static final String COLUMNS =
      "r.id, r.policy_pack_version_id, r.engine, r.started_at, r.finished_at, r.total, r.passed,"
          + " r.failed, r.result, r.engine_match, r.property_result_json::text AS property_json,"
          + " r.cases_json::text AS cases_json, r.created_by, r.row_version,"
          + " (SELECT count(*) FROM policy.test_run x JOIN policy.policy_pack_version xv"
          + " ON xv.tenant_id = x.tenant_id AND xv.id = x.policy_pack_version_id"
          + " WHERE x.tenant_id = r.tenant_id AND xv.policy_pack_id = v.policy_pack_id"
          + " AND x.id <= r.id) AS run_no";

  private static final String FROM =
      " FROM policy.test_run r JOIN policy.policy_pack_version v"
          + " ON v.tenant_id = r.tenant_id AND v.id = r.policy_pack_version_id";

  private final JdbcClient jdbc;

  public JdbcPolicyTestRunRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void insertRun(TenantId tenant, PolicyTestRun run) {
    jdbc.sql(
            """
            INSERT INTO policy.test_run
              (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
               policy_pack_version_id, engine, started_at, total, passed, failed, result,
               engine_match)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .param(run.id())
        .param(tenant.value())
        .param(Timestamp.from(run.startedAt()))
        .param(run.createdBy())
        .param(Timestamp.from(run.startedAt()))
        .param(run.createdBy())
        .param(run.rowVersion())
        .param(run.versionId())
        .param(run.engine())
        .param(Timestamp.from(run.startedAt()))
        .param(run.total())
        .param(run.passed())
        .param(run.failed())
        .param(run.result().name())
        .param(run.engineMatch())
        .update();
  }

  @Override
  public boolean storeFinished(TenantId tenant, PolicyTestRun run, long expected) {
    return jdbc.sql(
                """
                UPDATE policy.test_run
                   SET finished_at = ?, total = ?, passed = ?, failed = ?, result = ?,
                       engine_match = ?, property_result_json = ?::jsonb, cases_json = ?::jsonb,
                       updated_at = ?, row_version = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()))
            .param(run.total())
            .param(run.passed())
            .param(run.failed())
            .param(run.result().name())
            .param(run.engineMatch())
            .param(run.propertyResultJson())
            .param(run.casesJson())
            .param(Timestamp.from(run.finishedAt() == null ? run.startedAt() : run.finishedAt()))
            .param(run.rowVersion())
            .param(tenant.value())
            .param(run.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public void insertCaseResults(TenantId tenant, UUID runId, List<CaseResult> results) {
    for (CaseResult r : results) {
      jdbc.sql(
              """
              INSERT INTO policy.test_case_result
                (test_run_id, golden_test_case_id, tenant_id, passed, actual, diff)
              VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
              """)
          .param(runId)
          .param(r.goldenTestCaseId())
          .param(tenant.value())
          .param(r.passed())
          .param(r.actualJson())
          .param(r.diffJson())
          .update();
    }
  }

  @Override
  public Optional<NumberedRun> findRun(TenantId tenant, UUID versionId, UUID runId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + FROM
                + " WHERE r.tenant_id = ? AND r.policy_pack_version_id = ? AND r.id = ?")
        .param(tenant.value())
        .param(versionId)
        .param(runId)
        .query(JdbcPolicyTestRunRepository::row)
        .optional();
  }

  @Override
  public Optional<PolicyTestRun> findRunning(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + FROM
                + " WHERE r.tenant_id = ? AND r.policy_pack_version_id = ? AND r.result = ?"
                + " ORDER BY r.id DESC LIMIT 1")
        .param(tenant.value())
        .param(versionId)
        .param(TestRunResult.DANG_CHAY.name())
        .query(JdbcPolicyTestRunRepository::row)
        .optional()
        .map(NumberedRun::run);
  }

  @Override
  public List<NumberedRun> listRuns(TenantId tenant, UUID versionId, UUID beforeId, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + FROM
                + " WHERE r.tenant_id = ? AND r.policy_pack_version_id = ?"
                + " AND (CAST(? AS uuid) IS NULL OR r.id < ?)"
                + " ORDER BY r.id DESC LIMIT ?")
        .param(tenant.value())
        .param(versionId)
        .param(beforeId)
        .param(beforeId)
        .param(limit)
        .query(JdbcPolicyTestRunRepository::row)
        .list();
  }

  @Override
  public List<CaseResult> caseResults(TenantId tenant, UUID runId) {
    return jdbc.sql(
            """
            SELECT golden_test_case_id, passed, actual::text AS actual, diff::text AS diff
              FROM policy.test_case_result
             WHERE tenant_id = ? AND test_run_id = ?
             ORDER BY golden_test_case_id
            """)
        .param(tenant.value())
        .param(runId)
        .query(
            (rs, n) ->
                new CaseResult(
                    rs.getObject("golden_test_case_id", UUID.class),
                    rs.getBoolean("passed"),
                    rs.getString("actual"),
                    rs.getString("diff")))
        .list();
  }

  private static NumberedRun row(ResultSet rs, int n) throws SQLException {
    Timestamp finished = rs.getTimestamp("finished_at");
    PolicyTestRun run =
        new PolicyTestRun(
            rs.getObject("id", UUID.class),
            rs.getObject("policy_pack_version_id", UUID.class),
            rs.getString("engine"),
            rs.getTimestamp("started_at").toInstant(),
            finished == null ? null : finished.toInstant(),
            rs.getInt("total"),
            rs.getInt("passed"),
            rs.getInt("failed"),
            TestRunResult.valueOf(rs.getString("result")),
            rs.getBoolean("engine_match"),
            rs.getString("property_json"),
            rs.getString("cases_json"),
            rs.getObject("created_by", UUID.class),
            rs.getLong("row_version"));
    return new NumberedRun(run, rs.getInt("run_no"));
  }
}
