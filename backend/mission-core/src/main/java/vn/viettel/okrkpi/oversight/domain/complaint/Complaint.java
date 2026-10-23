package vn.viettel.okrkpi.oversight.domain.complaint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Khiếu nại ({@code oversight.complaint}). Nội dung lý do và đề nghị chỉ giữ ở dạng mã hoá ({@code
 * contentEnc}, {@code requestTextEnc}, DEK {@code dekId}); bản rõ không đi qua miền.
 */
public record Complaint(
    UUID id,
    String code,
    UUID periodId,
    int periodBucket,
    UUID complainantId,
    UUID profileId,
    ComplaintKind kind,
    ComplaintStage targetStage,
    String subjectLine,
    List<ComplaintTarget> targets,
    byte[] contentEnc,
    byte[] requestTextEnc,
    UUID dekId,
    List<UUID> attachmentIds,
    boolean reserveOpinion,
    ComplaintStatus status,
    Instant filedAt,
    LocalDate responseDeadline,
    UUID handlerUnitId,
    UUID signRequestId,
    Instant createdAt,
    int rowVersion) {

  public Complaint {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(targetStage, "targetStage");
    targets = targets == null ? List.of() : List.copyOf(targets);
    attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
  }

  /** Chưa gửi. */
  public boolean draft() {
    return status == null;
  }

  /** Đã mở yêu cầu ký ở SVC-10, chưa nhận EVT-18. */
  public boolean awaitingSignature() {
    return draft() && signRequestId != null;
  }

  /** Bản sau khi mở yêu cầu ký. */
  public Complaint withSignRequest(UUID signRequest) {
    return new Complaint(
        id,
        code,
        periodId,
        periodBucket,
        complainantId,
        profileId,
        kind,
        targetStage,
        subjectLine,
        targets,
        contentEnc,
        requestTextEnc,
        dekId,
        attachmentIds,
        reserveOpinion,
        status,
        filedAt,
        responseDeadline,
        handlerUnitId,
        signRequest,
        createdAt,
        rowVersion + 1);
  }

  /** Bản đã gửi ({@code MOI}) sau khi ký xong. */
  public Complaint filed(Instant at, LocalDate deadline, UUID handlerUnit) {
    return new Complaint(
        id,
        code,
        periodId,
        periodBucket,
        complainantId,
        profileId,
        kind,
        targetStage,
        subjectLine,
        targets,
        contentEnc,
        requestTextEnc,
        dekId,
        attachmentIds,
        reserveOpinion,
        ComplaintStatus.MOI,
        at,
        deadline,
        handlerUnit,
        signRequestId,
        createdAt,
        rowVersion + 1);
  }
}
