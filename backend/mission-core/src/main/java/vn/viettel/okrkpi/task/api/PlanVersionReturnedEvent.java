package vn.viettel.okrkpi.task.api;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phiên bản kế hoạch bị trả lại (QT-04 bước 7b, CORE-02-08); cầu nối workflow kết thúc tiến trình
 * {@code duyet-ke-hoach} của phiên bản. Sự kiện trong tiến trình, không có mã EVT riêng.
 */
public record PlanVersionReturnedEvent(
    TenantId tenantId,
    UUID planId,
    UUID versionId,
    int versionNo,
    UUID returnedBy,
    long aggregateVersion,
    Instant occurredAt) {}
