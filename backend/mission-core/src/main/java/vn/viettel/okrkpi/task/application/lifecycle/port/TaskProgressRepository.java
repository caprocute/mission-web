package vn.viettel.okrkpi.task.application.lifecycle.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Bảng {@code task.task_progress} (chỉ ghi thêm) và cột tiến độ của {@code task.task}. */
public interface TaskProgressRepository {

  /** Một dòng tiến độ mới; {@code ratio} là phần trăm chia 100. */
  record NewProgress(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID taskId,
      Instant reportedAt,
      BigDecimal ratio,
      BigDecimal doneQuantity,
      String note,
      String channel,
      String idempotencyKey,
      UUID actorId) {}

  /** Đã có dòng tiến độ mang khoá này (ứng dụng gửi lại sau mất kết nối). */
  boolean exists(TenantId tenant, int periodBucket, String idempotencyKey);

  void insert(TenantId tenant, NewProgress progress);

  /**
   * Ghi {@code progress_pct}, {@code done_quantity} (giữ giá trị cũ khi {@code null}) và tăng
   * {@code row_version} khi khớp {@code expectedVersion}; trả phiên bản mới, {@code -1} khi lệch.
   */
  long applyProgress(
      TenantId tenant,
      int periodBucket,
      UUID taskId,
      long expectedVersion,
      BigDecimal ratio,
      BigDecimal doneQuantity,
      UUID actorId,
      Instant at);
}
