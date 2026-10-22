package vn.viettel.okrkpi.policy.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tác vụ nền {@code TEST_RUN} của lần chạy kiểm thử Policy Pack ({@code GET /api/v1/jobs/{id}}).
 */
public interface PolicyTestJobs {

  String TEST_RUN = "TEST_RUN";

  void accepted(TenantId tenant, String jobId, UUID initiator);

  void running(TenantId tenant, String jobId, int progress);

  /** Lần chạy đã xong (đạt hoặc không đạt); {@code location} trỏ tới lần chạy. */
  void finished(TenantId tenant, String jobId, String location, String message);

  /** Lỗi hệ thống, không có kết luận kiểm thử. */
  void failed(TenantId tenant, String jobId, String message);
}
