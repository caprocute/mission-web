package vn.viettel.okrkpi.workflow.domain.template;

import java.util.Objects;
import java.util.UUID;

/**
 * Đề nghị phát hành một phiên bản mẫu: merge request vào repo mẫu luồng, người phê duyệt và chữ ký
 * cosign do pipeline báo (BS-01 "Đồng bộ với kho mã").
 *
 * @param templateId phiên bản mẫu
 * @param mergeRequestRef mã merge request
 * @param mergeRequestUrl đường dẫn merge request
 * @param status trạng thái
 * @param submittedBy người đề nghị
 * @param approvedBy người phê duyệt
 * @param sagaId saga phát hành
 * @param signature chữ ký cosign
 * @param pipelineRef lần chạy pipeline đã ký
 */
public record Release(
    UUID templateId,
    String mergeRequestRef,
    String mergeRequestUrl,
    ReleaseStatus status,
    UUID submittedBy,
    UUID approvedBy,
    UUID sagaId,
    byte[] signature,
    String pipelineRef) {

  public Release {
    Objects.requireNonNull(templateId, "templateId");
    Objects.requireNonNull(mergeRequestRef, "mergeRequestRef");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(submittedBy, "submittedBy");
    signature = signature == null ? null : signature.clone();
  }

  @Override
  public byte[] signature() {
    return signature == null ? null : signature.clone();
  }

  public boolean signed() {
    return status == ReleaseStatus.DA_KY || status == ReleaseStatus.DA_AP_DUNG;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Release r && templateId.equals(r.templateId) && status == r.status;
  }

  @Override
  public int hashCode() {
    return Objects.hash(templateId, status);
  }

  @Override
  public String toString() {
    return "Release[" + templateId + ", " + mergeRequestRef + ", " + status + "]";
  }
}
