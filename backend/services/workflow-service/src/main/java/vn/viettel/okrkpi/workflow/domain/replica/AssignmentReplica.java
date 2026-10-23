package vn.viettel.okrkpi.workflow.domain.replica;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Trạng thái đầy đủ của một lát hiệu lực phân công mang trong EVT-15 {@code
 * org.assignment.changed.v1} hoặc {@code org.bootstrap.v1} (ECST), áp vào bản sao {@code
 * org_replica.assignment_v} của workflow-service (TL-03 "Schema org_replica"). Khoá bản sao là
 * {@code (tenant_id, id, valid_from)}; phân công kết thúc được biểu diễn bằng {@code validTo},
 * không có xoá cứng hay xoá mềm (sự kiện không mang cờ xoá).
 *
 * @param id mã phân công
 * @param personId người được phân công
 * @param orgUnitId đơn vị công tác
 * @param positionId vị trí việc làm, có thể {@code null}
 * @param jobTitleId chức danh, có thể {@code null}
 * @param kind loại phân công ({@code CHINH}, {@code KIEM_NHIEM}, {@code DIEU_DONG}...)
 * @param primary phân công chính
 * @param head người đứng đầu đơn vị
 * @param validFrom ngày bắt đầu hiệu lực
 * @param validTo ngày hết hiệu lực (không gồm ngày này), {@code null} là vô thời hạn
 * @param sourceVersion phiên bản ở nguồn, đơn điệu tăng
 */
public record AssignmentReplica(
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

  /** Độ dài tối đa của cột {@code kind varchar(16)}. */
  public static final int MAX_KIND = 16;

  public AssignmentReplica {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(orgUnitId, "orgUnitId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(validFrom, "validFrom");
    if (kind.isBlank() || kind.length() > MAX_KIND) {
      throw new IllegalArgumentException("Loại phân công rỗng hoặc quá 16 ký tự");
    }
    if (validTo != null && !validTo.isAfter(validFrom)) {
      throw new IllegalArgumentException("Ngày hết hiệu lực phải sau ngày bắt đầu hiệu lực");
    }
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("Phiên bản nguồn không được âm");
    }
  }

  /**
   * Cột {@code branch} của từ điển TL-03: phân công chính hay phụ. Sự kiện chỉ có {@code isPrimary}
   * nên suy ra {@code CHINH}/{@code PHU} như bản sao của mission-core (suy luận).
   */
  public String branch() {
    return primary ? "CHINH" : "PHU";
  }

  /** Còn hiệu lực vào ngày {@code day}: {@code validFrom <= day < validTo}. */
  public boolean activeOn(LocalDate day) {
    return !day.isBefore(validFrom) && (validTo == null || day.isBefore(validTo));
  }
}
