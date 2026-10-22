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

  /** Một mốc khai báo khi đặt lại danh sách mốc. */
  record MilestoneDraft(int seq, LocalDate dueDate, String recognitionCondition) {}

  List<MilestoneRow> milestones(TenantId tenant, UUID keyResultId);

  /** Đặt lại toàn bộ mốc chưa đạt của KR theo {@code drafts} (mốc đã đạt giữ nguyên). */
  void replaceMilestones(
      TenantId tenant, UUID keyResultId, List<MilestoneDraft> drafts, UUID actor);

  /** Ghi ngày đạt mốc {@code seq} khi chưa ghi; trả mốc sau khi ghi, rỗng khi không có mốc. */
  Optional<MilestoneRow> markAchieved(
      TenantId tenant,
      UUID keyResultId,
      int seq,
      Instant achievedAt,
      String evidenceRef,
      UUID actor);
}
