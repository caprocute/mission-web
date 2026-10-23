package vn.viettel.okrkpi.task.application.assign.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đưa nhiệm vụ đăng ký đã duyệt vào phiên bản điều chỉnh kế hoạch đang soạn (CORE-02-17, QT-05 bước
 * 3): đọc và thêm dòng {@code task.plan_item} của đơn vị trong kỳ.
 */
public interface TaskRegistrations {

  /** Phiên bản điều chỉnh ({@code version_no > 1}) còn {@code NHAP} và người lập phiên bản. */
  record OpenAdjustment(UUID planId, UUID versionId, UUID planner) {}

  /** Nhiệm vụ đăng ký đã giao nhưng chưa có dòng kế hoạch. */
  record Pending(
      UUID taskId,
      UUID catalogItemId,
      String title,
      String taskGroup,
      UUID leadPersonId,
      BigDecimal quantity,
      String unit,
      Integer difficulty,
      BigDecimal standardScore,
      LocalDate dueDate) {}

  Optional<OpenAdjustment> openAdjustment(
      TenantId tenant, int periodBucket, UUID periodId, UUID unitId);

  /** Người lập phiên bản kế hoạch mới nhất của đơn vị trong kỳ (để báo khi chưa có điều chỉnh). */
  Optional<UUID> planner(TenantId tenant, int periodBucket, UUID periodId, UUID unitId);

  /** Nhiệm vụ đăng ký của đơn vị trong kỳ đã giao mà chưa gắn dòng kế hoạch. */
  List<Pending> pending(TenantId tenant, int periodBucket, UUID periodId, UUID unitId);

  /**
   * Thêm dòng cuối phiên bản với lý do điều chỉnh "Đăng ký nhiệm vụ", gắn nhiệm vụ vào dòng và tăng
   * {@code row_version} của phiên bản để lần lưu cũ của người lập bị từ chối (409).
   */
  UUID addItem(
      TenantId tenant,
      int periodBucket,
      UUID periodId,
      OpenAdjustment target,
      Pending task,
      UUID actor,
      Instant at);
}
