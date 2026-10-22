package vn.viettel.okrkpi.task.api.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nhiệm vụ vừa được giao, sự kiện trong tiến trình đi kèm outbox EVT-04 (CORE-02-12). Bản ghi được
 * công bố trước để các mô-đun nghe có thể viết listener; dịch vụ giao việc (CORE-02-14) phát nó
 * cùng giao dịch ghi EVT-04.
 *
 * @param tenantId tenant
 * @param periodId kỳ của nhiệm vụ
 * @param taskId nhiệm vụ
 * @param assignerId người giao
 * @param assigneeIds cán bộ được giao
 * @param occurredAt thời điểm giao
 */
public record NhiemVuDaGiao(
    TenantId tenantId,
    UUID periodId,
    UUID taskId,
    UUID assignerId,
    List<UUID> assigneeIds,
    Instant occurredAt) {

  public NhiemVuDaGiao {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(taskId, "taskId");
    assigneeIds = List.copyOf(assigneeIds == null ? List.of() : assigneeIds);
  }
}
