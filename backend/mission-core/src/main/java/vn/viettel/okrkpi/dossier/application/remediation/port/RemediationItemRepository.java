package vn.viettel.okrkpi.dossier.application.remediation.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lưu trữ {@code dossier.remediation_item} (CORE-06-04, BR-11-08): hạn chế cần khắc phục rút từ góp
 * ý loại hạn chế khi chủ trì kết thúc hội nghị, theo dõi qua các kỳ. Nội dung đã mã hoá trước khi
 * xuống tầng này.
 */
public interface RemediationItemRepository {

  /** Một hạn chế; {@code cipher} là bản mã của nội dung C2. */
  record ItemRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID personId,
      UUID meetingId,
      UUID reviewCommentId,
      byte[] cipher,
      UUID dekId,
      UUID duePeriodId,
      String status,
      UUID verifiedBy,
      Instant verifiedAt,
      long rowVersion) {}

  /** Nguồn hạn chế khi liệt kê cho một hội nghị. */
  enum Origin {
    /** Hạn chế của người được kiểm điểm từ hội nghị các kỳ trước. */
    KY_TRUOC,
    /** Hạn chế do chính hội nghị này tạo. */
    HOI_NGHI_NAY
  }

  void insert(TenantId tenant, ItemRow row, UUID actor);

  /** Đã có hạn chế tạo từ góp ý này (kết thúc hội nghị chạy lại không tạo trùng). */
  boolean existsForComment(TenantId tenant, int bucket, UUID reviewCommentId);

  Optional<ItemRow> lock(TenantId tenant, UUID id);

  /** Ghi trạng thái và người xác nhận; trả phiên bản mới, 0 khi phiên bản đã đổi. */
  long update(TenantId tenant, ItemRow row, long expectedVersion, UUID actor);

  /**
   * Hạn chế hiện ở hội nghị {@code meetingId} của kỳ {@code periodId}: hạn chế các kỳ khác của
   * {@code persons} và/hoặc hạn chế do hội nghị này tạo, theo {@code origin} ({@code null} là cả
   * hai), phân trang theo mã.
   */
  List<ItemRow> listForMeeting(
      TenantId tenant,
      UUID meetingId,
      UUID periodId,
      List<UUID> persons,
      Origin origin,
      UUID after,
      int limit);
}
