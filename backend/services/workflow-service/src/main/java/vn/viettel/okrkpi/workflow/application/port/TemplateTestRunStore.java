package vn.viettel.okrkpi.workflow.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.template.TestReport;
import vn.viettel.okrkpi.workflow.domain.template.TestRun;
import vn.viettel.okrkpi.workflow.domain.template.TestRunStatus;

/** Kho lần chạy bộ test {@code flowable.wf_template_test_run} (bảng bổ sung). */
public interface TemplateTestRunStore {

  void insert(TestRun run);

  Optional<TestRun> find(UUID id);

  /** Lần chạy đang chờ hoặc đang chạy của phiên bản với cùng nội dung. */
  Optional<TestRun> active(UUID templateId, byte[] sha256);

  /** Lần chạy đã kết thúc gần nhất của phiên bản. */
  Optional<TestRun> latestFinished(UUID templateId);

  void markRunning(UUID id);

  void finish(UUID id, TestRunStatus status, TestReport report);
}
