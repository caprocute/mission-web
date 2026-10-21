package vn.viettel.okrkpi.org.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.org.domain.OrgChangeType;

/** Quyết định thay đổi tổ chức theo {@code OrgChangeRequest} của hợp đồng. */
public record ChangeCommand(
    OrgChangeType type,
    LocalDate effectiveOn,
    String orderNo,
    LocalDate orderDate,
    String signedDocRef,
    String newName,
    List<UUID> relatedUnitIds,
    List<UnitCommand> newUnits,
    UUID moveAssignmentsTo) {

  public ChangeCommand {
    relatedUnitIds = relatedUnitIds == null ? List.of() : List.copyOf(relatedUnitIds);
    newUnits = newUnits == null ? List.of() : List.copyOf(newUnits);
  }
}
