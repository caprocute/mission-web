package vn.viettel.okrkpi.appraisal.application.prospect.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho danh sách phân nhóm triển vọng (CORE-04-12): {@code appraisal.prospect_board} theo đơn vị và
 * {@code appraisal.prospect_group} theo người. Cột {@code group_code}, {@code suggested_group} chỉ
 * đọc bằng tài khoản {@code svc_appraisal} (TL-03); không đưa ra nhật ký hay bộ đệm.
 */
public interface ProspectBoardRepository {

  /**
   * Danh sách của đơn vị; {@code status} theo hợp đồng ({@code NHAP}, {@code DA_TRINH}, {@code
   * DA_KY}).
   */
  record Board(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID orgUnitId,
      String status,
      int boardVersion,
      long rowVersion,
      SealedText rejectionReason,
      UUID signRequestId,
      UUID updatedBy) {}

  /**
   * Cán bộ có hồ sơ năm ở đơn vị: mức năm nặng nhất giữa các luồng (đã quyết định, chưa có thì mức
   * hệ thống), cờ bố trí lại của bất kỳ luồng nào, hồ sơ năm và phiên bản quy tắc để phát sự kiện,
   * tra tiêu chí.
   */
  record Candidate(
      UUID personId,
      String personName,
      String annualRating,
      boolean reassignFlag,
      List<UUID> annualProfileIds,
      String flow,
      UUID policyVersionId) {}

  /** Dòng đã lưu của một người. */
  record Entry(
      UUID id,
      UUID personId,
      String groupCode,
      String suggestedGroup,
      Integer rankInGroup,
      SealedText reason,
      String trainingPlanRef,
      Instant decidedAt) {}

  /** Dòng cần ghi; {@code reason} {@code null} là xoá lý do. */
  record EntryWrite(
      UUID id,
      UUID personId,
      String groupCode,
      int rankInGroup,
      SealedText reason,
      String trainingPlanRef) {}

  Optional<Board> find(TenantId tenant, int bucket, UUID orgUnitId);

  /** Khoá danh sách, tạo dòng {@code NHAP} khi chưa có. */
  Board lockOrCreate(TenantId tenant, UUID periodId, int bucket, UUID orgUnitId, UUID actor);

  List<Candidate> candidates(TenantId tenant, UUID periodId, int bucket, UUID orgUnitId);

  List<Entry> entries(TenantId tenant, int bucket, List<UUID> personIds);

  void saveEntries(TenantId tenant, UUID periodId, int bucket, List<EntryWrite> rows, UUID actor);

  /** Tăng phiên bản sau khi lưu nháp; trả phiên bản mới. */
  long touch(TenantId tenant, int bucket, UUID boardId, UUID actor);

  long markSubmitted(TenantId tenant, int bucket, UUID boardId, UUID actor);

  void recordProcess(TenantId tenant, int bucket, UUID boardId, String processInstanceId);

  /** Tập thể không thông qua: về {@code NHAP}, tăng {@code board_version}, lưu lý do. */
  long markRejected(TenantId tenant, int bucket, UUID boardId, SealedText reason, UUID actor);

  long markApproved(TenantId tenant, int bucket, UUID boardId, UUID signRequestId, UUID actor);

  Optional<Board> findBySignRequest(TenantId tenant, UUID signRequestId);

  /**
   * Ký xong: {@code DA_KY}; các dòng của đơn vị nhận thời điểm ký và người quyết định là người ghi
   * kết quả thông qua ({@code updated_by} của danh sách lúc thông qua).
   */
  long markSigned(TenantId tenant, Board board, Instant signedAt);
}
