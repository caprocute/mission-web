package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Tác vụ nền {@code TEST_RUN} của bước gửi duyệt UI Pack ({@code GET /api/v1/jobs/{id}}). */
public interface UiPackJobs {

  String TEST_RUN = "TEST_RUN";

  /** Loại tác vụ biên dịch và phát hành (CORE-08-11). */
  String RENDER = "RENDER";

  /** Nhận tác vụ {@link #TEST_RUN}. */
  default void accepted(TenantId tenantId, String jobId, UUID initiator) {
    accepted(tenantId, jobId, initiator, TEST_RUN);
  }

  void accepted(TenantId tenantId, String jobId, UUID initiator, String type);

  void running(TenantId tenantId, String jobId);

  /** Kiểm tra đạt; {@code location} là phiên bản UI Pack. */
  default void passed(TenantId tenantId, String jobId, String location) {
    passed(tenantId, jobId, location, "Đạt kiểm tra tự động, đã gửi người duyệt");
  }

  /** Tác vụ xong với thông điệp cho người dùng. */
  void passed(TenantId tenantId, String jobId, String location, String message);

  /** Kiểm tra không đạt hoặc lỗi hệ thống, kèm danh sách lỗi. */
  void failed(TenantId tenantId, String jobId, String message, List<ProblemFieldError> errors);
}
