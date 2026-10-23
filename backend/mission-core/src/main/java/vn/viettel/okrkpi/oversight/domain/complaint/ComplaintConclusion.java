package vn.viettel.okrkpi.oversight.domain.complaint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bản nháp kết luận người xử lý ghi trước khi người có thẩm quyền ký (QT-12 bước 8). Nội dung văn
 * bản trả lời chỉ ở dạng mã hoá ({@code textEnc}, DEK {@code dekId}).
 */
public record ComplaintConclusion(
    ComplaintResolution resolution,
    List<ComplaintAdjustment> adjustments,
    byte[] textEnc,
    UUID dekId,
    String docRef,
    String noExplanationNote,
    UUID concludedBy,
    Instant concludedAt) {

  public ComplaintConclusion {
    Objects.requireNonNull(resolution, "resolution");
    Objects.requireNonNull(textEnc, "textEnc");
    Objects.requireNonNull(dekId, "dekId");
    Objects.requireNonNull(concludedBy, "concludedBy");
    Objects.requireNonNull(concludedAt, "concludedAt");
    adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
  }
}
