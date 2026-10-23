package vn.viettel.okrkpi.task.application.plan.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tiến trình {@code duyet-ke-hoach-quy} trên SVC-06 (QT-04, CORE-02-08). Khởi tạo idempotent theo
 * (kế hoạch, phiên bản đối tượng) nên gọi lại sau khi nhận trùng sự kiện không sinh tiến trình thứ
 * hai.
 */
public interface PlanApprovalProcess {

  /**
   * Tham số khởi tạo.
   *
   * @param objectVersion {@code row_version} của phiên bản lúc gửi duyệt; SVC-06 gửi lại trong
   *     {@code If-Match} khi gọi duyệt hoặc trả lại
   * @param flow luồng đánh giá {@code DANG} hoặc {@code NHA_NUOC}
   */
  record Start(
      UUID planId,
      int versionNo,
      long objectVersion,
      UUID ownerUnitId,
      String flow,
      UUID submittedBy,
      UUID periodId,
      String title) {}

  /** Khởi tạo; trả mã tiến trình, rỗng khi môi trường chưa cấu hình SVC-06. */
  Optional<String> start(TenantId tenant, Start start);

  /** Kết thúc tiến trình còn chờ người duyệt của kế hoạch; trả số tiến trình đã kết thúc. */
  int cancel(TenantId tenant, UUID planId, String reason);
}
