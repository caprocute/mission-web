package vn.viettel.okrkpi.task.api;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phiên bản kế hoạch vừa gửi duyệt (QT-04 bước 5, CORE-02-08). Sự kiện trong tiến trình, ghi vào
 * {@code modulith.event_publication} cùng giao dịch gửi duyệt; {@code PlanApprovalListener} nghe sự
 * kiện này để khởi tạo tiến trình {@code duyet-ke-hoach-quy} với business key {@code PLAN:
 * <planId>} (wiki trang 14). Không phải sự kiện EVT, không có mã EVT riêng.
 *
 * @param contentHash SHA-256 dạng hex của biểu diễn chuẩn hoá
 * @param aggregateVersion {@code row_version} của phiên bản sau khi gửi duyệt, SVC-06 gửi lại trong
 *     {@code If-Match}
 */
public record PlanVersionSubmittedEvent(
    TenantId tenantId,
    UUID planId,
    UUID versionId,
    int versionNo,
    UUID periodId,
    UUID ownerUnitId,
    UUID submittedBy,
    String contentHash,
    long aggregateVersion,
    Instant occurredAt) {}
