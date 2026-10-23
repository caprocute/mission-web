package vn.viettel.okrkpi.oversight.domain.complaint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bản ghi {@code oversight.complaint_response} (chỉ thêm): yêu cầu giải trình gửi một bên, giải
 * trình trả lời một yêu cầu, kết luận đã đưa ký, ý kiến bảo lưu. Nội dung chỉ ở dạng mã hoá.
 */
public record ComplaintResponse(
    UUID id,
    UUID complaintId,
    UUID periodId,
    int periodBucket,
    ComplaintRecordKind kind,
    UUID responderId,
    ExplanationParty party,
    UUID requestId,
    LocalDate dueDate,
    byte[] contentEnc,
    UUID dekId,
    List<UUID> attachmentIds,
    String docRef,
    UUID signRequestId,
    ComplaintResolution resolution,
    List<ComplaintAdjustment> adjustments,
    Instant respondedAt) {

  public ComplaintResponse {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(complaintId, "complaintId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(responderId, "responderId");
    Objects.requireNonNull(contentEnc, "contentEnc");
    Objects.requireNonNull(dekId, "dekId");
    Objects.requireNonNull(respondedAt, "respondedAt");
    attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
  }
}
