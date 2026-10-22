package vn.viettel.okrkpi.dossier.application.comment.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lưu trữ {@code dossier.review_comment} (CORE-06-03): chỉ thêm, không sửa, không xoá. Nội dung đã
 * mã hoá ({@code comment_enc}, {@code dek_id}) trước khi xuống tầng này.
 */
public interface ReviewCommentRepository {

  /** Một góp ý; {@code cipher} là bản mã của nội dung C2. */
  record CommentRow(
      UUID id,
      UUID meetingId,
      UUID periodId,
      int periodBucket,
      UUID subjectPersonId,
      UUID commenterPersonId,
      String source,
      String kind,
      String phase,
      byte[] cipher,
      UUID dekId,
      LocalDate remediationDue,
      Instant createdAt) {}

  /** Vị trí sau cùng của trang trước (thứ tự thời điểm ghi rồi mã góp ý). */
  record Position(Instant createdAt, UUID id) {}

  void insert(TenantId tenant, CommentRow row, UUID actor);

  List<CommentRow> list(
      TenantId tenant,
      int bucket,
      UUID meetingId,
      String phase,
      String kind,
      Position after,
      int limit);
}
