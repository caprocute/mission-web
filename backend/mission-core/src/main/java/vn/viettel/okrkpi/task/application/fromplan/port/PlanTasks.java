package vn.viettel.okrkpi.task.application.fromplan.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nhiệm vụ gắn dòng kế hoạch ({@code task.task.plan_item_id}) trên phân vùng kỳ của kế hoạch. */
public interface PlanTasks {

  /** Nhiệm vụ đã gắn một dòng kế hoạch. */
  record LinkedTask(UUID id, String code, UUID planItemId, String status, long rowVersion) {}

  /** Nội dung dòng kế hoạch ghi lên nhiệm vụ đã giao khi dòng được sửa. */
  record ItemContent(
      UUID planItemId,
      String title,
      String productName,
      BigDecimal quantity,
      String unit,
      LocalDate dueDate,
      Integer difficulty,
      BigDecimal standardScore) {}

  /** Nhiệm vụ (khác {@code HUY} trước) gắn một trong các dòng; mỗi dòng tối đa một nhiệm vụ. */
  List<LinkedTask> linked(TenantId tenant, int periodBucket, Collection<UUID> planItemIds);

  /** Gắn nhiệm vụ sang dòng của phiên bản mới, giữ nguyên nội dung. */
  void relink(
      TenantId tenant, int periodBucket, UUID taskId, UUID planItemId, UUID actor, Instant at);

  /**
   * Ghi nội dung dòng sửa lên nhiệm vụ còn {@code DA_GIAO}; {@code false} khi nhiệm vụ đã đổi trạng
   * thái.
   */
  boolean refreshAssigned(
      TenantId tenant, int periodBucket, UUID taskId, ItemContent content, UUID actor, Instant at);
}
