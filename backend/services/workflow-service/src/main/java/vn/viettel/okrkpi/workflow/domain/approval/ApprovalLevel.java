package vn.viettel.okrkpi.workflow.domain.approval;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Một cấp duyệt đã phân giải tại thời điểm khởi tạo tiến trình (phần tử của biến {@code
 * approvalLevels}). Đơn vị cụ thể được lưu lại nên điều động nhân sự sau đó không làm hồ sơ mất
 * người duyệt (TL-01 G2).
 *
 * @param order thứ tự cấp
 * @param role vai trò baseline của nhóm ứng viên
 * @param scope phạm vi đã dùng để phân giải
 * @param orgUnitId đơn vị đã phân giải
 * @param dueAt hạn xử lý theo lịch làm việc của tenant
 * @param workingDays số ngày làm việc của cấp
 */
public record ApprovalLevel(
    int order, String role, ApprovalScope scope, UUID orgUnitId, Instant dueAt, int workingDays) {

  public ApprovalLevel {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(orgUnitId, "orgUnitId");
    Objects.requireNonNull(dueAt, "dueAt");
  }
}
