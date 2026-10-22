package vn.viettel.okrkpi.task.api;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phiên bản kế hoạch vừa gửi duyệt (QT-04 bước 5, CORE-02-08). Sự kiện trong tiến trình, ghi vào
 * {@code modulith.event_publication} cùng giao dịch gửi duyệt; cầu nối workflow (cơ chế của
 * CORE-01-05) nghe sự kiện này để khởi tạo tiến trình {@code duyet-ke-hoach} với business key
 * {@code PLAN_VERSION: <versionId>}. Không phải sự kiện EVT, không có mã EVT riêng.
 *
 * @param contentHash SHA-256 dạng hex của biểu diễn chuẩn hoá
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
