package vn.viettel.okrkpi.org.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Một phiên bản của {@code org.assignment}; không có trường C2. */
public record AssignmentVersion(
    UUID versionId,
    UUID id,
    UUID personId,
    UUID orgUnitId,
    UUID positionId,
    UUID jobTitleId,
    String kind,
    String branch,
    boolean head,
    UUID changeOrderId,
    ValidPeriod validPeriod,
    OffsetDateTime recordedFrom,
    OffsetDateTime recordedTo,
    int rowVersion) implements Versioned<AssignmentVersion> {

  @Override
  public AssignmentVersion withValidPeriod(ValidPeriod period) {
    return new AssignmentVersion(versionId, id, personId, orgUnitId, positionId, jobTitleId, kind, branch, head,
        changeOrderId, period, recordedFrom, recordedTo, rowVersion);
  }

  /**
   * Phân công tương ứng ở đơn vị mới khi chuyển theo quyết định: mã phân công mới, giữ người, chức danh, loại,
   * nhánh; bỏ vị trí việc làm vì vị trí thuộc đơn vị cũ (suy luận).
   */
  public AssignmentVersion movedTo(UUID newId, UUID unitId, ValidPeriod period) {
    return new AssignmentVersion(null, newId, personId, unitId, null, jobTitleId, kind, branch, head, null, period,
        null, null, 0);
  }
}
