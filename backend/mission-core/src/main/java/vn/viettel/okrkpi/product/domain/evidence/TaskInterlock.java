package vn.viettel.okrkpi.product.domain.evidence;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Quy tắc liên động giữa máy trạng thái minh chứng và máy trạng thái nhiệm vụ (TL-02a QT-07, bảng
 * "tác động tới nhiệm vụ"; BR-07-10). Chỉ quyết định lệnh cần yêu cầu, không tự đặt trạng thái
 * nhiệm vụ; MOD-02 chạy lệnh qua bảng chuyển của Policy Pack.
 */
public final class TaskInterlock {

  /** Lệnh yêu cầu MOD-02, trùng mã của bảng {@code task_lifecycle}. */
  public enum TaskRequest {
    /** Mọi sản phẩm đủ phiên bản đã nộp: nhiệm vụ sang {@code CHO_NGHIEM_THU}. */
    SUBMIT,
    /** Mọi sản phẩm đã xác nhận đủ: nhiệm vụ sang {@code HOAN_THANH}. */
    APPROVE,
    /** Một phiên bản bị yêu cầu bổ sung hoặc từ chối: nhiệm vụ về {@code DANG_THUC_HIEN}. */
    REWORK
  }

  private TaskInterlock() {}

  /**
   * Lệnh cần yêu cầu sau khi một phiên bản chuyển sang {@code changed}.
   *
   * @param changed trạng thái mới của phiên bản vừa chuyển
   * @param deliverables tổng hợp mọi sản phẩm của nhiệm vụ sau lần chuyển
   * @return lệnh, hoặc rỗng khi không tác động tới nhiệm vụ
   */
  public static Optional<TaskRequest> after(
      EvidenceStatus changed, Collection<DeliverableRollup> deliverables) {
    Objects.requireNonNull(changed, "changed");
    Objects.requireNonNull(deliverables, "deliverables");
    return switch (changed) {
      case DA_NOP ->
          !deliverables.isEmpty()
                  && deliverables.stream().allMatch(DeliverableRollup::fullySubmitted)
              ? Optional.of(TaskRequest.SUBMIT)
              : Optional.empty();
      case DA_XAC_NHAN ->
          !deliverables.isEmpty()
                  && deliverables.stream().allMatch(DeliverableRollup::fullyAccepted)
              ? Optional.of(TaskRequest.APPROVE)
              : Optional.empty();
      case YEU_CAU_BO_SUNG, TU_CHOI -> Optional.of(TaskRequest.REWORK);
      case NHAP, DANG_NGHIEM_THU -> Optional.empty();
    };
  }
}
