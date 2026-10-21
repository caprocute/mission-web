package vn.viettel.okrkpi.org.domain;

import java.util.List;
import java.util.UUID;

/** Kiểm tra thuần cho quyết định thay đổi tổ chức (BR-01-05, BR-01-07; MSG-01-09). */
public final class OrgChangeRules {

  private OrgChangeRules() {
  }

  public static void validate(OrgChangeType type, UUID targetUnitId, List<UUID> relatedUnitIds, int newUnitCount,
      String newName) {
    List<UUID> related = relatedUnitIds == null ? List.of() : relatedUnitIds;
    if (related.contains(targetUnitId)) {
      throw new OrgRuleViolation("ORG-422-VALIDATION", null, null, "relatedUnitIds",
          "Đơn vị liên quan không được trùng đơn vị đang thay đổi.");
    }
    if (related.stream().distinct().count() != related.size()) {
      throw new OrgRuleViolation("ORG-422-VALIDATION", null, null, "relatedUnitIds",
          "Danh sách đơn vị liên quan có đơn vị trùng nhau.");
    }
    if (type.requiresPredecessor() && related.isEmpty()) {
      throw new OrgRuleViolation("ORG-422-PREDECESSOR-REQUIRED", "BR-01-07", "MSG-01-09", "relatedUnitIds",
          "Sáp nhập cần khai báo ít nhất một đơn vị tiền thân.");
    }
    switch (type) {
      case THANH_LAP -> requireNewUnits(newUnitCount >= 1, "Thành lập cần ít nhất một đơn vị mới.");
      case HOP_NHAT -> requireNewUnits(newUnitCount == 1, "Hợp nhất tạo đúng một đơn vị mới.");
      case CHIA_TACH -> requireNewUnits(newUnitCount + related.size() >= 1,
          "Chia tách cần ít nhất một đơn vị kế thừa.");
      case DOI_TEN -> {
        if (newName == null || newName.isBlank()) {
          throw new OrgRuleViolation("ORG-422-VALIDATION", null, null, "newName", "Đổi tên cần nhập tên mới.");
        }
      }
      case SAP_NHAP, GIAI_THE -> {
        if (newUnitCount > 0) {
          throw new OrgRuleViolation("ORG-422-VALIDATION", null, null, "newUnits",
              "Loại thay đổi này không tạo đơn vị mới.");
        }
      }
      default -> throw new IllegalStateException(type.name());
    }
  }

  private static void requireNewUnits(boolean ok, String detail) {
    if (!ok) {
      throw new OrgRuleViolation("ORG-422-VALIDATION", null, null, "newUnits", detail);
    }
  }

  /** BR-01-05: mỗi đơn vị thuộc đúng một khối đối tượng PL 2A. */
  public static void requireSubjectGroup(UUID subjectGroupId, boolean validAtDate, String field) {
    if (subjectGroupId == null || !validAtDate) {
      throw new OrgRuleViolation("ORG-422-SUBJECT-GROUP", "BR-01-05", null, field,
          "Đơn vị phải thuộc đúng một khối đối tượng còn hiệu lực.");
    }
  }
}
