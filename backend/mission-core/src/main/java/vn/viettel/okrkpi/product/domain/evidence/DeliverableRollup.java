package vn.viettel.okrkpi.product.domain.evidence;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;

/**
 * Tổng hợp một sản phẩm đầu ra từ phiên bản hiện hành của các minh chứng của nó (CORE-03-02).
 *
 * <p>Mỗi minh chứng ứng với một đơn vị sản phẩm. {@code quantity_accepted} chỉ đếm minh chứng có
 * phiên bản được tính điểm ({@link EvidenceVersion#countsForScoring()}, BR-07-05); {@code
 * submittedCount} đếm minh chứng có phiên bản đã nộp và chưa bị trả lại.
 *
 * @param quantityPlanned số lượng kế hoạch
 * @param quantityAccepted số lượng đã xác nhận (đầu vào cấu phần A)
 * @param submittedCount số minh chứng đã nộp còn hiệu lực
 * @param status trạng thái tổng hợp
 */
public record DeliverableRollup(
    BigDecimal quantityPlanned,
    BigDecimal quantityAccepted,
    long submittedCount,
    DeliverableStatus status) {

  public DeliverableRollup {
    Objects.requireNonNull(quantityPlanned, "quantityPlanned");
    Objects.requireNonNull(quantityAccepted, "quantityAccepted");
    Objects.requireNonNull(status, "status");
  }

  /**
   * Tính từ phiên bản hiện hành của từng minh chứng thuộc sản phẩm.
   *
   * @param quantityPlanned số lượng kế hoạch, lớn hơn 0
   * @param currentVersions phiên bản mới nhất của mỗi minh chứng
   */
  public static DeliverableRollup of(
      BigDecimal quantityPlanned, Collection<EvidenceVersion> currentVersions) {
    Objects.requireNonNull(quantityPlanned, "quantityPlanned");
    Objects.requireNonNull(currentVersions, "currentVersions");
    long accepted = currentVersions.stream().filter(EvidenceVersion::countsForScoring).count();
    long submitted = currentVersions.stream().filter(v -> v.status().isSubmittedAndLive()).count();
    BigDecimal acceptedQty = BigDecimal.valueOf(accepted);
    DeliverableStatus status;
    if (currentVersions.isEmpty()) {
      status = DeliverableStatus.CHUA_CO;
    } else if (accepted == 0) {
      status = DeliverableStatus.DANG_THUC_HIEN;
    } else if (acceptedQty.compareTo(quantityPlanned) < 0) {
      status = DeliverableStatus.DA_XAC_NHAN_MOT_PHAN;
    } else {
      status = DeliverableStatus.DA_XAC_NHAN;
    }
    return new DeliverableRollup(quantityPlanned, acceptedQty, submitted, status);
  }

  /** Đủ số lượng đã nộp (còn hiệu lực) theo kế hoạch. */
  public boolean fullySubmitted() {
    return BigDecimal.valueOf(submittedCount).compareTo(quantityPlanned) >= 0;
  }

  public boolean fullyAccepted() {
    return status == DeliverableStatus.DA_XAC_NHAN;
  }
}
