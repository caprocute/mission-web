package vn.viettel.okrkpi.okr.application.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Khởi tạo tiến trình {@code duyet-okr} trên workflow-service (CORE-01-05, ADR-04 tầng 2). DV-06-03
 * chọn API nội bộ {@code StartApprovalService}, idempotent theo (tenant, mẫu, khoá nghiệp vụ {@code
 * OKR_OBJECTIVE:<id>}, {@code row_version} lúc trình); hợp đồng {@code POST /workflow/instances}
 * chưa có trong {@code platform.yaml} nên chưa có bộ chuyển HTTP. Lệnh được gọi sau khi giao dịch
 * trình đã commit, không gọi mạng trong giao dịch; chưa có bean thì mục tiêu ở {@code CHO_DUYET}
 * chờ khởi tạo.
 */
public interface OkrApprovalStarter {

  /** Tiến trình đã tạo: mã tiến trình, người duyệt dự kiến, hạn duyệt (MSG-02-08). */
  record ApprovalStarted(String processInstanceId, UUID reviewerId, LocalDate dueDate) {}

  Optional<ApprovalStarted> start(
      TenantId tenant, UUID objectiveId, UUID ownerUnitId, long rowVersion, UUID submittedBy);
}
