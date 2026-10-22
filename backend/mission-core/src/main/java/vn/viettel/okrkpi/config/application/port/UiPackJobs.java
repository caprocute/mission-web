package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Tác vụ nền {@code TEST_RUN} của bước gửi duyệt UI Pack ({@code GET /api/v1/jobs/{id}}). */
public interface UiPackJobs {

  String TEST_RUN = "TEST_RUN";

  void accepted(TenantId tenantId, String jobId, UUID initiator);

  void running(TenantId tenantId, String jobId);

  /** Kiểm tra đạt; {@code location} là phiên bản UI Pack. */
  void passed(TenantId tenantId, String jobId, String location);

  /** Kiểm tra không đạt hoặc lỗi hệ thống, kèm danh sách lỗi. */
  void failed(TenantId tenantId, String jobId, String message, List<ProblemFieldError> errors);
}
