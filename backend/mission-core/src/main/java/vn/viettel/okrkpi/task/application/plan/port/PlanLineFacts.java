package vn.viettel.okrkpi.task.application.plan.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tình trạng nhiệm vụ đã sinh từ dòng kế hoạch, dùng kiểm BR-04-11 và BR-04-12 khi điều chỉnh
 * (CORE-02-10). MOD-02 không gọi ngược {@code product}: "đã có minh chứng xác nhận" đọc từ sản phẩm
 * mà MOD-03 đẩy vào nhiệm vụ qua {@code TaskApi} (CORE-02-12, suy luận).
 */
public interface PlanLineFacts {

  /** Nhiệm vụ của một dòng: trạng thái và đã có sản phẩm {@code DA_XAC_NHAN} chưa. */
  record LineFact(UUID planItemId, String taskStatus, boolean confirmedEvidence) {}

  /** Theo mã dòng; dòng chưa sinh nhiệm vụ không có trong kết quả. */
  Map<UUID, LineFact> of(TenantId tenant, int periodBucket, Collection<UUID> planItemIds);

  /** Không có nhiệm vụ nào (dùng khi chưa nối MOD-02 với nhiệm vụ). */
  static PlanLineFacts none() {
    return (tenant, bucket, ids) -> Map.of();
  }
}
