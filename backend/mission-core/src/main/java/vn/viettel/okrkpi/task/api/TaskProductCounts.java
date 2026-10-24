package vn.viettel.okrkpi.task.api;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Số sản phẩm của nhiệm vụ cho thẻ "Sản phẩm" của chi tiết nhiệm vụ (SCR-0601, {@code
 * TaskDetail.products}). MOD-03 giữ sản phẩm và minh chứng nên cung cấp bean; khi chưa có bean, mọi
 * số là 0.
 */
@FunctionalInterface
public interface TaskProductCounts {

  Counts of(TenantId tenant, UUID taskId);

  /**
   * @param confirmed sản phẩm đã được xác nhận đủ
   * @param pendingReview sản phẩm có minh chứng đang chờ nghiệm thu
   * @param required sản phẩm cần nộp
   */
  record Counts(int confirmed, int pendingReview, int required) {

    public static final Counts NONE = new Counts(0, 0, 0);
  }
}
