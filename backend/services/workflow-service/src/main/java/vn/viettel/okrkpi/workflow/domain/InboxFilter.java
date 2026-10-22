package vn.viettel.okrkpi.workflow.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Bộ lọc hộp việc của một người dùng: việc giao thẳng cho người đó, cộng việc chưa giao có nhóm ứng
 * viên khớp vai trò và đơn vị đang chọn (hợp đồng {@code listWorkflowInbox}).
 *
 * @param approver người gọi
 * @param businessType lọc theo loại đối tượng, có thể {@code null}
 * @param periodId lọc theo kỳ, có thể {@code null}
 */
public record InboxFilter(Approver approver, String businessType, UUID periodId) {

  public InboxFilter {
    Objects.requireNonNull(approver, "approver");
    if (businessType != null && businessType.isBlank()) {
      businessType = null;
    }
  }

  /** Phạm vi con trỏ: tên danh sách kèm bộ lọc, để con trỏ không dùng được cho bộ lọc khác. */
  public String cursorScope() {
    return "workflow.inbox|"
        + approver.actorId()
        + '|'
        + (businessType == null ? "" : businessType)
        + '|'
        + (periodId == null ? "" : periodId);
  }
}
