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
    int rowVersion,
    ComplaintHandling handling) {

  public Complaint {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(targetStage, "targetStage");
    targets = targets == null ? List.of() : List.copyOf(targets);
    attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    handling = handling == null ? ComplaintHandling.NONE : handling;
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
    return copy(status, filedAt, responseDeadline, handlerUnitId, signRequest, handling);
  }

  /** Bản đã gửi ({@code MOI}) sau khi ký xong, kèm hạn trả lời và hạn tiếp nhận. */
  public Complaint filed(
      Instant at, LocalDate deadline, UUID handlerUnit, LocalDate acceptDeadline) {
    requireMove(ComplaintStatus.MOI);
    return copy(
        ComplaintStatus.MOI,
        at,
        deadline,
        handlerUnit,
        signRequestId,
        handling.withAcceptDeadline(acceptDeadline));
  }

  /** {@code MOI} → {@code DA_TIEP_NHAN}. */
  public Complaint accepted(UUID handler, Instant at) {
    requireMove(ComplaintStatus.DA_TIEP_NHAN);
    return copy(
        ComplaintStatus.DA_TIEP_NHAN,
        filedAt,
        responseDeadline,
        handlerUnitId,
        signRequestId,
        handling.withHandler(handler, at));
  }

  /** {@code MOI} → {@code KHONG_THU_LY}; {@link #closed} đưa sang {@code DONG} sau khi báo. */
  public Complaint notAccepted(UUID handler, ComplaintCloseReason code, String reason, Instant at) {
    if (!code.rejection()) {
      throw new IllegalArgumentException("Lý do không thụ lý không hợp lệ: " + code);
    }
    requireMove(ComplaintStatus.KHONG_THU_LY);
    return copy(
        ComplaintStatus.KHONG_THU_LY,
        filedAt,
        responseDeadline,
        handlerUnitId,
        signRequestId,
        handling.withHandler(handler, at).withClose(code, reason, at));
  }

  /** Người khiếu nại rút trước khi có kết luận: sang {@code RUT}. */
  public Complaint withdrawn(String reason, Instant at) {
    requireMove(ComplaintStatus.RUT);
    return copy(
        ComplaintStatus.RUT,
        filedAt,
        responseDeadline,
        handlerUnitId,
        signRequestId,
        handling.withClose(ComplaintCloseReason.RUT, reason, at));
  }

  /**
   * {@code KHONG_THU_LY}, {@code RUT}, {@code DA_TRA_LOI}, {@code DANG_THI_HANH} → {@code DONG}.
   */
  public Complaint closed(Instant at) {
    requireMove(ComplaintStatus.DONG);
    ComplaintHandling h =
        handling.closedAt() == null
            ? handling.withClose(handling.closeReasonCode(), handling.closeReason(), at)
            : handling;
    return copy(ComplaintStatus.DONG, filedAt, responseDeadline, handlerUnitId, signRequestId, h);
  }

  /**
   * Bước xử lý không đổi phần tiếp nhận (CORE-07-04): {@code DANG_GIAI_TRINH}, {@code
   * CHO_KET_LUAN}, {@code DA_TRA_LOI}, {@code DANG_THI_HANH} theo {@link ComplaintTransitions}.
   */
  public Complaint moved(ComplaintStatus to) {
    requireMove(to);
    return copy(to, filedAt, responseDeadline, handlerUnitId, signRequestId, handling);
  }

  /** Giữ trạng thái, chỉ tăng {@code row_version} (ghi thêm giải trình, kết luận, yêu cầu ký). */
  public Complaint touched() {
    return copy(status, filedAt, responseDeadline, handlerUnitId, signRequestId, handling);
  }

  private void requireMove(ComplaintStatus to) {
    if (!ComplaintTransitions.allowed(status, to)) {
      throw new IllegalStateException("Không chuyển được khiếu nại từ " + status + " sang " + to);
    }
  }

  private Complaint copy(
      ComplaintStatus newStatus,
      Instant newFiledAt,
      LocalDate newDeadline,
      UUID newHandlerUnit,
      UUID newSignRequest,
      ComplaintHandling newHandling) {
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
        newStatus,
        newFiledAt,
        newDeadline,
        newHandlerUnit,
        newSignRequest,
        createdAt,
        rowVersion + 1,
        newHandling);
  }
}
