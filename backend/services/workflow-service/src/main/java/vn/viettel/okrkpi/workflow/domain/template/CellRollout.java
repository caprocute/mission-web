package vn.viettel.okrkpi.workflow.domain.template;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Tiến độ triển khai một phiên bản mẫu lên engine của một cell ({@code
 * flowable.wf_template_rollout}).
 *
 * @param templateId phiên bản mẫu
 * @param cellCode mã cell
 * @param seqNo thứ tự cuốn chiếu, đếm từ 1
 * @param status trạng thái
 * @param deploymentId mã triển khai trên engine của cell
 * @param deployedAt thời điểm triển khai xong
 * @param previousVersionNo phiên bản mặc định trước đó trên cell, đặt lại khi bù trừ
 */
public record CellRollout(
    UUID templateId,
    String cellCode,
    int seqNo,
    RolloutStatus status,
    String deploymentId,
    Instant deployedAt,
    Integer previousVersionNo) {

  public CellRollout {
    Objects.requireNonNull(templateId, "templateId");
    Objects.requireNonNull(cellCode, "cellCode");
    Objects.requireNonNull(status, "status");
    if (seqNo < 1) {
      throw new IllegalArgumentException("Thứ tự cuốn chiếu phải từ 1");
    }
  }
}
