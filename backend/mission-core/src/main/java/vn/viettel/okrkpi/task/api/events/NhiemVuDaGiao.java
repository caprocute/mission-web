package vn.viettel.okrkpi.task.api.events;

import java.math.BigDecimal;
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
 * @param productName sản phẩm đầu ra ghi lúc soạn, để MOD-03 khai báo sản phẩm cần nộp; {@code
 *     null} khi bản soạn không ghi
 * @param unit đơn vị tính của sản phẩm
 * @param quantity số lượng kế hoạch
 */
public record NhiemVuDaGiao(
    TenantId tenantId,
    UUID periodId,
    UUID taskId,
    UUID assignerId,
    List<UUID> assigneeIds,
    Instant occurredAt,
    String productName,
    String unit,
    BigDecimal quantity) {

  /** Sự kiện không kèm sản phẩm đầu ra. */
  public NhiemVuDaGiao(
      TenantId tenantId,
      UUID periodId,
      UUID taskId,
      UUID assignerId,
      List<UUID> assigneeIds,
      Instant occurredAt) {
    this(tenantId, periodId, taskId, assignerId, assigneeIds, occurredAt, null, null, null);
  }

  public NhiemVuDaGiao {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(taskId, "taskId");
    assigneeIds = List.copyOf(assigneeIds == null ? List.of() : assigneeIds);
  }
}
