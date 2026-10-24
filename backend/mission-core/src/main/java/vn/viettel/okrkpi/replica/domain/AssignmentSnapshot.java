package vn.viettel.okrkpi.replica.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Trạng thái phân công mang trong EVT-15. */
public record AssignmentSnapshot(
    UUID id,
    UUID personId,
    UUID orgUnitId,
    UUID positionId,
    UUID jobTitleId,
    String kind,
    boolean primary,
    boolean head,
    LocalDate validFrom,
    LocalDate validTo,
    long sourceVersion,
    String jobTitleName) {

  /** Sự kiện chưa mang tên chức danh (trước bổ sung {@code jobTitleName}). */
  public AssignmentSnapshot(
      UUID id,
      UUID personId,
      UUID orgUnitId,
      UUID positionId,
      UUID jobTitleId,
      String kind,
      boolean primary,
      boolean head,
      LocalDate validFrom,
      LocalDate validTo,
      long sourceVersion) {
    this(
        id,
        personId,
        orgUnitId,
        positionId,
        jobTitleId,
        kind,
        primary,
        head,
        validFrom,
        validTo,
        sourceVersion,
        null);
  }

  /**
   * Cột {@code branch} của từ điển TL-03: phân công chính hay phụ. Sự kiện chỉ có {@code isPrimary}
   * nên suy ra {@code CHINH}/{@code PHU} (suy luận, điểm lệch ghi ở báo cáo CORE-00-03).
   */
  public String branch() {
    return primary ? "CHINH" : "PHU";
  }
}
