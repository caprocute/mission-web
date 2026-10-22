package vn.viettel.okrkpi.task.api;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phiên bản kế hoạch đã duyệt và ký số (EVT-03, CORE-02-09), phát trong giao dịch chuyển {@code
 * DA_DUYET}. Cầu nối workflow nghe sự kiện này để kết thúc tiến trình {@code duyet-ke-hoach}.
 *
 * @param supersededVersionId phiên bản hiệu lực trước đó khi đây là điều chỉnh, {@code null} nếu là
 *     lần duyệt đầu
 */
public record PlanVersionApprovedEvent(
    TenantId tenantId,
    UUID planId,
    UUID versionId,
    int versionNo,
    UUID periodId,
    String planType,
    UUID ownerId,
    int itemCount,
    UUID approvedBy,
    UUID signRequestId,
    UUID supersededVersionId,
    long aggregateVersion,
    Instant occurredAt) {}
