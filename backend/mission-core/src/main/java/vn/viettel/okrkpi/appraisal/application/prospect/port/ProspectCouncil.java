package vn.viettel.okrkpi.appraisal.application.prospect.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Khởi động user task thông qua tập thể lãnh đạo ở workflow-service (QT-10 bước 9, SVC-06) sau khi
 * giao dịch trình đã commit; idempotent theo (tenant, khoá nghiệp vụ {@code PROSPECT_BOARD:<id>},
 * {@code boardVersion}). Cùng cơ chế với {@code OkrApprovalStarter}: hợp đồng {@code platform.yaml}
 * chưa có thao tác khởi tạo tiến trình giữa các dịch vụ nên chưa có bộ chuyển HTTP; chưa có bean
 * thì danh sách ở {@code DA_TRINH} chờ khởi tạo.
 */
public interface ProspectCouncil {

  /** Mã tiến trình đã tạo, rỗng khi workflow-service chưa nhận. */
  Optional<String> start(
      TenantId tenant,
      UUID boardId,
      UUID periodId,
      UUID orgUnitId,
      int boardVersion,
      UUID submittedBy);
}
