package vn.viettel.okrkpi.replica.api;

import java.time.LocalDate;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Sự kiện ứng dụng nội bộ: EVT-15 đính chính lùi ngày rơi vào kỳ đã khoá (TL-03 L4084). MOD-04 nghe
 * để tạo cảnh báo đối soát. {@code periodId} là kỳ hiện hành khi ngày thuộc kỳ đó (đang chốt hay đã
 * khoá), rỗng khi ngày thuộc một kỳ trước.
 */
public record AssignmentBackdatedEvent(
    TenantId tenantId,
    UUID assignmentId,
    UUID personId,
    UUID orgUnitId,
    LocalDate validFrom,
    UUID periodId,
    UUID sourceEventId) {}
