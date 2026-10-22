package vn.viettel.okrkpi.policy.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.PolicyTestRun;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lần chạy kiểm thử ({@code policy.test_run}) và kết quả từng ca ({@code test_case_result}). */
public interface PolicyTestRunRepository {

  /** Lần chạy kèm số thứ tự trong gói (#14 của SCR-1503, suy luận). */
  record NumberedRun(PolicyTestRun run, int runNo) {}

  /** Kết quả một ca; {@code actualJson}, {@code diffJson} là JSON. */
  record CaseResult(UUID goldenTestCaseId, boolean passed, String actualJson, String diffJson) {}

  void insertRun(TenantId tenant, PolicyTestRun run);

  /** Ghi số liệu kết thúc nếu {@code row_version} còn bằng {@code expected}. */
  boolean storeFinished(TenantId tenant, PolicyTestRun run, long expected);

  void insertCaseResults(TenantId tenant, UUID runId, List<CaseResult> results);

  Optional<NumberedRun> findRun(TenantId tenant, UUID versionId, UUID runId);

  /** Lần chạy đang dở của phiên bản. */
  Optional<PolicyTestRun> findRunning(TenantId tenant, UUID versionId);

  /** Lần chạy của phiên bản, mới nhất trước, sau {@code beforeId} (con trỏ UUIDv7). */
  List<NumberedRun> listRuns(TenantId tenant, UUID versionId, UUID beforeId, int limit);

  List<CaseResult> caseResults(TenantId tenant, UUID runId);
}
