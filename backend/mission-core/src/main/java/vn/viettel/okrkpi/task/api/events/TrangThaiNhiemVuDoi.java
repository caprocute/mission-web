package vn.viettel.okrkpi.task.api.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Trạng thái nhiệm vụ vừa đổi, sự kiện trong tiến trình theo TL-01 "Giao tiếp giữa module"
 * (CORE-02-12). Phát trong cùng giao dịch ghi outbox EVT-06 và đi qua Event Publication Registry
 * (ADR-23), nên listener {@code @ApplicationModuleListener} của MOD-04, MOD-07 không mất sự kiện
 * khi pod dừng.
 *
 * @param tenantId tenant
 * @param periodId kỳ của nhiệm vụ
 * @param taskId nhiệm vụ
 * @param oldStatus trạng thái trước
 * @param newStatus trạng thái sau (giá trị chuẩn của baseline, ví dụ {@code HOAN_THANH})
 * @param assigneeIds cán bộ đang được giao (chủ trì và phối hợp)
 * @param isTerminal {@code true} khi trạng thái sau là {@code HOAN_THANH} hoặc {@code HUY}
 * @param occurredAt thời điểm chuyển
 */
public record TrangThaiNhiemVuDoi(
    TenantId tenantId,
    UUID periodId,
    UUID taskId,
    String oldStatus,
    String newStatus,
    List<UUID> assigneeIds,
    boolean isTerminal,
    Instant occurredAt) {

  public TrangThaiNhiemVuDoi {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(newStatus, "newStatus");
    assigneeIds = List.copyOf(assigneeIds == null ? List.of() : assigneeIds);
  }
}
