package vn.viettel.okrkpi.org.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Một phiên bản của {@code org.reporting_line} (quan hệ báo cáo:
 * {@code TRUC_TIEP}, {@code THAM_DINH}, {@code PHU_TRACH_LINH_VUC}).
 */
public record ReportingLineVersion(
    UUID versionId,
    UUID id,
    UUID subordinatePersonId,
    UUID superiorPersonId,
    String lineType,
    String branch,
    UUID changeOrderId,
    ValidPeriod validPeriod,
    OffsetDateTime recordedFrom,
    OffsetDateTime recordedTo,
    int rowVersion) implements Versioned<ReportingLineVersion> {

  public static final Set<String> ALLOWED_LINE_TYPES =
      Set.of("TRUC_TIEP", "THAM_DINH", "PHU_TRACH_LINH_VUC");
  public static final Set<String> ALLOWED_BRANCHES = Set.of("DANG", "CHINH_QUYEN");

  public ReportingLineVersion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(subordinatePersonId, "subordinatePersonId");
    Objects.requireNonNull(superiorPersonId, "superiorPersonId");
    Objects.requireNonNull(lineType, "lineType");
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(validPeriod, "validPeriod");
    if (!ALLOWED_LINE_TYPES.contains(lineType)) {
      throw new OrgRuleViolation("ORG-422-INVALID-LINE-TYPE", "BR-01-08", null, "lineType",
          "Loại quan hệ báo cáo không hợp lệ: " + lineType);
    }
    if (!ALLOWED_BRANCHES.contains(branch)) {
      throw new OrgRuleViolation("ORG-422-INVALID-BRANCH", "BR-01-08", null, "branch",
          "Nhánh báo cáo không hợp lệ: " + branch);
    }
    if (subordinatePersonId.equals(superiorPersonId)) {
      throw new OrgRuleViolation("ORG-422-SELF-REPORTING", "BR-01-08", null, "superiorPersonId",
          "Cán bộ không thể tự báo cáo cho chính mình.");
    }
  }

  @Override
  public ReportingLineVersion withValidPeriod(ValidPeriod period) {
    return new ReportingLineVersion(versionId, id, subordinatePersonId, superiorPersonId, lineType, branch,
        changeOrderId, period, recordedFrom, recordedTo, rowVersion);
  }

  public static ReportingLineVersion draft(UUID id, UUID subordinatePersonId, UUID superiorPersonId,
      String lineType, String branch, UUID changeOrderId, ValidPeriod validPeriod) {
    return new ReportingLineVersion(null, id, subordinatePersonId, superiorPersonId, lineType, branch,
        changeOrderId, validPeriod, null, null, 0);
  }
}
