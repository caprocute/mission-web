package vn.viettel.okrkpi.task.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Trạng thái nhiệm vụ vừa đổi (EVT-06 {@code task.task.statuschanged.v1}); phát qua outbox và đồng
 * thời là sự kiện trong tiến trình cho listener của module khác (CORE-02-12).
 *
 * @param tenantId tenant
 * @param taskId nhiệm vụ
 * @param periodId kỳ của nhiệm vụ
 * @param fromStatus trạng thái trước
 * @param toStatus trạng thái sau
 * @param transition mã lệnh ({@code ASSIGN}, {@code SUBMIT}…)
 * @param overdue cờ quá hạn tại lúc chuyển
 * @param reason lý do, có thể {@code null}
 * @param changedBy người thực hiện
 * @param aggregateVersion {@code row_version} sau khi chuyển
 * @param stateMachineVersion phiên bản bảng chuyển đã dùng
 * @param occurredAt thời điểm chuyển
 */
public record TaskStatusChangedEvent(
    TenantId tenantId,
    UUID taskId,
    UUID periodId,
    String fromStatus,
    String toStatus,
    String transition,
    boolean overdue,
    String reason,
    UUID changedBy,
    long aggregateVersion,
    String stateMachineVersion,
    Instant occurredAt) {

  public TaskStatusChangedEvent {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(toStatus, "toStatus");
    Objects.requireNonNull(changedBy, "changedBy");
  }
}
