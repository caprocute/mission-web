package vn.viettel.okrkpi.oversight.domain.complaint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Kết luận của khiếu nại (CORE-07-04, QT-12 bước 8 đến 10, migration oversight V6): bản người xử lý
 * ghi trước khi người có thẩm quyền ký (nội dung văn bản trả lời chỉ ở dạng mã hoá {@code
 * textEnc}), yêu cầu mở lại kỳ thi hành điều chỉnh, yêu cầu ký văn bản trả lời, thời điểm ký xong
 * và thời điểm người khiếu nại xác nhận bảo lưu ý kiến (CORE-07-05).
 */
public record ComplaintConclusion(
    ComplaintResolution resolution,
    List<ComplaintAdjustment> adjustments,
    byte[] textEnc,
    UUID dekId,
    String docRef,
    String noExplanationNote,
    UUID concludedBy,
    Instant concludedAt,
    UUID reopenRequestId,
    UUID signRequestId,
    Instant respondedAt,
    Instant reservedAt) {

  public ComplaintConclusion(
      ComplaintResolution resolution,
      List<ComplaintAdjustment> adjustments,
      byte[] textEnc,
      UUID dekId,
      String docRef,
      String noExplanationNote,
      UUID concludedBy,
      Instant concludedAt,
      UUID reopenRequestId,
      UUID signRequestId,
      Instant respondedAt) {
    this(
        resolution,
        adjustments,
        textEnc,
        dekId,
        docRef,
        noExplanationNote,
        concludedBy,
        concludedAt,
        reopenRequestId,
        signRequestId,
        respondedAt,
        null);
  }

  public ComplaintConclusion {
    Objects.requireNonNull(resolution, "resolution");
    Objects.requireNonNull(textEnc, "textEnc");
    Objects.requireNonNull(dekId, "dekId");
    Objects.requireNonNull(concludedBy, "concludedBy");
    Objects.requireNonNull(concludedAt, "concludedAt");
    adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
  }

  /** Đã mở yêu cầu ký văn bản trả lời: kết luận không sửa được nữa. */
  public boolean signing() {
    return signRequestId != null;
  }

  public ComplaintConclusion withReopenRequest(UUID reopen) {
    return new ComplaintConclusion(
        resolution,
        adjustments,
        textEnc,
        dekId,
        docRef,
        noExplanationNote,
        concludedBy,
        concludedAt,
        reopen,
        signRequestId,
        respondedAt,
        reservedAt);
  }

  public ComplaintConclusion withSignRequest(UUID sign) {
    return new ComplaintConclusion(
        resolution,
        adjustments,
        textEnc,
        dekId,
        docRef,
        noExplanationNote,
        concludedBy,
        concludedAt,
        reopenRequestId,
        sign,
        respondedAt,
        reservedAt);
  }

  public ComplaintConclusion respondedAt(Instant at) {
    return new ComplaintConclusion(
        resolution,
        adjustments,
        textEnc,
        dekId,
        docRef,
        noExplanationNote,
        concludedBy,
        concludedAt,
        reopenRequestId,
        signRequestId,
        at,
        reservedAt);
  }
}
