package vn.viettel.okrkpi.okr.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Mốc của KR kiểu {@code MOC} ({@code okr.kr_milestone}, bổ sung BS-03 DC-04, CORE-01-14). */
public interface OkrMilestoneRepository {

  /** Một mốc đã lưu. */
  record MilestoneRow(
      UUID id,
      int seq,
      LocalDate dueDate,
      String recognitionCondition,
      Instant achievedAt,
      String evidenceRef,
      long rowVersion) {}

  /** Mốc cần lưu; {@code id} khác {@code null} khi giữ mốc đã có. */
  record MilestoneDraft(UUID id, int seq, LocalDate dueDate, String recognitionCondition) {}

  List<MilestoneRow> milestones(TenantId tenant, UUID keyResultId);

  /** Thay toàn bộ danh sách mốc của KR, giữ {@code id} của mốc đã có. */
  void replaceMilestones(
      TenantId tenant, UUID keyResultId, List<MilestoneDraft> drafts, UUID actor);

  /** Ghi ngày đạt mốc; lần ghi sau thay lần trước (đính chính). Rỗng khi mốc không thuộc KR. */
  Optional<MilestoneRow> markAchieved(
      TenantId tenant,
      UUID keyResultId,
      UUID milestoneId,
      Instant achievedAt,
      String evidenceRef,
      UUID actor);

  /** Hạn của KR, mốc cuối không được sau hạn này. */
  Optional<LocalDate> keyResultDueDate(TenantId tenant, int bucket, UUID keyResultId);

  /** Tăng {@code row_version} của KR khi danh sách mốc đổi; trả phiên bản mới, 0 khi lệch. */
  long touchKeyResult(TenantId tenant, int bucket, UUID keyResultId, long rowVersion, UUID actor);
}
